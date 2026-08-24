package live.minehub.polarpaper.core.util;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import live.minehub.polarpaper.core.world.PolarChunkArchive;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public record WorldUsage(
        int chunks,
        int sections,
        int sectionsWithBlocks,
        long blockBytes,
        long biomeBytes,
        long lightBytes,
        long heightmapBytes,
        long overheadBytes,
        int blockEntities,
        int entities,
        int archivedChunks,
        long archivedBytes
) {

    private static final long NIBBLE_WITH_ARRAY_BYTES = 2123;
    private static final long EMPTY_NIBBLE_BYTES = 32;

    private static final long CHUNK_OVERHEAD_BYTES = 3072;
    private static final long EMPTY_SECTION_OVERHEAD_BYTES = 256;
    private static final long POPULATED_SECTION_OVERHEAD_BYTES = 1600;

    public long totalBytes() {
        return blockBytes + biomeBytes + lightBytes + heightmapBytes + overheadBytes + archivedBytes;
    }

    public boolean hasArchive() {
        return archivedChunks > 0;
    }

    public static @NotNull WorldUsage measure(@NotNull World world) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        ChunkHolderManager chunkHolderManager = level.moonrise$getChunkTaskScheduler().chunkHolderManager;

        int chunks = 0;
        int sections = 0;
        int sectionsWithBlocks = 0;
        long blockBytes = 0;
        long biomeBytes = 0;
        long lightBytes = 0;
        long heightmapBytes = 0;
        int blockEntities = 0;

        for (NewChunkHolder chunkHolder : chunkHolderManager.getChunkHolders()) {
            if (!(chunkHolder.getCurrentChunk() instanceof LevelChunk chunk)) continue;
            chunks++;

            for (LevelChunkSection section : chunk.getSections()) {
                sections++;
                if (!section.hasOnlyAir()) sectionsWithBlocks++;
                blockBytes += storageBytes(section.getStates());
                biomeBytes += storageBytes(asContainer(section));
            }

            lightBytes += nibbleBytes(chunk.starlight$getSkyNibbles())
                    + nibbleBytes(chunk.starlight$getBlockNibbles());

            for (Map.Entry<Heightmap.Types, Heightmap> heightmap : chunk.getHeightmaps()) {
                heightmapBytes += (long) heightmap.getValue().getRawData().length * Long.BYTES;
            }

            blockEntities += chunk.blockEntities.size();
        }

        long overheadBytes = (long) chunks * CHUNK_OVERHEAD_BYTES
                + (long) sectionsWithBlocks * POPULATED_SECTION_OVERHEAD_BYTES
                + (long) (sections - sectionsWithBlocks) * EMPTY_SECTION_OVERHEAD_BYTES;

        PolarGenerator generator = PolarGenerator.fromWorld(world);
        PolarChunkArchive archive = generator == null ? null : generator.getChunkArchive();

        return new WorldUsage(chunks, sections, sectionsWithBlocks, blockBytes, biomeBytes, lightBytes,
                heightmapBytes, overheadBytes, blockEntities, world.getEntityCount(),
                archive == null ? 0 : archive.size(),
                archive == null ? 0L : archive.retainedBytes());
    }

    @SuppressWarnings("unchecked")
    private static PalettedContainer<Holder<Biome>> asContainer(LevelChunkSection section) {
        return (PalettedContainer<Holder<Biome>>) section.getBiomes();
    }

    private static long storageBytes(PalettedContainer<?> container) {
        return (long) container.data.storage().getRaw().length * Long.BYTES;
    }

    private static long nibbleBytes(SWMRNibbleArray[] nibbles) {
        long bytes = 0;
        for (SWMRNibbleArray nibble : nibbles) {
            boolean hasArray = !nibble.isNullNibbleVisible() && !nibble.isUninitialisedVisible();
            bytes += hasArray ? NIBBLE_WITH_ARRAY_BYTES : EMPTY_NIBBLE_BYTES;
        }
        return bytes;
    }
}
