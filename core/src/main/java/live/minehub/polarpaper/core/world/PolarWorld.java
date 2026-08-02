package live.minehub.polarpaper.core.world;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.util.CoordConversion;
import net.minecraft.SharedConstants;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PolarWorld {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolarWorld.class);

    public static final int MAGIC_NUMBER = 0x506F6C72; // `Polr`
    public static final short LATEST_VERSION = 7;
    public static final short MIN_VERSION = 4;

//    static final short VERSION_UNIFIED_LIGHT = 1;
//    static final short VERSION_USERDATA_OPT_BLOCK_ENT_NBT = 2;
//    static final short VERSION_MINESTOM_NBT_READ_BREAK = 3;
    static final short VERSION_WORLD_USERDATA = 4;
    static final short VERSION_SHORT_GRASS = 5; // >:(
    static final short VERSION_DATA_CONVERTER = 6;
    static final short VERSION_IMPROVED_LIGHT = 7;
    static final short VERSION_DEPRECATED_ENTITIES = 8;

    public static CompressionType DEFAULT_COMPRESSION = CompressionType.ZSTD;
    public static int DEFAULT_COMPRESSION_LEVEL = 7;

    // Polar metadata
    private final short version;
    private final int dataVersion;
    private CompressionType compression;
    private int compressionLevel = DEFAULT_COMPRESSION_LEVEL;

    // World metadata
    private final byte minSection;
    private final byte maxSection;
    private byte @NotNull [] userData;

    // Chunk data
    private final Map<Long, PolarChunk> chunks = new ConcurrentHashMap<>();

    public PolarWorld(byte minSection, byte maxSection, Config config) {
        this(LATEST_VERSION, SharedConstants.getCurrentVersion().dataVersion().version(), DEFAULT_COMPRESSION, minSection, maxSection, new byte[0], List.of());
        this.compression = config.compression();
        this.compressionLevel = config.compressionLevel();
    }

    public PolarWorld(byte minSection, byte maxSection, Config config, @NotNull List<PolarChunk> chunks) {
        this(LATEST_VERSION, SharedConstants.getCurrentVersion().dataVersion().version(), DEFAULT_COMPRESSION, minSection, maxSection, new byte[0], chunks);
        this.compression = config.compression();
        this.compressionLevel = config.compressionLevel();
    }

    public PolarWorld(
            short version,
            int dataVersion,
            @NotNull CompressionType compression,
            byte minSection, byte maxSection,
            byte @NotNull [] userData,
            @NotNull List<PolarChunk> chunks
    ) {
        this.version = version;
        this.dataVersion = dataVersion;
        this.compression = compression;

        this.minSection = minSection;
        this.maxSection = maxSection;
        this.userData = userData;

        for (var chunk : chunks) {
            var index = CoordConversion.chunkIndex(chunk.x(), chunk.z());
            this.chunks.put(index, chunk);
        }
    }

    public enum CompressionType {
        NONE,
        ZSTD;

        private static final CompressionType[] VALUES = values();

        public static @Nullable CompressionType fromId(int id) {
            if (id < 0 || id >= VALUES.length) return null;
            return VALUES[id];
        }
    }

    public short version() {
        return version;
    }

    public int dataVersion() {
        return dataVersion;
    }

    public @NotNull CompressionType compression() {
        return compression;
    }

    public void compression(@NotNull CompressionType compression) {
        this.compression = compression;
    }

    public int compressionLevel() {
        return compressionLevel;
    }

    public void compressionLevel(int compressionLevel) {
        this.compressionLevel = compressionLevel;
    }

    public byte minSection() {
        return minSection;
    }

    public byte maxSection() {
        return maxSection;
    }

    public byte @NotNull [] userData() {
        return userData;
    }

    public void userData(byte @NotNull [] userData) {
        this.userData = userData;
    }

    public boolean hasChunkAt(int x, int z) {
        return chunks.containsKey(CoordConversion.chunkIndex(x, z));
    }

    public @Nullable PolarChunk chunkAt(int x, int z) {
        return chunks.getOrDefault(CoordConversion.chunkIndex(x, z), null);
    }

    public void removeChunkAt(int x, int z) {
        chunks.remove(CoordConversion.chunkIndex(x, z));
    }

    public void updateChunkAt(int x, int z, @NotNull PolarChunk chunk) {
        chunks.put(CoordConversion.chunkIndex(x, z), chunk);
    }

    public int numChunks() {
        return chunks.size();
    }

    public @NotNull Collection<PolarChunk> chunks() {
        return chunks.values();
    }

    public @NotNull List<PolarChunk> nonEmptyChunks() {
        List<PolarChunk> nonEmptyChunks = new ArrayList<>(chunks.size());
        for (PolarChunk chunk : chunks()) {
            if (chunk.isEmpty()) continue;
            nonEmptyChunks.add(chunk);
        }
        return nonEmptyChunks;
    }

    /**
     * Creates a new PolarWorld by converting chunks from the supplied bukkit world
     * <p>
     * Includes chunks already in this PolarWorld
     *
     * @param world The bukkit world to retrieve the updated chunks from
     */
    public CompletableFuture<PolarWorld> updateChunks(World world, PolarWorldAccess polarWorldAccess, boolean loadChunks) {
        return updateChunks(world, polarWorldAccess, BlockSelector.ALL, loadChunks);
    }

    /**
     * Creates a new PolarWorld by converting chunks from the supplied bukkit world
     * <p>
     * Includes chunks already in this PolarWorld
     *
     * @param world The bukkit world to retrieve the updated chunks from
     * @see BlockSelector#ALL
     */
    public CompletableFuture<PolarWorld> updateChunks(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, boolean loadChunks) {
        return convert(world, polarWorldAccess, blockSelector, Config.Builder.defaults().build(), this.nonEmptyChunks(), loadChunks);
    }

    /**
     * Creates a new PolarWorld by converting chunks from the supplied bukkit world
     * <p>
     * Includes chunks already in this PolarWorld
     *
     * @param world The bukkit world to retrieve the updated chunks from
     * @see BlockSelector#ALL
     */
    public CompletableFuture<PolarWorld> updateChunks(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, Config config, boolean loadChunks) {
        return convert(world, polarWorldAccess, blockSelector, config, this.nonEmptyChunks(), loadChunks);
    }

    /**
     * Creates a new PolarWorld by converting chunks from the supplied bukkit world
     *
     * @param world The bukkit world to retrieve the updated chunks from
     * @param polarWorldAccess Describes how userdata should be handled (default PolarWorldAccess.POLAR_PAPER_FEATURES)
     * @param blockSelector Used to filter which blocks should be updated (essentially a crop)
     * @see BlockSelector#ALL
     */
    public static CompletableFuture<PolarWorld> convert(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, boolean loadChunks) {
        return convert(world, polarWorldAccess, blockSelector, Config.Builder.defaults().build(), loadChunks);
    }

    /**
     * Creates a new PolarWorld by converting chunks from the supplied bukkit world
     *
     * @param world The bukkit world to retrieve the updated chunks from
     * @param polarWorldAccess Describes how userdata should be handled (default PolarWorldAccess.POLAR_PAPER_FEATURES)
     * @param blockSelector Used to filter which blocks should be updated (essentially a crop)
     * @param config Custom config for the polar world
     * @see BlockSelector#ALL
     */
    public static CompletableFuture<PolarWorld> convert(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, Config config, boolean loadChunks) {
        return convert(world, polarWorldAccess, blockSelector, config, List.of(),loadChunks);
    }

    /**
     * Creates a new PolarWorld by converting chunks from the supplied bukkit world
     *
     * @param world The bukkit world to retrieve the updated chunks from
     * @param polarWorldAccess Describes how userdata should be handled (default PolarWorldAccess.POLAR_PAPER_FEATURES)
     * @param blockSelector Used to filter which blocks should be updated (essentially a crop)
     * @param config Custom config for the polar world
     * @param includedChunks PolarChunks to add to the world
     * @param loadChunks Whether to load chunks
     * @see BlockSelector#ALL
     */
    public static CompletableFuture<PolarWorld> convert(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, Config config, Collection<PolarChunk> includedChunks, boolean loadChunks) {
        // TODO: consider offsets
        // Chunks loaded here do not need releasing: scheduleChunkLoad(addTicket = true) drops its own ticket
        // once the callback has run, so they unload normally afterwards.

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        ChunkHolderManager chunkHolderManager = serverLevel.moonrise$getChunkTaskScheduler().chunkHolderManager;

        // The three sources overlap heavily, so they are collected into a set to avoid converting a chunk twice
        Set<Long> chunkIndexes = new LinkedHashSet<>();
        for (PolarChunk chunk : includedChunks) {
            if (!blockSelector.testChunk(chunk.x(), chunk.z())) continue;
            chunkIndexes.add(CoordConversion.chunkIndex(chunk.x(), chunk.z()));
        }
        blockSelector.forEachChunk(chunkPos -> chunkIndexes.add(CoordConversion.chunkIndex(chunkPos.x, chunkPos.y)));
        for (NewChunkHolder chunkHolder : chunkHolderManager.getChunkHolders()) {
            if (!blockSelector.testChunk(chunkHolder.chunkX, chunkHolder.chunkZ)) continue;
            chunkIndexes.add(CoordConversion.chunkIndex(chunkHolder.chunkX, chunkHolder.chunkZ));
        }

        List<CompletableFuture<@Nullable PolarChunk>> futures = new ArrayList<>(chunkIndexes.size());
        for (long chunkIndex : chunkIndexes) {
            int chunkX = CoordConversion.chunkX(chunkIndex);
            int chunkZ = CoordConversion.chunkZ(chunkIndex);
            // One unreadable chunk should not fail the whole world, so it is dropped after being reported
            futures.add(PolarChunk.convert(world, chunkX, chunkZ, polarWorldAccess, blockSelector, config.saveLight(), loadChunks)
                    .exceptionally(e -> {
                        LOGGER.error("Failed to convert chunk at {} {} in {}", chunkX, chunkZ, world.getKey(), e);
                        return null;
                    }));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(_ -> {
            List<PolarChunk> chunks = new ArrayList<>(futures.size());
            for (CompletableFuture<PolarChunk> future : futures) {
                PolarChunk polarChunk = future.join();
                if (polarChunk == null) continue;
                chunks.add(polarChunk);
            }

            int minHeight = world.getMinHeight();
            int maxHeight = world.getMaxHeight() - 1;
            return new PolarWorld(
                    (byte) CoordConversion.sectionIndex(minHeight),
                    (byte) CoordConversion.sectionIndex(maxHeight),
                    config,
                    chunks
            );
        });
    }
}
