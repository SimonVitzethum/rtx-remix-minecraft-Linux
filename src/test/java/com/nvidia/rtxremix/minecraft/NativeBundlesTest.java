/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NativeBundlesTest {
    @TempDir Path temp;
    private int counter;

    private record Fixture(Path archive, NativeBundles.Bundle bundle) {}

    @Test
    void verifiesAndInstallsEveryMachineTypeWithoutLoadingNativeCode() throws Exception {
        for (String platform : NativeBundles.PLATFORMS) {
            Fixture f = fixture(platform, Map.of());
            Path installed = temp.resolve(platform);
            NativeBundles.validate(f.archive, f.bundle, installed);
            assertTrue(NativeBundles.installedMatches(installed, f.bundle));
            Files.writeString(installed.resolve(f.bundle.bridgeFile()), "truncated");
            assertFalse(NativeBundles.installedMatches(installed, f.bundle));
        }
    }

    @Test
    void rejectsTruncationAndWrongArchiveHash() throws Exception {
        Fixture f = fixture("linux64", Map.of());
        Files.write(f.archive, Arrays.copyOf(Files.readAllBytes(f.archive), 40));
        Fixture truncated = f;
        assertThrows(IOException.class, () -> NativeBundles.validate(truncated.archive, truncated.bundle, null));
        f = fixture("linux64", Map.of());
        byte[] bad = Files.readAllBytes(f.archive);
        bad[40] ^= 1;
        Files.write(f.archive, bad);
        Fixture corrupt = f;
        assertThrows(IOException.class, () -> NativeBundles.validate(corrupt.archive, corrupt.bundle, null));
    }

    @Test
    void rejectsTraversalAbsoluteWindowsPathsAndCaseDuplicateEntries() throws Exception {
        for (String name : new String[]{"../outside", "/absolute", "C:/windows", "a\\b", "NUL.dll", "a/./b", "dir./x"}) {
            Fixture f = fixture("linux64", Map.of(name, "bad".getBytes(StandardCharsets.UTF_8)));
            assertThrows(IOException.class, () -> NativeBundles.validate(f.archive, f.bundle, null), name);
        }
        Fixture f = fixture("linux64", Map.of("Foo.txt", new byte[]{1}, "foo.txt", new byte[]{2}));
        assertThrows(IOException.class, () -> NativeBundles.validate(f.archive, f.bundle, null));
        assertFalse(Files.exists(temp.resolve("outside")));
    }

    @Test
    void rejectsSymlinkAndUnsafePermissionsFromTheCentralDirectory() throws Exception {
        Fixture f = fixture("linux64", Map.of());
        for (int mode : new int[]{0120777, 0104777, 0100666}) {
            byte[] bytes = Files.readAllBytes(f.archive);
            int central = find(bytes, new byte[]{0x50, 0x4b, 1, 2});
            bytes[central + 5] = 3;
            put32(bytes, central + 38, (long) mode << 16);
            Path modified = temp.resolve("mode-" + mode + ".zip");
            Files.write(modified, bytes);
            var bundle = repin(f.bundle, modified, f.bundle.url());
            assertThrows(IOException.class, () -> NativeBundles.validate(modified, bundle, null));
        }
    }

    @Test
    void rejectsDuplicateEntriesEvenWhenTheOuterHashIsPinned() throws Exception {
        Fixture f = fixture("linux64", Map.of("same1", new byte[]{1}, "same2", new byte[]{2}));
        byte[] bytes = Files.readAllBytes(f.archive);
        // Mutate both local and central names, preserving the ZIP directory sizes.
        byte[] old = "same2".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= bytes.length - old.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + old.length), old)) bytes[i + 4] = '1';
        }
        Files.write(f.archive, bytes);
        var bundle = repin(f.bundle, f.archive, f.bundle.url());
        assertThrows(IOException.class, () -> NativeBundles.validate(f.archive, bundle, null));
    }

    @Test
    void rejectsZipBombAndWrongMachineDespiteAValidOuterHash() throws Exception {
        Fixture bomb = fixture("linux64", Map.of("zeros.bin", new byte[2 * 1024 * 1024]));
        assertThrows(IOException.class, () -> NativeBundles.validate(bomb.archive, bomb.bundle, null));
        Fixture wrong = fixture("linux64", Map.of("foreign.dll", binary("arm64")));
        assertThrows(IOException.class, () -> NativeBundles.validate(wrong.archive, wrong.bundle, null));
    }

    @Test
    void aBundledOnlyArchiveNeedsNoInventedDownloadUrl() throws Exception {
        Fixture f = fixture("linux64", Map.of());
        var bundledOnly = repin(f.bundle, f.archive, null);
        assertEquals(f.archive, NativeBundles.acquire(bundledOnly, f.archive, temp.resolve("offline-cache")));
        var manifest = new java.util.Properties();
        manifest.putAll(NativeBundles.sourceContract());
        manifest.setProperty("schema", "1");
        manifest.setProperty("freeze", "final");
        manifest.setProperty("platforms", String.join(",", NativeBundles.PLATFORMS));
        manifest.setProperty("runtimeSource", "1".repeat(40));
        manifest.setProperty("bridgeSource", "2".repeat(40));
        for (String platform : NativeBundles.PLATFORMS) {
            manifest.setProperty(platform + ".status", platform.equals("linux64") ? "available" : "unavailable");
            manifest.setProperty(platform + ".reason", "Not yet published");
        }
        manifest.setProperty("linux64.sha256", bundledOnly.sha256());
        manifest.setProperty("linux64.bytes", Long.toString(bundledOnly.bytes()));
        manifest.setProperty("linux64.unpackedBytes", Long.toString(bundledOnly.unpackedBytes()));
        for (String field : java.util.List.of("minimumOs", "minimumVulkan", "minimumDriver")) {
            manifest.setProperty("linux64." + field, "fixture");
        }
        Path path = temp.resolve("bundled-only.properties");
        try (var out = Files.newOutputStream(path)) { manifest.store(out, "fixture"); }
        assertDoesNotThrow(() -> NativeBundles.verifyBootstrapManifest(path));
        assertNull(NativeBundles.manifest(path, java.util.List.of("linux64")).get("linux64").url());
        assertThrows(IOException.class, () -> NativeBundles.manifest(path, NativeBundles.PLATFORMS));

        IOException unavailable = assertThrows(IOException.class, () -> NativeBundles.acquire(bundledOnly,
                temp.resolve("not-bundled.zip"), temp.resolve("uncached")));
        assertTrue(unavailable.getMessage().contains("no published HTTPS download for linux64"));
        try (var files = Files.list(temp.resolve("uncached"))) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".part")));
        }
    }

    @Test
    void cacheWorksOfflineAndAnActualTransferFailureLeavesNoPartialArchive() throws Exception {
        Fixture f = fixture("linux64", Map.of());
        byte[] archive = Files.readAllBytes(f.archive);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bundle.zip", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive);
            exchange.close();
        });
        server.createContext("/truncated.zip", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(Arrays.copyOf(archive, 40));
            exchange.close();
        });
        server.createContext("/corrupt.zip", exchange -> {
            exchange.sendResponseHeaders(200, archive.length);
            byte[] corrupt = archive.clone();
            corrupt[40] ^= 1;
            exchange.getResponseBody().write(corrupt);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var bundle = repin(f.bundle, f.archive, base.resolve("/bundle.zip"));
        Path cache = temp.resolve("cache");
        Path missing = temp.resolve("missing.zip");
        Path cached;
        try {
            cached = NativeBundles.acquire(bundle, missing, cache, true);
            assertEquals(1, requests.get());
            assertEquals(bundle.sha256(), NativeBundles.hash(cached));
            for (String failure : new String[]{"truncated", "corrupt"}) {
                Path failedCache = temp.resolve(failure);
                var failed = repin(f.bundle, f.archive, base.resolve("/" + failure + ".zip"));
                assertThrows(IOException.class, () -> NativeBundles.acquire(failed, missing, failedCache, true));
                try (var files = Files.list(failedCache)) {
                    assertTrue(files.noneMatch(p -> p.toString().endsWith(".zip") || p.toString().endsWith(".part")));
                }
            }
        } finally { server.stop(0); }
        assertEquals(cached, NativeBundles.acquire(bundle, missing, cache, true));
        Files.writeString(cached, "bad cached content");
        assertThrows(IOException.class, () -> NativeBundles.acquire(bundle, missing, cache, true));
        assertFalse(Files.exists(cached));
    }

    @Test
    void rejectsAnUnsafeCacheAndProductionHttp() throws Exception {
        Fixture f = fixture("linux64", Map.of());
        Path real = Files.createDirectory(temp.resolve("real"));
        Path linked = temp.resolve("linked");
        try { Files.createSymbolicLink(linked, real); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            // Windows runners without symlink privileges still exercise HTTPS enforcement.
            linked = real.resolve("child");
            Files.writeString(linked, "not a directory");
        }
        Path cache = linked;
        assertThrows(IOException.class, () -> NativeBundles.acquire(f.bundle, temp.resolve("missing"), cache));
        var http = repin(f.bundle, f.archive, URI.create("http://127.0.0.1:1/bundle.zip"));
        assertThrows(IOException.class, () -> NativeBundles.acquire(http, temp.resolve("missing"), temp.resolve("http")));
    }

    @Test
    void platformAndApiChangesInvalidateTheInstalledIdentity() throws Exception {
        Fixture f = fixture("linux64", Map.of());
        Path installed = temp.resolve("installed");
        NativeBundles.validate(f.archive, f.bundle, installed);
        NativeBundles.Bundle different = fixture("linuxarm64", Map.of()).bundle;
        assertNotEquals(f.bundle.identity(), different.identity());
        assertFalse(NativeBundles.installedMatches(installed, different));
        Files.writeString(installed.resolve("bundle.properties"), "api=0.16.2\n");
        assertFalse(NativeBundles.installedMatches(installed, f.bundle));
    }

    @Test
    void corruptBundledUpdatePreservesTheLiveSdkSettingsAndMarker() throws Exception {
        Fixture f = fixture("linux64", Map.of());
        Path nativeRoot = Files.createDirectory(temp.resolve("native"));
        Path bundles = Files.createDirectory(nativeRoot.resolve("bundles"));
        Path archive = Files.copy(f.archive, bundles.resolve("linux64.zip"));
        Files.writeString(nativeRoot.resolve("rtx.conf"), "shipped defaults");
        Path game = Files.createDirectory(temp.resolve("game"));
        Path settings = game.resolve("rtx-remix/mods/minecraft-logic/confs/custom.conf");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "author settings");
        ShippedPayloadHistory history = ShippedPayloadHistory.parse(java.util.List.of());
        NativeExtractor.extractIfNeeded(nativeRoot, game, "v1", "remix-sdk-linux64", history, Runnable::run, f.bundle);
        String marker = Files.readString(game.resolve(".rtx-remix-version"));
        String installed = NativeBundles.hash(game.resolve("remix-sdk/libremixapi.so"));
        Files.writeString(archive, "corrupt replacement");
        assertThrows(IOException.class, () -> NativeExtractor.extractIfNeeded(
                nativeRoot, game, "v2", "remix-sdk-linux64", history, Runnable::run, f.bundle));
        assertEquals(marker, Files.readString(game.resolve(".rtx-remix-version")));
        assertEquals(installed, NativeBundles.hash(game.resolve("remix-sdk/libremixapi.so")));
        assertEquals("author settings", Files.readString(settings));
        assertFalse(Files.exists(game.resolve("bundles")));
    }

    @Test
    void apiAndPlatformMarkerChangesForceTheBundledInstallPath() throws Exception {
        Fixture f = fixture("linux64", Map.of());
        Path nativeRoot = Files.createDirectory(temp.resolve("native"));
        Path bundles = Files.createDirectory(nativeRoot.resolve("bundles"));
        Files.copy(f.archive, bundles.resolve("linux64.zip"));
        Path game = Files.createDirectory(temp.resolve("game"));
        var history = ShippedPayloadHistory.parse(java.util.List.of());
        NativeExtractor.extractIfNeeded(nativeRoot, game, "v1", "remix-sdk-linux64", history, Runnable::run, f.bundle);
        String expected = Files.readString(game.resolve(".rtx-remix-version"));
        for (String previous : new String[]{expected.replace(f.bundle.api(), "0.0.0"),
                expected.replace("linux64", "linuxarm64")}) {
            Files.writeString(game.resolve(".rtx-remix-version"), previous);
            Files.writeString(game.resolve("remix-sdk/libremixapi.so"), "wrong live payload");
            NativeExtractor.extractIfNeeded(nativeRoot, game, "v1", "remix-sdk-linux64", history, Runnable::run, f.bundle);
            assertEquals(expected, Files.readString(game.resolve(".rtx-remix-version")));
            assertTrue(NativeBundles.installedMatches(game.resolve("remix-sdk"), f.bundle));
        }
    }

    @Test
    void textureInventoryDetectsCorruptionAndMissingPayloads() throws Exception {
        Path textures = Files.createDirectory(temp.resolve("textures"));
        Path inventory = temp.resolve("textures.properties");
        StringBuilder entries = new StringBuilder();
        for (String name : java.util.List.of("overcast_thunderstorm_bc6u.dds", "water_normals_flipbook.dds")) {
            Path file = Files.writeString(textures.resolve(name), "fixture DDS");
            entries.append(name).append('=').append(Files.size(file)).append(':').append(NativeBundles.hash(file)).append('\n');
        }
        Files.writeString(inventory, entries.toString());
        NativeBundles.verifyTextures(textures, inventory);
        Files.writeString(textures.resolve("water_normals_flipbook.dds"), "corrupt");
        assertThrows(IOException.class, () -> NativeBundles.verifyTextures(textures, inventory));
        Files.delete(textures.resolve("water_normals_flipbook.dds"));
        assertThrows(IOException.class, () -> NativeBundles.verifyTextures(textures, inventory));
    }

    @Test
    void missingPlatformAndPendingFreezeAreReleaseBlockers() throws Exception {
        Path manifest = temp.resolve("manifest.properties");
        Files.writeString(manifest, "schema=1\napi=" + NativeBundles.sourceContract().getProperty("api") + "\nbridgeAbi=source-v1\ninterfacePointers="
                + NativeBundles.sourceContract().getProperty("interfacePointers") + "\nfreeze=pending\n"
                + "platforms=linux64,linuxarm64,win64,arm64\nlinux64.status=unavailable\nlinux64.reason=fixture unavailable\n");
        IOException error = assertThrows(IOException.class, () -> NativeBundles.manifest(manifest, NativeBundles.PLATFORMS));
        assertTrue(error.getMessage().contains("linux64: fixture unavailable"));
        assertTrue(error.getMessage().contains("linuxarm64:"));
        assertTrue(error.getMessage().contains("win64:"));
        assertTrue(error.getMessage().contains("arm64:"));
    }

    @Test
    void portableBootstrapAllowsExplicitlyUnavailablePlatformsButRuntimeAndReleaseDoNot() throws Exception {
        Path path = temp.resolve("portable.properties");
        StringBuilder manifest = new StringBuilder("schema=1\nfreeze=pending\nplatforms=linux64,linuxarm64,win64,arm64\n");
        for (String platform : NativeBundles.PLATFORMS) {
            manifest.append(platform).append(".status=unavailable\n");
            manifest.append(platform).append(".reason=Compatible package not published\n");
        }
        Files.writeString(path, manifest);
        assertDoesNotThrow(() -> NativeBundles.verifyBootstrapManifest(path));
        IOException startup = assertThrows(IOException.class, () -> NativeBundles.manifest(path, java.util.List.of("linux64")));
        assertTrue(startup.getMessage().contains("linux64: Compatible package not published"));
        assertFalse(startup.getMessage().contains("win64:"));
        assertThrows(IOException.class, () -> NativeBundles.manifest(path, NativeBundles.PLATFORMS));
        Files.writeString(path, manifest.toString().replace("arm64.status=unavailable", "arm64.status=unknown"));
        assertThrows(IOException.class, () -> NativeBundles.verifyBootstrapManifest(path));
    }

    @Test
    void aFrozenManifestCannotRelabelTheCompiledApiOrInterfaceExtent() throws Exception {
        var contract = NativeBundles.sourceContract();
        for (String changed : java.util.List.of("api", "interfacePointers", "headerSha256", "bridgeSourceSha256")) {
            var manifest = new java.util.Properties();
            manifest.putAll(contract);
            manifest.setProperty(changed, changed.equals("api") ? "0.0.0" : "0");
            manifest.setProperty("schema", "1");
            manifest.setProperty("freeze", "final");
            manifest.setProperty("platforms", String.join(",", NativeBundles.PLATFORMS));
            for (String arch : NativeBundles.PLATFORMS) manifest.setProperty(arch + ".status", "available");
            Path path = temp.resolve(changed + ".properties");
            try (var out = Files.newOutputStream(path)) { manifest.store(out, "fixture"); }
            IOException error = assertThrows(IOException.class, () -> NativeBundles.manifest(path, NativeBundles.PLATFORMS));
            assertTrue(error.getMessage().contains(changed));
            assertThrows(IOException.class, () -> NativeBundles.verifyBootstrapManifest(path));
        }
    }

    private Fixture fixture(String platform, Map<String, byte[]> extras) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        boolean linux = platform.startsWith("linux");
        files.put(linux ? "libremixapi.so" : "d3d9.dll", binary(platform));
        files.put(linux ? "remixbridge.so" : "remixbridge.dll", binary(platform));
        files.put("licenses/MIT.txt", "fixture license\n".getBytes(StandardCharsets.UTF_8));
        files.put("audit.json", "{\"fixture\":true}".getBytes(StandardCharsets.UTF_8));
        java.io.ByteArrayOutputStream descriptors = new java.io.ByteArrayOutputStream();
        NativeBundles.jniSignatures().store(descriptors, "JVM fixture descriptors");
        files.put("jni-signatures.properties", descriptors.toByteArray());
        files.putAll(extras);
        String metadata = "schema=1\nplatform=" + platform + "\napi=" + NativeBundles.sourceContract().getProperty("api") + "\nbridgeAbi=source-v1\ninterfacePointers="
                + NativeBundles.sourceContract().getProperty("interfacePointers") + "\n"
                + "runtimeSource=" + "1".repeat(40) + "\nbridgeSource=" + "2".repeat(40)
                + "\nheaderSha256=" + "3".repeat(64) + "\nbridgeSourceSha256=" + "4".repeat(64)
                + "\nminimumOs=fixture\nminimumVulkan=fixture\nminimumDriver=fixture\n";
        StringBuilder inventory = new StringBuilder("license.fixture=licenses/MIT.txt\n");
        for (String name : files.keySet()) {
            if (name.matches("(?i).*([.]so([.][0-9]+)*|[.]dll|[.]exe)$")) {
                inventory.append("license.").append(name).append("=licenses/MIT.txt\n");
            }
        }
        for (var e : files.entrySet()) {
            var hash = java.security.MessageDigest.getInstance("SHA-256").digest(e.getValue());
            inventory.append("file.").append(e.getKey().replace("\\", "\\\\")).append('=')
                    .append(e.getValue().length).append(':').append(java.util.HexFormat.of().formatHex(hash)).append('\n');
        }
        files.put("bundle.properties", metadata.getBytes(StandardCharsets.ISO_8859_1));
        files.put("inventory.properties", inventory.toString().getBytes(StandardCharsets.ISO_8859_1));
        Path archive = temp.resolve("fixture-" + counter++ + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        NativeBundles.Bundle b = new NativeBundles.Bundle(platform, NativeBundles.sourceContract().getProperty("api"), "source-v1", NativeBundles.sourceContract().getProperty("interfacePointers"),
                "1".repeat(40), "2".repeat(40), "3".repeat(64), "4".repeat(64),
                URI.create("https://example.invalid/v1/" + platform + ".zip"), NativeBundles.hash(archive),
                Files.size(archive), files.values().stream().mapToLong(a -> a.length).sum(), "fixture", "fixture", "fixture");
        return new Fixture(archive, b);
    }

    private NativeBundles.Bundle repin(NativeBundles.Bundle b, Path archive, URI url) throws IOException {
        return new NativeBundles.Bundle(b.platform(), b.api(), b.bridgeAbi(), b.interfacePointers(), b.runtimeSource(), b.bridgeSource(),
                b.headerSha256(), b.bridgeSourceSha256(), url, NativeBundles.hash(archive), Files.size(archive),
                b.unpackedBytes(), b.minimumOs(), b.minimumVulkan(), b.minimumDriver());
    }

    private static byte[] binary(String platform) {
        byte[] bytes = new byte[256];
        boolean arm = platform.equals("arm64") || platform.equals("linuxarm64");
        if (platform.startsWith("linux")) {
            bytes[0] = 127; bytes[1] = 'E'; bytes[2] = 'L'; bytes[3] = 'F'; bytes[4] = 2; bytes[5] = 1;
            bytes[18] = (byte) (arm ? 183 : 62);
        } else {
            bytes[0] = 'M'; bytes[1] = 'Z'; bytes[60] = 64; bytes[64] = 'P'; bytes[65] = 'E';
            bytes[68] = 0x64; bytes[69] = (byte) (arm ? 0xaa : 0x86);
            bytes[88] = 0x0b; bytes[89] = 2;
        }
        return bytes;
    }

    private static int find(byte[] bytes, byte[] needle) {
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + needle.length), needle)) return i;
        }
        throw new AssertionError("ZIP central directory missing");
    }
    private static void put32(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }
}
