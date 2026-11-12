/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Pinned native bundles shared by the Gradle verifier and first-launch installer.
 * No compiler, shell command or mutable remote metadata is used at launch.
 */
final class NativeBundles {
    static final List<String> PLATFORMS = List.of("linux64", "linuxarm64", "win64", "arm64");
    static final long MAX_ARCHIVE = 1024L * 1024 * 1024;
    static final long MAX_UNPACKED = 3L * MAX_ARCHIVE;
    static final int MAX_ENTRIES = 20_000;
    private static final LinkOption[] NO_LINKS = {LinkOption.NOFOLLOW_LINKS};
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private NativeBundles() {}

    static String platform(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if ((os.equals("linux") || os.startsWith("windows")) && (arm || x64)) {
            return os.equals("linux") ? (arm ? "linuxarm64" : "linux64") : (arm ? "arm64" : "win64");
        }
        throw new IllegalArgumentException("RTX Remix supports Linux and Windows on x86-64 or ARM64. "
                + "Unsupported OS/CPU: " + osName + " / " + osArch + ". Use a matching 64-bit Java 25 launcher.");
    }

    record Bundle(String platform, String api, String bridgeAbi, String interfacePointers, String runtimeSource, String bridgeSource,
                  String headerSha256, String bridgeSourceSha256, URI url, String sha256, long bytes,
                  long unpackedBytes, String minimumOs, String minimumVulkan, String minimumDriver) {
        String identity() { return api + "|" + bridgeAbi + "|" + interfacePointers + "|" + platform + "|" + sha256; }
        String runtimeFile() { return platform.startsWith("linux") ? "libremixapi.so" : "d3d9.dll"; }
        String bridgeFile() { return platform.startsWith("linux") ? "remixbridge.so" : "remixbridge.dll"; }
    }

    /** The portable Java artifact may describe unpublished native platforms explicitly. */
    static void verifyBootstrapManifest(Path path) throws IOException {
        Properties p = properties(path);
        require("1".equals(p.getProperty("schema")), "Unsupported native manifest schema");
        require(String.join(",", PLATFORMS).equals(p.getProperty("platforms")), "Manifest must declare all four platforms");
        require(Set.of("pending", "final").contains(required(p, "freeze")), "Invalid native source freeze state");
        List<String> available = new ArrayList<>();
        for (String platform : PLATFORMS) {
            String status = required(p, platform + ".status");
            require(status.equals("available") || status.equals("unavailable"), "Invalid native platform status: " + platform);
            if (status.equals("available")) available.add(platform);
            else required(p, platform + ".reason");
        }
        if (!available.isEmpty()) manifest(path, available);
    }

