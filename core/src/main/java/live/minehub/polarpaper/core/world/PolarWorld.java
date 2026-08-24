package live.minehub.polarpaper.core.world;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.util.CoordConversion;
import net.minecraft.SharedConstants;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PolarWorld {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolarWorld.class);

    public static final int MAGIC_NUMBER = 0x506F6C72;
    public static final short LATEST_VERSION = 7;
    public static final short MIN_VERSION = 4;

    static final short VERSION_WORLD_USERDATA = 4;
    static final short VERSION_SHORT_GRASS = 5;
    static final short VERSION_DATA_CONVERTER = 6;
    static final short VERSION_IMPROVED_LIGHT = 7;
    static final short VERSION_DEPRECATED_ENTITIES = 8;

    public static CompressionType DEFAULT_COMPRESSION = CompressionType.ZSTD;
    public static int DEFAULT_COMPRESSION_LEVEL = 3;

    private static final int CHUNK_CONVERSION_TIMEOUT_MINUTES = 10;
    private static final int AMORTIZE_SNAPSHOT_THRESHOLD = 64;
    private static final long SNAPSHOT_BATCH_BUDGET_NANOS = 8_000_000L;

    private final short version;
    private final int dataVersion;
    private CompressionType compression;
    private int compressionLevel = DEFAULT_COMPRESSION_LEVEL;

    private final byte minSection;
    private final byte maxSection;
    private byte @NotNull [] userData;

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

    public CompletableFuture<PolarWorld> updateChunks(World world, PolarWorldAccess polarWorldAccess, boolean loadChunks) {
        return updateChunks(world, polarWorldAccess, BlockSelector.ALL, loadChunks);
    }

    public CompletableFuture<PolarWorld> updateChunks(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, boolean loadChunks) {
        return convert(world, polarWorldAccess, blockSelector, Config.Builder.defaults().build(), this.nonEmptyChunks(), loadChunks);
    }

    public CompletableFuture<PolarWorld> updateChunks(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, Config config, boolean loadChunks) {
        return convert(world, polarWorldAccess, blockSelector, config, this.nonEmptyChunks(), loadChunks);
    }

    public static CompletableFuture<PolarWorld> convert(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, boolean loadChunks) {
        return convert(world, polarWorldAccess, blockSelector, Config.Builder.defaults().build(), loadChunks);
    }

    public static CompletableFuture<PolarWorld> convert(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, Config config, boolean loadChunks) {
        return convert(world, polarWorldAccess, blockSelector, config, List.of(),loadChunks);
    }

    public static CompletableFuture<PolarWorld> convert(World world, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, Config config, Collection<PolarChunk> includedChunks, boolean loadChunks) {

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        ChunkHolderManager chunkHolderManager = serverLevel.moonrise$getChunkTaskScheduler().chunkHolderManager;

        Set<Long> chunkIndexes = new LinkedHashSet<>();
        for (PolarChunk chunk : includedChunks) {
            if (!blockSelector.testChunk(chunk.x(), chunk.z())) continue;
            chunkIndexes.add(CoordConversion.chunkIndex(chunk.x(), chunk.z()));
        }

        if (loadChunks) {
            blockSelector.forEachChunk(chunkPos -> chunkIndexes.add(CoordConversion.chunkIndex(chunkPos.x, chunkPos.y)));
        }
        for (NewChunkHolder chunkHolder : chunkHolderManager.getChunkHolders()) {
            if (!blockSelector.testChunk(chunkHolder.chunkX, chunkHolder.chunkZ)) continue;
            chunkIndexes.add(CoordConversion.chunkIndex(chunkHolder.chunkX, chunkHolder.chunkZ));
        }

        List<CompletableFuture<@Nullable PolarChunk>> futures = new ArrayList<>(chunkIndexes.size());

        if (chunkIndexes.size() <= AMORTIZE_SNAPSHOT_THRESHOLD) {
            for (long chunkIndex : chunkIndexes) {
                futures.add(convertLogged(world, chunkIndex, polarWorldAccess, blockSelector, config, loadChunks));
            }
            return awaitChunks(world, futures).thenApply(_ -> buildResult(world, config, futures));
        }

        CompletableFuture<Void> snapshotsScheduled = amortizeSnapshots(
                world, chunkIndexes, futures, polarWorldAccess, blockSelector, config, loadChunks);
        return snapshotsScheduled.thenCompose(_ -> awaitChunks(world, futures))
                .thenApply(_ -> buildResult(world, config, futures));
    }

    private static CompletableFuture<@Nullable PolarChunk> convertLogged(
            World world, long chunkIndex, PolarWorldAccess polarWorldAccess,
            BlockSelector blockSelector, Config config, boolean loadChunks) {
        int chunkX = CoordConversion.chunkX(chunkIndex);
        int chunkZ = CoordConversion.chunkZ(chunkIndex);
        return PolarChunk.convert(world, chunkX, chunkZ, polarWorldAccess, blockSelector, config.saveLight(), loadChunks)
                .whenComplete((_, e) -> {
                    if (e != null) LOGGER.error("Failed to convert chunk at {} {} in {}", chunkX, chunkZ, world.getKey(), e);
                });
    }

    private static CompletableFuture<Void> amortizeSnapshots(
            World world, Set<Long> chunkIndexes,
            List<CompletableFuture<@Nullable PolarChunk>> futures,
            PolarWorldAccess polarWorldAccess, BlockSelector blockSelector,
            Config config, boolean loadChunks) {
        Iterator<Long> remaining = chunkIndexes.iterator();
        CompletableFuture<Void> allScheduled = new CompletableFuture<>();
        org.bukkit.scheduler.BukkitTask[] holder = new org.bukkit.scheduler.BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(polarWorldAccess.getPlugin(), new Runnable() {
            @Override
            public void run() {
                if (!polarWorldAccess.getPlugin().isEnabled()) {
                    holder[0].cancel();
                    allScheduled.completeExceptionally(
                            new IllegalStateException("Plugin disabled while the autosave snapshot was being scheduled"));
                    return;
                }

                long deadline = System.nanoTime() + SNAPSHOT_BATCH_BUDGET_NANOS;
                try {
                    while (System.nanoTime() < deadline) {
                        if (!remaining.hasNext()) {
                            holder[0].cancel();
                            allScheduled.complete(null);
                            return;
                        }
                        futures.add(convertLogged(world, remaining.next(), polarWorldAccess, blockSelector, config, loadChunks));
                    }
                } catch (Throwable throwable) {
                    holder[0].cancel();
                    LOGGER.error("Could not schedule chunk snapshots for {}", world.getKey(), throwable);
                    allScheduled.completeExceptionally(throwable);
                }
            }
        }, 0L, 1L);

        return allScheduled;
    }

    private static PolarWorld buildResult(World world, Config config,
                                          List<CompletableFuture<@Nullable PolarChunk>> futures) {
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
    }

    private static CompletableFuture<Void> awaitChunks(World world, List<CompletableFuture<@Nullable PolarChunk>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(CHUNK_CONVERSION_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .whenComplete((_, e) -> {
                    if (!(unwrap(e) instanceof TimeoutException)) return;
                    LOGGER.error("Timed out after {} minutes converting {} of {}'s chunks. The world has not been saved and its previous file is untouched",
                            CHUNK_CONVERSION_TIMEOUT_MINUTES, futures.size(), world.getKey());
                });
    }

    private static @Nullable Throwable unwrap(@Nullable Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) return throwable.getCause();
        return throwable;
    }

    public static PolarWorld convertSynchronously(World world, PolarWorldAccess polarWorldAccess,
                                                   BlockSelector blockSelector, Config config,
                                                   Collection<PolarChunk> includedChunks) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Synchronous world conversion must run on Paper's main thread");
        }

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        ChunkHolderManager chunkHolderManager = serverLevel.moonrise$getChunkTaskScheduler().chunkHolderManager;

        Map<Long, PolarChunk> includedByIndex = new java.util.LinkedHashMap<>();
        for (PolarChunk chunk : includedChunks) {
            if (!blockSelector.testChunk(chunk.x(), chunk.z())) continue;
            includedByIndex.put(CoordConversion.chunkIndex(chunk.x(), chunk.z()), chunk);
        }

        Set<Long> chunkIndexes = new LinkedHashSet<>(includedByIndex.keySet());
        for (NewChunkHolder chunkHolder : chunkHolderManager.getChunkHolders()) {
            if (!blockSelector.testChunk(chunkHolder.chunkX, chunkHolder.chunkZ)) continue;
            chunkIndexes.add(CoordConversion.chunkIndex(chunkHolder.chunkX, chunkHolder.chunkZ));
        }

        List<PolarChunk> chunks = new ArrayList<>(chunkIndexes.size());
        for (long chunkIndex : chunkIndexes) {
            int chunkX = CoordConversion.chunkX(chunkIndex);
            int chunkZ = CoordConversion.chunkZ(chunkIndex);
            NewChunkHolder holder = chunkHolderManager.getChunkHolder(chunkX, chunkZ);

            if (holder != null && holder.getCurrentChunk() != null) {
                PolarChunk converted = PolarChunk.convertSynchronously(
                        world, holder.getCurrentChunk(), holder.getEntityChunk(),
                        polarWorldAccess, blockSelector, config.saveLight());
                if (converted != null) chunks.add(converted);
                continue;
            }

            PolarChunk included = includedByIndex.get(chunkIndex);
            if (included != null && containsEntireStoredChunk(blockSelector, included, world.getMinHeight())) {
                chunks.add(included);
            } else if (included != null) {
                LOGGER.warn("Dropping unloaded boundary chunk at {} {} during shutdown save to keep the world radius safe",
                        chunkX, chunkZ);
            }
        }

        return new PolarWorld(
                (byte) CoordConversion.sectionIndex(world.getMinHeight()),
                (byte) CoordConversion.sectionIndex(world.getMaxHeight() - 1),
                config,
                chunks
        );
    }

    private static boolean containsEntireStoredChunk(BlockSelector blockSelector, PolarChunk chunk, int minHeight) {
        int minSection = CoordConversion.sectionIndex(minHeight);
        for (int i = 0; i < chunk.sections().length; i++) {
            if (!blockSelector.containsEntireSection(chunk.x(), chunk.z(), minSection + i)) return false;
        }
        return true;
    }
}
