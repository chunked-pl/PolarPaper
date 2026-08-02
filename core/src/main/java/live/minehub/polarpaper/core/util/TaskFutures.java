package live.minehub.polarpaper.core.util;

import org.bukkit.Bukkit;
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
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> complete(future, runnable));
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    public static <T> CompletableFuture<T> runSync(Plugin plugin, Supplier<T> runnable) {
        if (Bukkit.isPrimaryThread()) {
            CompletableFuture<T> future = new CompletableFuture<>();
            complete(future, runnable);
            return future;
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> complete(future, runnable));
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