    static Map<String, Bundle> manifest(Path path, List<String> selected) throws IOException {
        Properties p = properties(path);
        require("1".equals(p.getProperty("schema")), "Unsupported native manifest schema");
        require(String.join(",", PLATFORMS).equals(p.getProperty("platforms")), "Manifest must declare all four platforms");
        List<String> missing = new ArrayList<>();
        for (String arch : selected) {
            require(PLATFORMS.contains(arch), "Unknown native platform: " + arch);
            if (!"available".equals(p.getProperty(arch + ".status"))) {
                missing.add(arch + ": " + p.getProperty(arch + ".reason", "no verified bundle published"));
            }
        }
        require(missing.isEmpty(), "Compatible RTX Remix native package is not published for the requested platform(s):\n" + String.join("\n", missing));
        require("final".equals(p.getProperty("freeze")), "Native API/source freeze is pending");
        Properties contract = sourceContract();
        for (String key : java.util.List.of("api", "bridgeAbi", "interfacePointers", "headerSha256", "bridgeSourceSha256")) {
            require(required(contract, key).equals(required(p, key)), "Native manifest differs from the compiled source contract: " + key);
        }
        String runtimeSource = hex(p, "runtimeSource", 40);
        String bridgeSource = hex(p, "bridgeSource", 40);
        String header = hex(p, "headerSha256", 64);
        String bridgeHash = hex(p, "bridgeSourceSha256", 64);
        Map<String, Bundle> bundles = new LinkedHashMap<>();
        for (String arch : selected) {
            String prefix = arch + ".";
            URI url;
            try { url = URI.create(required(p, prefix + "url")); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid bundle URL for " + arch, e); }
            require("https".equals(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null
                    && url.getFragment() == null, "Bundle URL must be versioned HTTPS: " + arch);
            require(!url.getPath().matches("(?i).*/latest(/.*)?"), "Mutable latest bundle URLs are forbidden");
            bundles.put(arch, new Bundle(arch, p.getProperty("api"), p.getProperty("bridgeAbi"), p.getProperty("interfacePointers"), runtimeSource, bridgeSource, header, bridgeHash,
                    url, hex(p, prefix + "sha256", 64), number(p, prefix + "bytes", MAX_ARCHIVE),
                    number(p, prefix + "unpackedBytes", MAX_UNPACKED), required(p, prefix + "minimumOs"),
                    required(p, prefix + "minimumVulkan"), required(p, prefix + "minimumDriver")));
        }
        return bundles;
    }

    /** Returns only a fully hashed archive. A failed transfer cannot modify the installed SDK. */
    static Path acquire(Bundle bundle, Path bundled, Path cache) throws IOException {
        return acquire(bundle, bundled, cache, false);
    }

    // The loopback exception is confined to JVM fixtures, never a system property or release manifest.
    static Path acquire(Bundle b, Path bundled, Path cache, boolean loopbackFixture) throws IOException {
        boolean hasBundled = Files.exists(bundled, NO_LINKS);
        if (hasBundled && bundled.getFileSystem().equals(java.nio.file.FileSystems.getDefault())) {
            validate(bundled, b, null);
            return bundled;
        }
        safeDirectory(cache);
        Path lock = cache.resolve("download.lock");
        regularOrAbsent(lock);
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.tryLock()) {
            require(held != null, "Another RTX Remix native download is active. Relaunch after it finishes.");
            Path target = cache.resolve(b.sha256 + ".zip");
            if (Files.exists(target, NO_LINKS)) {
                try {
                    validate(target, b, null);
                    return target;
                } catch (IOException corrupt) {
                    rejectLink(target);
                    Files.delete(target);
                }
            }
            Path part = Files.createTempFile(cache, "download-", ".part");
            try {
                if (hasBundled) {
                    require(Files.isRegularFile(bundled, NO_LINKS) && Files.size(bundled) == b.bytes,
                            "Bundled native archive size mismatch");
                    Files.copy(bundled, part, StandardCopyOption.REPLACE_EXISTING);
                } else download(b, part, loopbackFixture);
                validate(part, b, null);
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } finally {
                Files.deleteIfExists(part);
            }
        }
    }

    private static void download(Bundle b, Path part, boolean fixture) throws IOException {
        URI url = b.url;
        long deadline = System.nanoTime() + DOWNLOAD_TIMEOUT.toNanos();
        for (int redirects = 0; redirects <= 3; redirects++) {
            boolean local = fixture && "http".equals(url.getScheme())
                    && Set.of("127.0.0.1", "[::1]", "localhost").contains(url.getHost());
            require(("https".equals(url.getScheme()) || local) && url.getUserInfo() == null,
                    "Refusing non-HTTPS native download");
            HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = connection.getResponseCode();
                if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                    String location = connection.getHeaderField("Location");
                    require(location != null, "Native download redirect has no destination");
                    url = url.resolve(location);
                    continue;
                }
                require(status == 200, "Native download failed: HTTP " + status + " from " + url.getHost()
                        + ". Check the connection and relaunch; an already verified cache works offline.");
                long length = connection.getContentLengthLong();
                require(length == -1 || length == b.bytes, "Native download Content-Length mismatch");
                try (InputStream input = connection.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                    MessageDigest hash = digest();
                    byte[] buffer = new byte[65536];
                    long count = 0;
                    for (int n; (n = input.read(buffer)) != -1;) {
                        require(System.nanoTime() < deadline, "Native download exceeded five minutes");
                        count += n;
                        require(count <= b.bytes, "Native download is larger than its pinned size");
                        hash.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                    require(count == b.bytes && hex(hash).equals(b.sha256), "Native download size/SHA-256 mismatch");
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many native download redirects");
    }

    /** Checks archive structure, exact inventory, metadata, hashes and ELF/PE architecture before publishing it. */
    static void validate(Path archive, Bundle b, Path output) throws IOException {
        rejectLink(archive);
        require(Files.isRegularFile(archive, NO_LINKS) && Files.size(archive) == b.bytes,
                "Native archive size mismatch for " + b.platform);
        require(hash(archive).equals(b.sha256), "Native archive SHA-256 mismatch for " + b.platform);
        Map<String, Long> entries = centralDirectory(archive, b.unpackedBytes);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Properties metadata = zipProperties(zip, "bundle.properties");
            Map<String, String> expected = new LinkedHashMap<>(Map.of("schema", "1", "platform", b.platform, "api", b.api,
                    "bridgeAbi", b.bridgeAbi, "runtimeSource", b.runtimeSource, "bridgeSource", b.bridgeSource,
                    "headerSha256", b.headerSha256, "bridgeSourceSha256", b.bridgeSourceSha256,
                    "minimumOs", b.minimumOs));
            expected.put("interfacePointers", b.interfacePointers);
            for (var e : expected.entrySet()) {
                require(e.getValue().equals(metadata.getProperty(e.getKey())), "Native bundle metadata mismatch: " + e.getKey());
            }
            require(b.minimumVulkan.equals(metadata.getProperty("minimumVulkan"))
                    && b.minimumDriver.equals(metadata.getProperty("minimumDriver")), "Native GPU contract mismatch");
            Properties inventory = zipProperties(zip, "inventory.properties");
            Set<String> files = new HashSet<>();
            Set<String> licenses = new HashSet<>();
            for (String key : inventory.stringPropertyNames()) {
                if (key.startsWith("file.")) files.add(safeName(key.substring(5)));
                else if (key.startsWith("license.")) {
                    for (String license : inventory.getProperty(key).split(",", -1)) licenses.add(safeName(license));
                }
                else throw new IOException("Unknown native inventory key: " + key);
            }
            Set<String> payload = new HashSet<>(entries.keySet());
            payload.remove("bundle.properties");
            payload.remove("inventory.properties");
            require(payload.equals(files), "Native bundle has missing/uninventoried files");
            require(files.containsAll(List.of(b.runtimeFile(), b.bridgeFile(), "audit.json", "jni-signatures.properties")),
                    "Missing runtime, bridge or producer audit");
            Properties signatures = zipProperties(zip, "jni-signatures.properties");
            require(signatures.equals(jniSignatures()), "Native bundle JNI descriptors differ from the Java facade");
            require(!licenses.isEmpty() && files.containsAll(licenses)
                    && licenses.stream().allMatch(s -> s.startsWith("licenses/")), "Missing native license inventory/texts");
            if (output != null) {
                require(!Files.exists(output, NO_LINKS), "Native staging directory already exists");
                safeDirectory(output);
            }
            for (String name : entries.keySet()) {
                var entry = zip.getEntry(name);
                require(entry != null && entry.getSize() == entries.get(name), "Native ZIP directory mismatch");
                String spec = inventory.getProperty("file." + name);
                if (spec != null) {
                    require(spec.matches("[1-9][0-9]*:[0-9a-f]{64}"), "Invalid inventory value: " + name);
                    require(spec.startsWith(entry.getSize() + ":"), "Native file size mismatch: " + name);
                }
                MessageDigest hash = digest();
                long count = 0;
                byte[] header = new byte[4096];
                int headerBytes = 0;
                Path destination = output == null ? null : output.resolve(name);
                if (destination != null) safeDirectory(destination.getParent());
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream out = destination == null ? OutputStream.nullOutputStream()
                             : Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536];
                    for (int n; (n = input.read(buffer)) != -1;) {
                        count += n;
                        require(count <= entry.getSize(), "Expanded native file exceeds inventory: " + name);
                        int copy = Math.min(n, header.length - headerBytes);
                        System.arraycopy(buffer, 0, header, headerBytes, copy);
                        headerBytes += copy;
                        hash.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                }
                require(count == entry.getSize(), "Truncated native ZIP entry: " + name);
                if (spec != null) require(spec.endsWith(":" + hex(hash)), "Native file hash mismatch: " + name);
                if (name.matches("(?i).*([.]so([.][0-9]+)*|[.]dll|[.]exe)$")) {
                    require(inventory.containsKey("license." + name), "Missing binary license assignment: " + name);
                    machine(header, headerBytes, b.platform, name);
                }
            }
        }
    }

    static boolean installedMatches(Path sdk, Bundle b) throws IOException {
        if (!Files.isDirectory(sdk, NO_LINKS)) return false;
        Path metadata = sdk.resolve("bundle.properties");
        Path inventory = sdk.resolve("inventory.properties");
        rejectLink(metadata);
        rejectLink(inventory);
        if (!Files.isRegularFile(metadata, NO_LINKS) || !Files.isRegularFile(inventory, NO_LINKS)) return false;
        Properties m = properties(metadata);
        if (!b.api.equals(m.getProperty("api")) || !b.bridgeAbi.equals(m.getProperty("bridgeAbi"))
                || !b.platform.equals(m.getProperty("platform"))
                || !b.runtimeSource.equals(m.getProperty("runtimeSource"))
                || !b.bridgeSourceSha256.equals(m.getProperty("bridgeSourceSha256"))
                || !b.interfacePointers.equals(m.getProperty("interfacePointers"))) return false;
        Properties files = properties(inventory);
        if (!files.containsKey("file." + b.runtimeFile()) || !files.containsKey("file." + b.bridgeFile())) return false;
        for (String key : files.stringPropertyNames()) {
            if (!key.startsWith("file.")) continue;
            Path file = sdk.resolve(safeName(key.substring(5)));
            // Validate ancestors as well as the final file before reading any bytes.
            Path parent = file.getParent();
            while (parent != null && parent.startsWith(sdk)) { rejectLink(parent); parent = parent.getParent(); }
            rejectLink(file);
            if (!Files.isRegularFile(file, NO_LINKS)
                    || !files.getProperty(key).equals(Files.size(file) + ":" + hash(file))) return false;
        }
        return true;
    }

    private static void machine(byte[] h, int size, String platform, String name) throws IOException {
        boolean arm = platform.equals("arm64") || platform.equals("linuxarm64");
        if (platform.startsWith("linux")) {
            require(size >= 64 && h[0] == 127 && h[1] == 'E' && h[2] == 'L' && h[3] == 'F'
                    && h[4] == 2 && h[5] == 1 && u16(h, 18) == (arm ? 183 : 62), "Wrong ELF architecture: " + name);
        } else {
            require(size >= 64 && h[0] == 'M' && h[1] == 'Z', "Expected Windows PE binary: " + name);
            long offset = u32(h, 60);
            require(offset <= size - 26 && u32(h, (int) offset) == 0x4550
                    && u16(h, (int) offset + 4) == (arm ? 0xaa64 : 0x8664)
                    && u16(h, (int) offset + 24) == 0x20b, "Wrong PE architecture (ARM64EC is unsupported): " + name);
        }
    }

    /** Rejects symlinks, special files, encryption, duplicate/case-alias names, ZIP64 and expansion bombs. */
    private static Map<String, Long> centralDirectory(Path archive, long unpackedBytes) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(archive.toFile(), "r")) {
            int tailLength = (int) Math.min(f.length(), 65557);
            byte[] tail = new byte[tailLength];
            f.seek(f.length() - tailLength);
            f.readFully(tail);
            int eocd = -1;
            for (int i = tailLength - 22; i >= 0; i--) {
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tailLength) { eocd = i; break; }
            }
            require(eocd >= 0, "Native ZIP has no complete end directory");
            int count = u16(tail, eocd + 10);
            long centralBytes = u32(tail, eocd + 12), offset = u32(tail, eocd + 16);
            require(u32(tail, eocd + 4) == 0 && u16(tail, eocd + 8) == count && count > 0 && count <= MAX_ENTRIES
                    && centralBytes <= 16 * 1024 * 1024 && offset + centralBytes == f.length() - tailLength + eocd,
                    "Unsupported or oversized native ZIP directory");
            f.seek(offset);
            Map<String, Long> entries = new LinkedHashMap<>();
            Set<String> caseNames = new HashSet<>();
            long total = 0;
            for (int i = 0; i < count; i++) {
                byte[] c = new byte[46];
                f.readFully(c);
                require(u32(c, 0) == 0x02014b50L, "Invalid native ZIP central entry");
                int flags = u16(c, 8), method = u16(c, 10), mode = (int) (u32(c, 38) >>> 16);
                require((flags & 1) == 0 && (method == 0 || method == 8)
                        && ((mode & 0170000) == 0 || (mode & 0170000) == 0100000)
                        && (mode & 07022) == 0, "Unsafe native ZIP entry type/permissions");
                int length = u16(c, 28), extra = u16(c, 30), comment = u16(c, 32);
                require(length > 0 && length <= 1024, "Invalid native ZIP path length");
                byte[] nameBytes = new byte[length];
                f.readFully(nameBytes);
                String name = safeName(new String(nameBytes, StandardCharsets.UTF_8));
                require(!name.matches("(?i).*(python[^/]*[.](dll|so)([.][0-9]+)*|[.](pyd|pyc|pdb|a|lib|o|obj))$"),
                        "Python/development native payload is prohibited: " + name);
                long size = u32(c, 24), compressed = u32(c, 20);
                require(size > 0 && size <= MAX_UNPACKED && size <= Math.max(1024 * 1024L, compressed * 250)
                        && u16(c, 34) == 0 && u32(c, 42) < offset, "Oversized/split native ZIP entry: " + name);
                require(entries.putIfAbsent(name, size) == null && caseNames.add(name.toLowerCase(Locale.ROOT)),
                        "Duplicate native ZIP entry: " + name);
                total += size;
                require(total <= unpackedBytes, "Native ZIP expanded size exceeds manifest");
                f.seek(f.getFilePointer() + extra + comment);
            }
            require(f.getFilePointer() == offset + centralBytes && total == unpackedBytes,
                    "Native ZIP directory/expanded size mismatch");
            return entries;
        }
    }

    static String safeName(String name) throws IOException {
        require(!name.isEmpty() && name.length() <= 1024 && !name.contains("\\"), "Unsafe native path: " + name);
        for (String segment : name.split("/", -1)) {
            require(segment.matches("[A-Za-z0-9_@+.-]{1,240}") && !segment.equals(".") && !segment.equals("..")
                    && !segment.endsWith(".") && !segment.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])([.].*)?"),
                    "Unsafe native path: " + name);
        }
        return name;
    }

    static void safeDirectory(Path directory) throws IOException {
        Path full = directory.toAbsolutePath().normalize();
        Path current = full.getRoot();
        for (Path segment : full) {
            current = current.resolve(segment);
            rejectLink(current);
            if (!Files.exists(current, NO_LINKS)) Files.createDirectory(current);
            require(Files.isDirectory(current, NO_LINKS), "Not a directory: " + current);
        }
    }

    static void regularOrAbsent(Path path) throws IOException {
        rejectLink(path);
        require(!Files.exists(path, NO_LINKS) || Files.isRegularFile(path, NO_LINKS),
                "Refusing non-regular native installation file: " + path);
    }

    static void rejectLink(Path path) throws IOException {
        require(!Files.isSymbolicLink(path), "Refusing symbolic link in native installation: " + path);
    }

    static Properties properties(Path file) throws IOException {
        require(Files.size(file) <= 2 * 1024 * 1024, "Oversized native metadata");
        try (InputStream in = Files.newInputStream(file)) { return properties(in); }
    }

    private static Properties zipProperties(ZipFile zip, String name) throws IOException {
        var entry = zip.getEntry(name);
        require(entry != null && entry.getSize() > 0 && entry.getSize() <= 2 * 1024 * 1024, "Missing/oversized " + name);
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(2 * 1024 * 1024 + 1);
            require(bytes.length <= 2 * 1024 * 1024, "Expanded native metadata is oversized");
            return properties(new java.io.ByteArrayInputStream(bytes));
        }
    }

    private static Properties properties(InputStream in) throws IOException {
        Properties p = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate native metadata key: " + key);
                return super.put(key, value);
            }
        };
        try { p.load(in); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid native metadata", e); }
        return p;
    }

    static String hash(Path path) throws IOException {
        MessageDigest hash = digest();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) hash.update(buffer, 0, n);
        }
        return hex(hash);
    }

    static void verifyTextures(Path directory, Path inventory) throws IOException {
        Properties textures = properties(inventory);
        require(textures.stringPropertyNames().equals(Set.of("overcast_thunderstorm_bc6u.dds", "water_normals_flipbook.dds")),
                "Unexpected runtime DDS inventory");
        for (String name : textures.stringPropertyNames()) {
            Path texture = directory.resolve(name);
            regularOrAbsent(texture);
            require(Files.isRegularFile(texture, NO_LINKS)
                    && textures.getProperty(name).equals(Files.size(texture) + ":" + hash(texture)),
                    "Runtime texture size/SHA-256 mismatch: " + name);
        }
    }

    static Properties sourceContract() throws IOException {
        try (InputStream input = NativeBundles.class.getResourceAsStream("/native-source.properties")) {
            require(input != null, "Missing compiled native source contract");
            return properties(input);
        }
    }

    static Properties jniSignatures() throws IOException {
        Properties signatures = new Properties();
        for (Class<?> type : java.util.List.of(com.nvidia.rtxremix.RemixBridge.class, com.nvidia.rtxremix.VoxelDag.class)) {
            for (var method : type.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isNative(method.getModifiers())) continue;
                String export = "Java_" + type.getName().replace("_", "_1").replace('.', '_')
                        + "_" + method.getName().replace("_", "_1");
                String descriptor = java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                        .descriptorString();
                require(signatures.putIfAbsent(export, descriptor) == null, "Overloaded JNI export requires a mangled descriptor: " + export);
            }
        }
        return signatures;
    }

    static String bridgeSourceHash(Path root) throws IOException {
        MessageDigest hash = digest();
        for (String name : Files.readAllLines(root.resolve("packaging/native-bundles/source-files.txt"))) {
            if (name.isBlank()) continue;
            hash.update((name + "\n").getBytes(StandardCharsets.UTF_8));
            hash.update((hash(root.resolve("rtxremix-java/native").resolve(name)) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return hex(hash);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(MessageDigest hash) { return HexFormat.of().formatHex(hash.digest()); }
    private static int u16(byte[] b, int p) { return Byte.toUnsignedInt(b[p]) | Byte.toUnsignedInt(b[p + 1]) << 8; }
    private static long u32(byte[] b, int p) { return Integer.toUnsignedLong(u16(b, p) | u16(b, p + 2) << 16); }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        require(value != null && !value.isBlank(), "Missing native manifest field: " + key);
        return value;
    }
    private static String hex(Properties p, String key, int count) throws IOException {
        String value = required(p, key);
        require(value.matches("[0-9a-f]{" + count + "}"), "Invalid pinned native hash/revision: " + key);
        return value;
    }
    private static long number(Properties p, String key, long max) throws IOException {
        try {
            long value = Long.parseLong(required(p, key));
            require(value > 0 && value <= max, "Invalid native byte limit: " + key);
            return value;
        } catch (NumberFormatException e) { throw new IOException("Invalid native byte limit: " + key, e); }
    }
    static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }

    /** Gradle uses the same validation as the player installer. No native initialization occurs. */
    public static void main(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("verify")) {
            var bundles = manifest(Path.of(args[1]), List.of(args[3].split(",")));
            Path root = Path.of(args[4]);
            for (Bundle b : bundles.values()) {
                require(hash(root.resolve("rtxremix-java/native/include/remix_c.h")).equals(b.headerSha256)
                        && bridgeSourceHash(root).equals(b.bridgeSourceSha256), "Native source/header changed after ABI freeze");
                Path dir = Path.of(args[2]);
                safeDirectory(dir);
                Path archive = dir.resolve(b.platform + ".zip");
                Path verified = acquire(b, archive, dir.resolve("cache"));
                if (!verified.equals(archive)) Files.copy(verified, archive);
                System.out.println("Verified " + b.identity());
            }
        } else if (args.length == 5 && args[0].equals("unpack")) {
            Bundle b = manifest(Path.of(args[1]), List.of(args[3])).get(args[3]);
            validate(Path.of(args[2]).resolve(b.platform + ".zip"), b, Path.of(args[4]));
        } else if (args.length == 2 && args[0].equals("bootstrap")) {
            verifyBootstrapManifest(Path.of(args[1]));
        } else if (args.length == 4 && args[0].equals("jar")) {
            verifyJar(Path.of(args[1]), List.of(args[2].split(",")), Path.of(args[3]));
        } else throw new IllegalArgumentException("Use verify <manifest> <bundleDir> <platforms> <repo> or jar <jar> <platforms> <repo>");
    }

    private static void verifyJar(Path jar, List<String> platforms, Path root) throws IOException {
        Path temp = Files.createTempDirectory(jar.toAbsolutePath().getParent(), "native-verify-");
        try (var fs = java.nio.file.FileSystems.newFileSystem(jar)) {
            Path top = fs.getPath("/");
            Path manifestPath = top.resolve("native/manifest.properties");
            verifyBootstrapManifest(manifestPath);
            require(properties(manifestPath).equals(properties(root.resolve("packaging/native-bundles/manifest.properties"))),
                    "Packaged native manifest differs from source");
            List<String> bundledPlatforms = new ArrayList<>();
            for (String platform : platforms) {
                if (Files.exists(top.resolve("native/bundles/" + platform + ".zip"))) bundledPlatforms.add(platform);
            }
            var bundles = bundledPlatforms.isEmpty() ? Map.<String, Bundle>of() : manifest(manifestPath, bundledPlatforms);
            verifyTextures(top.resolve("native/textures"), top.resolve("runtime-textures.properties"));
            require(hash(top.resolve("runtime-textures.properties")).equals(hash(root.resolve("src/main/resources/runtime-textures.properties"))),
                    "Packaged runtime texture inventory differs from source");
            require(properties(top.resolve("native-source.properties")).equals(sourceContract()),
                    "Packaged native contract differs from the compiled source");
            // Fabric's own metadata must name the nested API; its original published
            // archive includes the API modules and their loader metadata recursively.
            String mod = Files.readString(top.resolve("fabric.mod.json"));
            String nested = "META-INF/jars/fabric-api-0.165.2+26.3.jar";
            require(mod.contains(nested), "Fabric metadata does not declare the bundled Fabric API");
            require(hash(top.resolve(nested)).equals("2612a078a5daa06d9a14cabad40fe66de36b8cb22ee8cdf346d5d9894478c392"),
                    "Bundled Fabric API hash mismatch");
            for (Bundle b : bundles.values()) {
                Path local = temp.resolve(b.platform + ".zip");
                Files.copy(top.resolve("native/bundles/" + b.platform + ".zip"), local);
                try { validate(local, b, null); }
                finally { Files.deleteIfExists(local); }
            }
            if (Files.exists(top.resolve("native/bundles"))) {
                try (var stream = Files.list(top.resolve("native/bundles"))) {
                    require(stream.count() == bundledPlatforms.size(), "Unexpected native platform payload");
                }
            }
            for (String file : List.of("native/rtx.conf", "rtx-remix-shipped-payload.sha256",
                    "native/textures/overcast_thunderstorm_bc6u.dds", "native/textures/water_normals_flipbook.dds")) {
                require(Files.isRegularFile(top.resolve(file)) && Files.size(top.resolve(file)) > 0, "Missing JAR payload: " + file);
            }
            for (String dir : List.of("packaging/licenses", "packaging/rtx-remix")) {
                try (var stream = Files.walk(root.resolve(dir))) {
                    for (Path source : stream.filter(Files::isRegularFile).toList()) {
                        String prefix = dir.equals("packaging/licenses") ? "packaging/licenses/" : "native/rtx-remix/";
                        Path target = top.resolve(prefix + root.resolve(dir).relativize(source).toString().replace('\\', '/'));
                        require(Files.isRegularFile(target) && hash(target).equals(hash(source)), "Missing/changed packaged file: " + source);
                    }
                }
            }
            Set<String> allowedNative = new HashSet<>(List.of("native/manifest.properties", "native/rtx.conf",
                    "native/textures/overcast_thunderstorm_bc6u.dds", "native/textures/water_normals_flipbook.dds"));
            for (String platform : bundledPlatforms) allowedNative.add("native/bundles/" + platform + ".zip");
            try (var stream = Files.walk(root.resolve("packaging/rtx-remix"))) {
                for (Path source : stream.filter(Files::isRegularFile).toList()) {
                    allowedNative.add("native/rtx-remix/" + root.resolve("packaging/rtx-remix")
                            .relativize(source).toString().replace('\\', '/'));
                }
            }
            try (var stream = Files.walk(top)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    String name = top.relativize(path).toString();
                    require(!name.startsWith("resourcepacks/") && !name.startsWith("texturepacks/"),
                            "External resource packs must remain local: " + name);
                    if (name.startsWith("native/")) require(allowedNative.contains(name), "Uninventoried JAR native payload: " + name);
                    require(!name.endsWith(".zip") || allowedNative.contains(name), "External archive in mod JAR: " + name);
                }
            }
            try (var stream = Files.walk(root.resolve("src/main/resources/assets"))) {
                for (Path source : stream.filter(Files::isRegularFile).toList()) {
                    Path target = top.resolve("assets/" + root.resolve("src/main/resources/assets")
                            .relativize(source).toString().replace('\\', '/'));
                    require(Files.isRegularFile(target) && hash(target).equals(hash(source)), "Missing/changed renderer asset: " + source);
                }
            }
            require(!Files.exists(top.resolve("README.md")), "Repository notes must not be packaged");
        } finally {
            try (var stream = Files.list(temp)) { for (Path p : stream.toList()) Files.delete(p); }
            Files.delete(temp);
        }
    }
}
