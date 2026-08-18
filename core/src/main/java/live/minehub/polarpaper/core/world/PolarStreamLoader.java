package live.minehub.polarpaper.core.world;

import ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.moonrise.common.util.WorldUtil;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import live.minehub.polarpaper.core.generator.PolarStreamingGenerator;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.util.CoordConversion;
import live.minehub.polarpaper.core.util.TaskFutures;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.getVarInt;

public class PolarStreamLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolarStreamLoader.class);

    private PolarStreamLoader() {}

    private static final MethodHandle GET_OR_CREATE_CHUNK_HOLDER_HANDLE;
    private static final VarHandle CURRENT_CHUNK_HANDLE;
    private static final VarHandle CURRENT_GEN_STATUS_HANDLE;
    private static final VarHandle CHUNK_COMPLETIONS_HANDLE;
    private static final VarHandle LAST_CHUNK_COMPLETION_HANDLE;
    private static final VarHandle ENTITY_CHUNK_HANDLE;
    private static final VarHandle CHUNK_COMPLETION_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(NewChunkHolder.ChunkCompletion[].class);
    private static final ChunkStatus[] ALL_STATUSES = ChunkStatus.getStatusList().toArray(new ChunkStatus[0]);

    static {
        try {
            GET_OR_CREATE_CHUNK_HOLDER_HANDLE = MethodHandles
                    .privateLookupIn(ChunkHolderManager.class, MethodHandles.lookup())
                    .findVirtual(ChunkHolderManager.class, "getOrCreateChunkHolder", MethodType.methodType(NewChunkHolder.class, int.class, int.class));

            CURRENT_CHUNK_HANDLE = MethodHandles
                    .privateLookupIn(NewChunkHolder.class, MethodHandles.lookup())
                    .findVarHandle(NewChunkHolder.class, "currentChunk", ChunkAccess.class);
            CURRENT_GEN_STATUS_HANDLE = MethodHandles
                    .privateLookupIn(NewChunkHolder.class, MethodHandles.lookup())
                    .findVarHandle(NewChunkHolder.class, "currentGenStatus", ChunkStatus.class);
            CHUNK_COMPLETIONS_HANDLE = MethodHandles
                    .privateLookupIn(NewChunkHolder.class, MethodHandles.lookup())
                    .findVarHandle(NewChunkHolder.class, "chunkCompletions", NewChunkHolder.ChunkCompletion[].class);
            LAST_CHUNK_COMPLETION_HANDLE = MethodHandles
                    .privateLookupIn(NewChunkHolder.class, MethodHandles.lookup())
                    .findVarHandle(NewChunkHolder.class, "lastChunkCompletion", NewChunkHolder.ChunkCompletion.class);
            ENTITY_CHUNK_HANDLE = MethodHandles
                    .privateLookupIn(NewChunkHolder.class, MethodHandles.lookup())
                    .findVarHandle(NewChunkHolder.class, "entityChunk", ChunkEntitySlices.class);
        } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static CompletableFuture<Void> stream(PolarSource source, World world, @NotNull PolarWorldAccess worldAccess) throws IOException {
        try {
            return stream(source.readBytes(), world, worldAccess);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public static CompletableFuture<Void> stream(PolarSource source, World world, @NotNull PolarDataConverter dataConverter, @NotNull PolarWorldAccess worldAccess) throws IOException {
        try {
            return stream(source.readBytes(), world, dataConverter, worldAccess);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public static CompletableFuture<Void> stream(byte @NotNull [] data, World world, @NotNull PolarWorldAccess worldAccess) {
        return stream(data, world, PolarDataConverter.DEFAULT, worldAccess, BlockSelector.ALL);
    }

    public static CompletableFuture<Void> stream(byte @NotNull [] data, World world, @NotNull PolarDataConverter dataConverter, @NotNull PolarWorldAccess worldAccess) {
        return stream(data, world, dataConverter, worldAccess, BlockSelector.ALL);
    }

    public static CompletableFuture<Void> stream(byte @NotNull [] data, World world, @NotNull PolarWorldAccess worldAccess,
                                                  @NotNull BlockSelector blockSelector) {
        return stream(data, world, PolarDataConverter.DEFAULT, worldAccess, blockSelector);
    }

    public static CompletableFuture<Void> stream(byte @NotNull [] data, World world, @NotNull PolarDataConverter dataConverter,
                                                  @NotNull PolarWorldAccess worldAccess, @NotNull BlockSelector blockSelector) {
        return stream(data, world, dataConverter, worldAccess, blockSelector, ChunkResidencyPolicy.LOAD_EVERYTHING, null);
    }

    public static CompletableFuture<Void> stream(byte @NotNull [] data, World world, @NotNull PolarDataConverter dataConverter,
                                                  @NotNull PolarWorldAccess worldAccess, @NotNull BlockSelector blockSelector,
                                                  @NotNull ChunkResidencyPolicy residency, @Nullable PolarChunkArchive archive) {
        if (residency != ChunkResidencyPolicy.LOAD_EVERYTHING && archive == null) {
            throw new IllegalArgumentException("A residency that leaves chunks out needs an archive to keep them in");
        }

        PolarGenerator polarGenerator = PolarGenerator.fromWorld(world);
        if (polarGenerator == null) return CompletableFuture.completedFuture(null);
        if (!(polarGenerator instanceof PolarStreamingGenerator voidGenerator)) return CompletableFuture.completedFuture(null);

        PolarContentReader.Content content = PolarContentReader.open(data, dataConverter);
        short version = content.version();
        ByteBuf uncompressed = content.body();

        voidGenerator.setVersion(version);
        voidGenerator.setDataVersion(content.dataVersion());
        voidGenerator.setUserData(content.userData());

        int dataVersion = content.dataVersion();
        int chunkCount = content.chunkCount();
        byte minSection = content.minSection();
        byte maxSection = content.maxSection();
        validateChunkCount(chunkCount, uncompressed, content.sectionCount());

        List<CompletableFuture<Void>> futures = new ArrayList<>(Math.min(chunkCount, 4096));
        Throwable readFailure = null;
        for (int i = 0; i < chunkCount; i++) {
            try {
                CompletableFuture<Void> future = readChunk(worldAccess.getPlugin(), world, dataConverter, worldAccess,
                        blockSelector, residency, archive, version, dataVersion, uncompressed, maxSection - minSection + 1);
                if (future == null) continue;
                if (future.isCompletedExceptionally()) throw future.exceptionNow();
                if (!future.isDone()) futures.add(future);
            } catch (Throwable e) {
                readFailure = e;
                break;
            }
        }

        // Says plainly how much of the file was made live, so that a residency which quietly fell back to
        // loading everything is visible in the log rather than only in the memory graph
        LOGGER.info("Streamed {}: {} chunks in the file, {} kept archived",
                world.getKey(), chunkCount, archive == null ? 0 : archive.size());

        CompletableFuture<Void> scheduledChunks = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        if (readFailure == null) return scheduledChunks;

        // Some chunks may already be queued on the main thread. Do not report failure (and let the caller unload
        // the world) until those tasks have stopped touching it.
        Throwable finalReadFailure = readFailure;
        return scheduledChunks.handle((_, scheduledFailure) -> {
            if (scheduledFailure != null && scheduledFailure != finalReadFailure) {
                finalReadFailure.addSuppressed(scheduledFailure);
            }
            throw new CompletionException(finalReadFailure);
        });
    }

    private static @Nullable CompletableFuture<Void> readChunk(
            Plugin plugin, World world, @NotNull PolarDataConverter dataConverter,
            @NotNull PolarWorldAccess worldAccess, @NotNull BlockSelector blockSelector,
            @NotNull ChunkResidencyPolicy residency, @Nullable PolarChunkArchive archive,
            short version, int dataVersion, @NotNull ByteBuf bb, int sectionCount) {
        int chunkX = getVarInt(bb);
        int chunkZ = getVarInt(bb);

        // Outside the world entirely: it is not part of the world and is not kept
        if (!blockSelector.testChunk(chunkX, chunkZ)) {
            PolarReader.skipChunkBody(version, bb, sectionCount);
            return null;
        }

        if (archive != null && !residency.shouldLoad(chunkX, chunkZ)) {
            archive.markArchived(chunkX, chunkZ);
            PolarReader.skipChunkBody(version, bb, sectionCount);
            return null;
        }

        CraftWorld craftWorld = (CraftWorld) world;
        ServerLevel serverLevel = craftWorld.getHandle();

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        int minSectionY = serverLevel.getMinSectionY();

        ChunkLight chunkLight = new ChunkLight(serverLevel, sectionCount);
        PolarSection[] polarSections = new PolarSection[sectionCount];
        LevelChunkSection[] levelChunkSections = new LevelChunkSection[sectionCount];
        boolean preserveStoredLight = true;
        for (int i = 0; i < sectionCount; i++) {
            PolarSection polarSection = PolarReader.readSection(dataConverter, version, dataVersion, bb);
            polarSections[i] = polarSection;
            int sectionY = minSectionY + i;

            try {
                LevelChunkSection levelChunkSection = polarSection.createLevelChunkSection(serverLevel, chunkPos, sectionY);
                levelChunkSections[i] = levelChunkSection;
                if (!blockSelector.containsEntireSection(chunkX, chunkZ, sectionY)) {
                    preserveStoredLight = false;
                    maskOutsideSelection(levelChunkSection, blockSelector, chunkX, chunkZ, sectionY);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load chunk at {} {} in {}", chunkX, chunkZ, world.getKey());
                throw e;
            }

        }

        if (preserveStoredLight) {
            for (int i = 0; i < sectionCount; i++) chunkLight.addSection(i, polarSections[i]);
        }

        NoUnloadLevelChunk newLevelChunk = new NoUnloadLevelChunk(serverLevel, chunkPos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, levelChunkSections, null, null);

        int blockEntityCount = getVarInt(bb);
        for (int i = 0; i < blockEntityCount; i++) {
            PolarChunk.BlockEntity polarBlockEntity = PolarReader.readBlockEntity(dataConverter, dataVersion, bb);
            if (!isBlockEntitySelected(polarBlockEntity, blockSelector, chunkX, chunkZ)) continue;
            addBlockEntity(polarBlockEntity, newLevelChunk);
        }

        // Skipped rather than read: insertChunk primes the heightmaps from the blocks it just placed
        PolarReader.skipHeightmaps(bb);

        // Objects
        byte[] userData = live.minehub.polarpaper.core.util.ByteArrayUtil.getByteArray(bb);

        return prepareChunkAsync(newLevelChunk).thenCompose(_ ->
                TaskFutures.runSync(plugin, () -> {
                    newLevelChunk.tryMarkSaved();
                    if (!insertChunk(serverLevel, newLevelChunk)) {
                        return null;
                    }
                    retainChunk(plugin, world, chunkX, chunkZ);
                    worldAccess.loadChunkData(world, newLevelChunk, userData, blockSelector);
                    chunkLight.applyTo(serverLevel, newLevelChunk);
                    return null;
                }));
    }

    /**
     * Keeps a chunk that was just put into the world from being unloaded again.
     * <p>
     * The ticket is taken on the next tick rather than now, because asking for one goes through
     * {@link org.bukkit.World#getChunkAt}, which drives the chunk system's own loading pipeline. Run against a
     * chunk holder that was populated by hand a moment ago, that pipeline finds a half built chunk where it
     * expects a finished one and fails. By the next tick the holder is settled and the ticket is just a ticket.
     */
    public static void retainChunk(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ) {
        Bukkit.getScheduler().runTask(plugin, () -> world.addPluginChunkTicket(chunkX, chunkZ, plugin));
    }

    /**
     * Replaces every unselected block in a boundary section with air before the chunk becomes visible.
     */
    public static void maskOutsideSelection(@NotNull LevelChunkSection section, @NotNull BlockSelector blockSelector,
                                            int chunkX, int chunkZ, int sectionY) {
        if (blockSelector.containsEntireSection(chunkX, chunkZ, sectionY) || section.hasOnlyAir()) return;

        BlockState air = Blocks.AIR.defaultBlockState();
        for (int index = 0; index < PolarSection.BLOCK_PALETTE_SIZE; index++) {
            if (blockSelector.test(index, chunkX, chunkZ, sectionY)) continue;

            int x = CoordConversion.sectionBlockIndexGetX(index);
            int y = CoordConversion.sectionBlockIndexGetY(index);
            int z = CoordConversion.sectionBlockIndexGetZ(index);
            if (!section.getBlockState(x, y, z).isAir()) section.setBlockState(x, y, z, air);
        }
    }

    public static boolean isBlockEntitySelected(@NotNull PolarChunk.BlockEntity blockEntity,
                                                 @NotNull BlockSelector blockSelector, int chunkX, int chunkZ) {
        int index = blockEntity.index();
        int x = chunkX * 16 + CoordConversion.chunkBlockIndexGetX(index);
        int y = CoordConversion.chunkBlockIndexGetY(index);
        int z = chunkZ * 16 + CoordConversion.chunkBlockIndexGetZ(index);
        return blockSelector.test(x, y, z);
    }

    /**
     * Builds the heightmaps before a chunk is made visible to the world. Keeping this work asynchronous retains
     * loading throughput, while awaiting it prevents a data race with chunk ticking and packet creation.
     */
    public static CompletableFuture<Void> prepareChunkAsync(LevelChunk chunk) {
        return CompletableFuture.runAsync(() -> primeMissingHeightmaps(chunk));
    }

    /**
     * Builds the heightmaps of a chunk that is about to be inserted, for callers already off the main thread.
     */
    public static void primeChunk(@NotNull LevelChunk chunk) {
        primeMissingHeightmaps(chunk);
    }

    /**
     * Puts a chunk's real blocks into a chunk that is already in the world, in place of what it holds now.
     * <p>
     * Used where a position was made visible as empty before its contents were meant to be seen. Swapping the
     * sections of the chunk that is already there, rather than putting a different chunk in its place, leaves
     * the chunk holder, its entities and everything tracking it untouched, so the only thing that changes is
     * what the blocks are.
     * <p>
     * The chunk is relit and resent afterwards, because terrain appearing out of nothing changes the light of
     * its neighbours as well as its own. Must be called on the thread that owns the world.
     */
    public static void replaceChunkBlocks(@NotNull ServerLevel serverLevel, @NotNull World world,
                                           @NotNull LevelChunk target, @NotNull LevelChunk source,
                                           @NotNull PolarChunk polarChunk, @NotNull PolarWorldAccess worldAccess,
                                           @NotNull BlockSelector blockSelector) {
        LevelChunkSection[] targetSections = target.getSections();
        LevelChunkSection[] sourceSections = source.getSections();
        if (targetSections.length != sourceSections.length) {
            throw new IllegalArgumentException("Cannot replace a chunk of " + targetSections.length
                    + " sections with one of " + sourceSections.length);
        }
        System.arraycopy(sourceSections, 0, targetSections, 0, targetSections.length);

        Heightmap.primeHeightmaps(target, ChunkStatus.FULL.heightmapsAfter());

        for (PolarChunk.BlockEntity blockEntity : polarChunk.blockEntities()) {
            if (!isBlockEntitySelected(blockEntity, blockSelector, polarChunk.x(), polarChunk.z())) continue;
            addBlockEntity(blockEntity, target);
        }
        worldAccess.loadChunkData(world, target, polarChunk.userData(), blockSelector);

        // Light is recomputed rather than copied from the file: the neighbours were lit against an empty
        // chunk here, and only the light engine knows how to correct them now that it is not empty
        relight(serverLevel, target);
        world.refreshChunk(target.locX, target.locZ);
    }

    /**
     * Relights a chunk and everything its light can reach.
     */
    private static void relight(@NotNull ServerLevel serverLevel, @NotNull LevelChunk chunk) {
        ThreadedLevelLightEngine lightEngine = (ThreadedLevelLightEngine) serverLevel.getLightEngine();
        lightEngine.starlight$serverRelightChunks(List.of(chunk.getPos()), _ -> {}, _ -> {});
    }

    /**
     * Puts a chunk built by hand into the world, as though the chunk system had loaded it itself.
     * <p>
     * Refuses the position if the chunk system already holds a chunk of its own there, and returns false.
     * That case is not ours to take over: the system finishes its own load afterwards and writes its chunk
     * back into the holder, which would leave the holder carrying a half built chunk while the ticket level
     * already says a finished one stands there. Everything that later asks the position to tick reads that
     * as a finished chunk and fails on the cast.
     *
     * @return whether the chunk was put into the world
     */
    public static boolean insertChunk(ServerLevel serverLevel, NoUnloadLevelChunk newLevelChunk) {
        int chunkX = newLevelChunk.locX;
        int chunkZ = newLevelChunk.locZ;

        // Usually already done by prepareChunkAsync. This cheap missing-only check keeps direct callers safe too.
        primeMissingHeightmaps(newLevelChunk);

        ChunkTaskScheduler chunkTaskScheduler = serverLevel.moonrise$getChunkTaskScheduler();
        ChunkHolderManager chunkHolderManager = chunkTaskScheduler.chunkHolderManager;

        // Begin reflection hell :D
        ReentrantAreaLock.Node lock = chunkHolderManager.ticketLockArea.lock(chunkX, chunkZ);
        ReentrantAreaLock.Node lock1 = chunkTaskScheduler.schedulingLockArea.lock(chunkX, chunkZ);
        NewChunkHolder newChunkHolder;
        boolean systemOwnsPosition;
        try {
            newChunkHolder = (NewChunkHolder) GET_OR_CREATE_CHUNK_HOLDER_HANDLE.invoke(chunkHolderManager, chunkX, chunkZ);
            systemOwnsPosition = newChunkHolder.getCurrentChunk() != null;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        } finally {
            chunkTaskScheduler.schedulingLockArea.unlock(lock1);
            chunkHolderManager.ticketLockArea.unlock(lock);
        }

        if (systemOwnsPosition) {
            LOGGER.warn("Not inserting chunk at {} {} in {}, the chunk system is already loading it",
                    chunkX, chunkZ, serverLevel.getWorld().getKey());
            return false;
        }

        newLevelChunk.needsDecoration = false;
        newLevelChunk.mustNotSave = true;
        CURRENT_CHUNK_HANDLE.set(newChunkHolder, newLevelChunk);
        CURRENT_GEN_STATUS_HANDLE.set(newChunkHolder, ChunkStatus.FULL);
        newLevelChunk.moonrise$setChunkHolder(newChunkHolder);

        // Populate every status up to and including FULL
        // This mirrors what replaceProtoChunk() does, but for all statuses including FULL
        NewChunkHolder.ChunkCompletion[] chunkCompletions = (NewChunkHolder.ChunkCompletion[]) CHUNK_COMPLETIONS_HANDLE.get(newChunkHolder);
        for (ChunkStatus status : ALL_STATUSES) {
            NewChunkHolder.ChunkCompletion completion = new NewChunkHolder.ChunkCompletion(newLevelChunk, status);
            CHUNK_COMPLETION_ARRAY_HANDLE.setVolatile(chunkCompletions, status.getIndex(), completion);

            if (status == ChunkStatus.FULL) {
                LAST_CHUNK_COMPLETION_HANDLE.set(newChunkHolder, completion);
            }
        }

        newLevelChunk.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
        newLevelChunk.runPostLoad();
        newLevelChunk.setLoaded(true);
        newLevelChunk.registerAllBlockEntitiesAfterLevelLoad();
        newLevelChunk.registerTickContainerInLevel(serverLevel);

        initializeEntityChunk(newChunkHolder);
        return true;
    }

    public static void lightChunk(ServerLevel level, LevelChunk chunk) {
        ThreadedLevelLightEngine threadedEngine = (ThreadedLevelLightEngine) level.getLightEngine();
        StarLightInterface starlight = threadedEngine.starlight$getLightEngine();
        starlight.lightChunk(chunk, StarLightEngine.getEmptySectionsForChunk(chunk));
    }

    private static ChunkEntitySlices initializeEntityChunk(NewChunkHolder holder) {
        ChunkEntitySlices slices = new ChunkEntitySlices(
                holder.world, holder.chunkX, holder.chunkZ, holder.getChunkStatus(),
                holder.holderData, WorldUtil.getMinSection(holder.world), WorldUtil.getMaxSection(holder.world)
        );
        slices.setTransient(false);

        ENTITY_CHUNK_HANDLE.set(holder, slices);

        holder.world.moonrise$getEntityLookup().entitySectionLoad(holder.chunkX, holder.chunkZ, slices);

        return slices;
    }

    private static void primeMissingHeightmaps(LevelChunk chunk) {
        EnumSet<Heightmap.Types> missing = EnumSet.noneOf(Heightmap.Types.class);
        missing.addAll(ChunkStatus.FULL.heightmapsAfter());
        missing.removeIf(chunk::hasPrimedHeightmap);
        Heightmap.primeHeightmaps(chunk, missing);
    }

    private static void validateChunkCount(int chunkCount, ByteBuf data, int sectionCount) {
        // Every chunk contains two coordinates, one marker per section, a block-entity count, a heightmap mask
        // and a userdata length. This lower bound rejects corrupt counts before they can allocate a huge list.
        int minimumChunkBytes = sectionCount + Integer.BYTES + 4;
        if (chunkCount < 0 || chunkCount > data.readableBytes() / minimumChunkBytes) {
            throw new IllegalArgumentException("Invalid chunk count: " + chunkCount);
        }
    }

    public static void addBlockEntity(PolarChunk.BlockEntity polarBlockEntity, ChunkAccess chunk) {
        int posIndex = polarBlockEntity.index();
        CompoundTag nbt = polarBlockEntity.data();

        int x = CoordConversion.chunkBlockIndexGetX(posIndex);
        int y = CoordConversion.chunkBlockIndexGetY(posIndex);
        int z = CoordConversion.chunkBlockIndexGetZ(posIndex);

        BlockState blockState = chunk.getBlockState(x, y, z);
        BlockPos blockPos = new BlockPos(chunk.locX * 16 + x, y, chunk.locZ * 16 + z);

        if (!(blockState.getBlock() instanceof EntityBlock entityBlock)) {
//            PolarPaper.logger().warning("Block " + blockState + " does not have a block entity");
//            throw new IllegalArgumentException("Block " + blockState + " does not have a block entity");
            return;
        }

        BlockEntity blockEntity = entityBlock.newBlockEntity(blockPos, blockState);
        if (blockEntity == null) {
//            PolarPaper.logger().warning("Block " + blockState + " returned null block entity");
//            throw new IllegalArgumentException("Block " + blockState + " returned null block entity");
            return;
        }

        var registryAccess = ((CraftServer) Bukkit.getServer()).getServer().registryAccess();

        // Load NBT data into the block entity
        ProblemReporter.ScopedCollector problemReporter = new ProblemReporter.ScopedCollector(() -> "addBlockEntity", LogUtils.getLogger());
        blockEntity.loadWithComponents(TagValueInput.create(problemReporter, registryAccess, nbt));

        if (chunk instanceof LevelChunk levelChunk) {
            blockEntity.setLevel(levelChunk.getLevel());
            levelChunk.addAndRegisterBlockEntity(blockEntity);
        } else {
            chunk.blockEntities.put(blockPos, blockEntity);
        }

    }

    @Contract("false, _ -> fail")
    private static void assertThat(boolean condition, @NotNull String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }



}
