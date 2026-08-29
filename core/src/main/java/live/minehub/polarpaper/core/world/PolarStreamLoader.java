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

        LOGGER.info("Streamed {}: {} chunks in the file, {} kept archived",
                world.getKey(), chunkCount, archive == null ? 0 : archive.size());

        CompletableFuture<Void> scheduledChunks = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        if (readFailure == null) return scheduledChunks;

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
        List<PolarChunk.BlockEntity> blockEntities = new ArrayList<>(blockEntityCount);
        for (int i = 0; i < blockEntityCount; i++) {
            PolarChunk.BlockEntity polarBlockEntity = PolarReader.readBlockEntity(dataConverter, dataVersion, bb);
            if (!isBlockEntitySelected(polarBlockEntity, blockSelector, chunkX, chunkZ)) continue;
            blockEntities.add(polarBlockEntity);
        }

        PolarReader.skipHeightmaps(bb);

        byte[] userData = live.minehub.polarpaper.core.util.ByteArrayUtil.getByteArray(bb);

        return prepareChunkAsync(newLevelChunk).thenCompose(_ ->
                TaskFutures.runSync(plugin, () -> {
                    for (PolarChunk.BlockEntity blockEntity : blockEntities) {
                        addBlockEntity(blockEntity, newLevelChunk);
                    }
                    newLevelChunk.tryMarkSaved();
                    if (!insertChunk(serverLevel, newLevelChunk)) {
                        LevelChunk occupying = liveChunkAt(serverLevel, chunkX, chunkZ);
                        if (occupying == null) {
                            LOGGER.warn("Dropped the stored chunk at {} {} in {}, the chunk system claimed that position mid-load",
                                    chunkX, chunkZ, world.getKey());
                            return null;
                        }
                        replaceChunkBlocks(serverLevel, world, occupying, newLevelChunk,
                                blockEntities, userData, worldAccess, blockSelector);
                        retainChunk(plugin, world, chunkX, chunkZ);
                        return null;
                    }
                    retainChunk(plugin, world, chunkX, chunkZ);
                    worldAccess.loadChunkData(world, newLevelChunk, userData, blockSelector);
                    chunkLight.applyTo(serverLevel, newLevelChunk);
                    return null;
                }));
    }

    public static @Nullable LevelChunk liveChunkAt(@NotNull ServerLevel level, int chunkX, int chunkZ) {
        NewChunkHolder holder = level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        return holder != null && holder.getCurrentChunk() instanceof LevelChunk chunk ? chunk : null;
    }

    public static void retainChunk(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ) {
        if (Bukkit.isPrimaryThread()) {
            world.addPluginChunkTicket(chunkX, chunkZ, plugin);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> world.addPluginChunkTicket(chunkX, chunkZ, plugin));
    }

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

    public static CompletableFuture<Void> prepareChunkAsync(LevelChunk chunk) {
        return CompletableFuture.runAsync(() -> primeMissingHeightmaps(chunk));
    }

    public static void primeChunk(@NotNull LevelChunk chunk) {
        primeMissingHeightmaps(chunk);
    }

    public static void replaceChunkBlocks(@NotNull ServerLevel serverLevel, @NotNull World world,
                                           @NotNull LevelChunk target, @NotNull LevelChunk source,
                                           @NotNull PolarChunk polarChunk, @NotNull PolarWorldAccess worldAccess,
                                           @NotNull BlockSelector blockSelector) {
        List<PolarChunk.BlockEntity> blockEntities = new ArrayList<>(polarChunk.blockEntities().length);
        for (PolarChunk.BlockEntity blockEntity : polarChunk.blockEntities()) {
            if (!isBlockEntitySelected(blockEntity, blockSelector, polarChunk.x(), polarChunk.z())) continue;
            blockEntities.add(blockEntity);
        }
        replaceChunkBlocks(serverLevel, world, target, source, blockEntities, polarChunk.userData(), worldAccess, blockSelector);
    }

    public static void replaceChunkBlocks(@NotNull ServerLevel serverLevel, @NotNull World world,
                                           @NotNull LevelChunk target, @NotNull LevelChunk source,
                                           @NotNull List<PolarChunk.BlockEntity> blockEntities, byte @NotNull [] userData,
                                           @NotNull PolarWorldAccess worldAccess, @NotNull BlockSelector blockSelector) {
        LevelChunkSection[] targetSections = target.getSections();
        LevelChunkSection[] sourceSections = source.getSections();
        if (targetSections.length != sourceSections.length) {
            throw new IllegalArgumentException("Cannot replace a chunk of " + targetSections.length
                    + " sections with one of " + sourceSections.length);
        }
        System.arraycopy(sourceSections, 0, targetSections, 0, targetSections.length);

        Heightmap.primeHeightmaps(target, ChunkStatus.FULL.heightmapsAfter());

        for (PolarChunk.BlockEntity blockEntity : blockEntities) {
            addBlockEntity(blockEntity, target);
        }
        worldAccess.loadChunkData(world, target, userData, blockSelector);

        relight(serverLevel, target);
        world.refreshChunk(target.locX, target.locZ);
    }

    private static void relight(@NotNull ServerLevel serverLevel, @NotNull LevelChunk chunk) {
        ThreadedLevelLightEngine lightEngine = (ThreadedLevelLightEngine) serverLevel.getLightEngine();
        lightEngine.starlight$serverRelightChunks(List.of(chunk.getPos()), _ -> {}, _ -> {});
    }

    public static boolean insertChunk(ServerLevel serverLevel, NoUnloadLevelChunk newLevelChunk) {
        int chunkX = newLevelChunk.locX;
        int chunkZ = newLevelChunk.locZ;

        primeMissingHeightmaps(newLevelChunk);

        ChunkTaskScheduler chunkTaskScheduler = serverLevel.moonrise$getChunkTaskScheduler();
        ChunkHolderManager chunkHolderManager = chunkTaskScheduler.chunkHolderManager;

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

        if (systemOwnsPosition) return false;

        newLevelChunk.needsDecoration = false;
        newLevelChunk.mustNotSave = true;
        CURRENT_CHUNK_HANDLE.set(newChunkHolder, newLevelChunk);
        CURRENT_GEN_STATUS_HANDLE.set(newChunkHolder, ChunkStatus.FULL);
        newLevelChunk.moonrise$setChunkHolder(newChunkHolder);

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
        ChunkEntitySlices existing = (ChunkEntitySlices) ENTITY_CHUNK_HANDLE.get(holder);
        if (existing != null) {
            existing.setTransient(false);
            return existing;
        }

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

            return;
        }

        BlockEntity blockEntity = entityBlock.newBlockEntity(blockPos, blockState);
        if (blockEntity == null) {

            return;
        }

        var registryAccess = ((CraftServer) Bukkit.getServer()).getServer().registryAccess();

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
