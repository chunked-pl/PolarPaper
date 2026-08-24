package live.minehub.polarpaper.core.userdata;

import net.minecraft.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
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

    int SAVE_TIMEOUT_SECONDS = 10;

    net.minecraft.world.entity.Entity compoundToEntity(World world, CompoundTag compound);
    CompletableFuture<byte @Nullable []> entityToBytes(Entity entity, Plugin plugin);

    static boolean saveOnEntityThread(@NotNull Entity entity, @NotNull Plugin plugin, @NotNull BooleanSupplier save) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return save.getAsBoolean();
            } catch (Throwable throwable) {
                LOGGER.error("Failed to serialize entity {}", entity.getUniqueId(), throwable);
                return false;
            }
        }

        CompletableFuture<Boolean> saved = new CompletableFuture<>();
        BukkitTask task;
        try {
            task = Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    saved.complete(save.getAsBoolean());
                } catch (Throwable throwable) {
                    saved.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            LOGGER.error("Failed to schedule serialization of entity {}", entity.getUniqueId(), throwable);
            return false;
        }

        try {
            return saved.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            task.cancel();
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException e) {
            task.cancel();
            LOGGER.error("Timed out serializing entity {}", entity.getUniqueId(), e);
            return false;
        } catch (ExecutionException e) {
            LOGGER.error("Failed to serialize entity {}", entity.getUniqueId(), e);
            return false;
        }
    }
}
