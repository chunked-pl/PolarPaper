package live.minehub.polarpaper.paper_26_1;

import com.mojang.logging.LogUtils;
import live.minehub.polarpaper.core.userdata.EntitySerializer;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class EntitySerializerImpl implements EntitySerializer {

    @Override
    public net.minecraft.world.entity.Entity compoundToEntity(World world, CompoundTag compound) {
        ProblemReporter.ScopedCollector problemReporter = new ProblemReporter.ScopedCollector(() -> "deserialiseEntity", LogUtils.getLogger());
        ValueInput tagValueInput = TagValueInput.create(problemReporter, ((CraftWorld) world).getHandle().registryAccess(), compound);

        return net.minecraft.world.entity.EntityType
                .create(tagValueInput, ((CraftWorld) world).getHandle(), EntitySpawnReason.LOAD)
                .orElse(null);
    }

    @Override
    public CompletableFuture<byte @Nullable []> entityToBytes(Entity entity, Plugin plugin) {
        CompletableFuture<byte @Nullable []> byteArrayFuture = new CompletableFuture<>();
        try {
            serializeEntity(entity, plugin, byteArrayFuture);
        } catch (Throwable throwable) {
            byteArrayFuture.completeExceptionally(throwable);
        }

        return byteArrayFuture;
    }

    private void serializeEntity(Entity entity, Plugin plugin, CompletableFuture<byte @Nullable []> byteArrayFuture) {
        if (entity.getType() == EntityType.PLAYER) {
            byteArrayFuture.complete(null);
            return;
        }
        if (!entity.isPersistent()) {
            byteArrayFuture.complete(null);
            return;
        }

        net.minecraft.world.entity.Entity nmsEntity = ((CraftEntity) entity).getHandle();
        ProblemReporter.ScopedCollector problemReporter = new ProblemReporter.ScopedCollector(() -> "serialiseEntity@" + entity.getUniqueId(), LogUtils.getLogger());
        TagValueOutput tagValueOutput = TagValueOutput.createWithContext(problemReporter, nmsEntity.registryAccess());

        boolean successful;
        if (Bukkit.isPrimaryThread()) {
            try {
                successful = nmsEntity.saveAsPassenger(tagValueOutput, true, false, false);
            } catch (Exception e) {
                LOGGER.error("Failed to serialize entity {}", entity.getUniqueId(), e);
                successful = false;
            }
        } else {
            successful = EntitySerializer.saveOnEntityThread(entity, plugin,
                    () -> nmsEntity.saveAsPassenger(tagValueOutput, true, false, false));
        }

        CompoundTag compound = tagValueOutput.buildResult();

        Optional<String> id = compound.getString("id");
        if (id.isEmpty() || id.get().isBlank() || !successful) {
            byteArrayFuture.complete(null);
            return;
        }
        compound.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutput = new DataOutputStream(outputStream);
        try {
            NbtIo.write(
                    compound,
                    dataOutput
            );
            outputStream.flush();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        byteArrayFuture.complete(outputStream.toByteArray());
    }
}
