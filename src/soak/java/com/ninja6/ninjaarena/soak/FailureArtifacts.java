package com.ninja6.ninjaarena.soak;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional local failure evidence, captured after the observing stack has returned. */
final class FailureArtifacts {
    private FailureArtifacts() {
    }

    static void shutdown(JavaPlugin owner, Path output) {
        if (!Boolean.getBoolean("soak.heapDumpOnFailure")) {
            Bukkit.shutdown();
            return;
        }
        // This callback must not resolve a weak world or close over a resolved referent.
        Bukkit.getScheduler().runTask(owner, () -> {
            try {
                Path dump = output.resolve("failed-live-heap.hprof").toAbsolutePath();
                long required = Runtime.getRuntime().maxMemory() * 2 + 128 * 1024 * 1024L;
                if (Files.exists(dump) || Files.getFileStore(output).getUsableSpace() < required) {
                    throw new IllegalStateException("Heap dump already exists or insufficient bounded local disk budget");
                }
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
                Files.writeString(output.resolve("heap-dump-complete"), dump.getFileName().toString());
            } catch (Exception error) {
                owner.getLogger().log(java.util.logging.Level.SEVERE, "Could not retain failure heap", error);
                try {
                    Files.writeString(output.resolve("heap-dump-error.txt"), error.toString());
                } catch (Exception writeError) {
                    owner.getLogger().severe(writeError.toString());
                }
            } finally {
                Bukkit.shutdown();
            }
        });
    }
}
