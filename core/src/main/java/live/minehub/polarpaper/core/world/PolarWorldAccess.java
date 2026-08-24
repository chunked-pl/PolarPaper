package live.minehub.polarpaper.core.world;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

@SuppressWarnings("unused")
public interface PolarWorldAccess {
    Plugin getPlugin();

    default void loadChunkData(@NotNull World world, @NotNull ChunkAccess chunk, byte @Nullable [] userData) {
    }

    default void loadChunkData(@NotNull World world, @NotNull ChunkAccess chunk, byte @Nullable [] userData,
                               @NotNull BlockSelector blockSelector) {
        loadChunkData(world, chunk, userData);
    }

    default void populateChunkData(@NotNull Chunk chunk, byte @Nullable [] userData) {
    }

    default void saveChunkData(@NotNull ChunkAccess chunk,
                               @NotNull Map<BlockPos, BlockEntity> blockEntities, @NotNull Entity[] entities,
                               @NotNull ByteBuf userData) {
    }

    @ApiStatus.Experimental
    default void loadHeightmaps(@NotNull ChunkGenerator.ChunkData chunkData, int[][] heightmaps) {
    }

    @ApiStatus.Experimental
    default void saveHeightmaps(@NotNull ChunkAccess chunk, int[][] heightmaps) {
    }

}
