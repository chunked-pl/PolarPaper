package live.minehub.polarpaper.core.userdata;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

public interface EntitySerializer {
    Logger LOGGER = LoggerFactory.getLogger(EntitySerializer.class);

    /**
     * How long {@link #saveOnEntityThread} waits before giving up on an entity.
     */
    int SAVE_TIMEOUT_SECONDS = 10;

    net.minecraft.world.entity.Entity compoundToEntity(World world, CompoundTag compound);
    CompletableFuture<byte @Nullable []> entityToBytes(Entity entity, Plugin plugin);

    /**
     * Runs a serialisation attempt on the entity's own thread and waits for the result, for the cases where
     * saving an entity fires events that must not run asynchronously.
     * <p>
     * The wait is bounded. A removed entity or a shutting down server may never run the scheduled task, and
     * waiting forever there would hang the save along with anything blocking on it, such as the shutdown save.
     *
     * @return whether the entity was saved
     */
    static boolean saveOnEntityThread(@NotNull Entity entity, @NotNull Plugin plugin, @NotNull BooleanSupplier save) {
        CompletableFuture<Boolean> saved = new CompletableFuture<>();
        ScheduledTask task = entity.getScheduler().run(plugin, _ -> {
            try {
                saved.complete(save.getAsBoolean());
            } catch (Exception e) {
                saved.completeExceptionally(e);
            }
        }, () -> saved.complete(false)); // retired: the entity was removed before the task ran

        if (task == null) return false; // the entity was already removed, so there is nothing left to save

        try {
            return saved.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.error("Failed to serialize entity {}", entity.getUniqueId(), e);
            return false;
        }
    }
}
