package live.minehub.polarpaper.core.util;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class TaskFutures {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskFutures.class);

    private TaskFutures() {
    }

    public static <T> CompletableFuture<T> runAsync(Plugin plugin, Supplier<T> runnable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, _ -> complete(future, runnable));
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    public static <T> CompletableFuture<T> runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Supplier<T> runnable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> complete(future, runnable));
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    /**
     * On Paper, immediately runs on the main tick thread. Folia region threads are not the global region thread,
     * so Folia always schedules through the global scheduler.
     */
    public static <T> CompletableFuture<T> runSync(Plugin plugin, Supplier<T> runnable) {
        if (!FoliaUtil.isFolia() && TickThread.isTickThread()) {
            CompletableFuture<T> future = new CompletableFuture<>();
            complete(future, runnable);
            return future;
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> complete(future, runnable));
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    private static <T> void complete(CompletableFuture<T> future, Supplier<T> runnable) {
        try {
            future.complete(runnable.get());
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
            LOGGER.error("Task failed exceptionally: ", throwable);
        }
    }

}
