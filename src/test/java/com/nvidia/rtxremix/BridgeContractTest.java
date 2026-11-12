/* SPDX-License-Identifier: Apache-2.0 */
package com.nvidia.rtxremix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Isolate JNI symbol binding in a fresh JVM, because native libraries cannot be safely unloaded. */
@EnabledIfEnvironmentVariable(named = "RTX_REMIX_STALE_BRIDGE_TEST_LIBRARY", matches = ".+")
class BridgeContractTest {
    @Test void oldBridgeIsRejectedBeforeRuntimeLoadAndCannotBeReplacedInProcess(@TempDir Path temp) throws Exception {
        String bridgeName = System.getProperty("os.name").toLowerCase().contains("linux") ? "remixbridge.so" : "remixbridge.dll";
        Path stale = Files.createDirectories(temp.resolve("old"));
        Path matching = Files.createDirectories(temp.resolve("matching"));
        Files.copy(Path.of(System.getenv("RTX_REMIX_STALE_BRIDGE_TEST_LIBRARY")), stale.resolve(bridgeName));
        Files.copy(Path.of(System.getenv("RTX_REMIX_BRIDGE_TEST_LIBRARY")), matching.resolve(bridgeName));
        Path oldRuntime = Files.createFile(stale.resolve("runtime-not-loaded.bin"));
        Path newRuntime = Files.createFile(matching.resolve("runtime-not-loaded.bin"));
        String classes = Path.of(BridgeContractTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator + Path.of(RemixAPI.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path log = temp.resolve("probe.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", classes, Probe.class.getName(), oldRuntime.toString(), newRuntime.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "JNI contract probe must terminate");
            assertEquals(0, child.exitValue(), () -> read(log));
            assertTrue(read(log).contains("STALE_BRIDGE_REJECTED_AND_LATCHED"));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(); }
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (Exception error) { return error.toString(); }
    }

    public static final class Probe {
        public static void main(String[] args) {
            for (int i = 0; i < args.length; i++) {
                try {
                    RemixAPI.load(args[i]);
                    throw new AssertionError("A stale bridge must never initialize a runtime");
                } catch (RemixException rejected) {
                    if (rejected.getErrorCode() != ErrorCode.INCOMPATIBLE_VERSION) throw rejected;
                    if (i == 0 && !rejected.getMessage().contains("no compatible load-time contract")) throw rejected;
                    if (i == 1 && !rejected.getMessage().contains("Restart")) throw rejected;
                }
            }
            System.out.println("STALE_BRIDGE_REJECTED_AND_LATCHED");
        }
    }
}
