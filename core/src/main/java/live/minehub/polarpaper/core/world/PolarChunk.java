package live.minehub.polarpaper.core.world;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import live.minehub.polarpaper.core.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntFunction;

public record PolarChunk(
        int x,
        int z,
        PolarSection[] sections,
        BlockEntity[] blockEntities,
        int[][] heightmaps,
        byte[] userData
) {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolarChunk.class);

    public static final int HEIGHTMAP_NONE = 0b0;
    public static final int HEIGHTMAP_MOTION_BLOCKING = 0b1;
    public static final int HEIGHTMAP_MOTION_BLOCKING_NO_LEAVES = 0b10;
    public static final int HEIGHTMAP_OCEAN_FLOOR = 0b100;
    public static final int HEIGHTMAP_OCEAN_FLOOR_WG = 0b1000;
    public static final int HEIGHTMAP_WORLD_SURFACE = 0b10000;
    public static final int HEIGHTMAP_WORLD_SURFACE_WG = 0b100000;
    static final int[] HEIGHTMAPS = new int[]{
            HEIGHTMAP_NONE,
            HEIGHTMAP_MOTION_BLOCKING,
            HEIGHTMAP_MOTION_BLOCKING_NO_LEAVES,
            HEIGHTMAP_OCEAN_FLOOR,
            HEIGHTMAP_OCEAN_FLOOR_WG,
            HEIGHTMAP_WORLD_SURFACE,
            HEIGHTMAP_WORLD_SURFACE_WG,
    };
    static final int HEIGHTMAP_SIZE = 16 * 16; // Chunk Size X * Chunk Size Z
    static final int MAX_HEIGHTMAPS = 32;

    private static final String AIR_PALETTE_ENTRY = "minecraft:air";
    private static final String DEFAULT_BIOME_PALETTE_ENTRY = "minecraft:plains";
    private static final int UNUSED_PALETTE_ENTRY = -1;
    private static final int NO_SECTION = -1;

    /** The packed storage of a section that holds a single value throughout, which stores nothing per entry. */
    private static final long[] ZERO_STORAGE = new long[0];

    public int @Nullable [] heightmap(int type) {
        return heightmaps[type];
    }

    public boolean isEmpty() {
        if (blockEntities.length != 0 || userData.length != 0) return false;
        for (PolarSection section : sections) {
            if (!section.isEmpty()) return false;
        }
        return true;
    }

    public PolarChunk(int x, int z, int sectionCount) {
        // Blank chunk
        this(x, z, new PolarSection[sectionCount], new BlockEntity[0], new int[PolarChunk.MAX_HEIGHTMAPS][], new byte[0]);
        Arrays.setAll(sections, _ -> new PolarSection());
    }

    public PolarChunk withUserData(byte[] newUserData) {
        return new PolarChunk(x, z, sections, blockEntities, heightmaps, newUserData);
    }

    public NoUnloadLevelChunk createLevelChunk(ServerLevel serverLevel) {
        return createLevelChunk(serverLevel, BlockSelector.ALL);
    }

    public NoUnloadLevelChunk createLevelChunk(ServerLevel serverLevel, BlockSelector blockSelector) {
        int sectionCount = sections().length;
        ChunkPos chunkPos = new ChunkPos(x, z);
        int minSectionY = serverLevel.getMinSectionY();

        ChunkLight chunkLight = new ChunkLight(serverLevel, sectionCount);
        LevelChunkSection[] levelChunkSections = new LevelChunkSection[sectionCount];
        boolean preserveStoredLight = true;
        for (int i = 0; i < sectionCount; i++) {
            PolarSection polarSection = sections()[i];
            int sectionY = minSectionY + i;
            LevelChunkSection levelChunkSection = polarSection.createLevelChunkSection(serverLevel, chunkPos, sectionY);
            levelChunkSections[i] = levelChunkSection;
            if (blockSelector.containsEntireSection(x, z, sectionY)) continue;

            preserveStoredLight = false;
            PolarStreamLoader.maskOutsideSelection(levelChunkSection, blockSelector, x, z, sectionY);
        }

        if (preserveStoredLight) {
            for (int i = 0; i < sectionCount; i++) chunkLight.addSection(i, sections()[i]);
        }

        NoUnloadLevelChunk chunk = new NoUnloadLevelChunk(serverLevel, chunkPos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, levelChunkSections, null, null);
        chunkLight.applyTo(serverLevel, chunk);

        return chunk;
    }

    public record BlockEntity(
            int index,
            @Nullable String id,
            @Nullable CompoundTag data
    ) {

    }

    /**
     * Converts a bukkit world chunk to a polar chunk without light data
     * @param world The bukkit world
     * @param chunkX The X coordinate of the chunk in the bukkit world
     * @param chunkZ The Z coordinate of the chunk in the bukkit world
     * @param blockSelector Used to filter which blocks are converted
     * @param loadChunks Whether to load chunks
     * @return The new PolarChunk, null if bukkit world chunk is empty
     */
    public static CompletableFuture<@Nullable PolarChunk> convert(World world, int chunkX, int chunkZ, PolarWorldAccess worldAccess, BlockSelector blockSelector, boolean loadChunks) {
        return convert(world, chunkX, chunkZ, worldAccess, blockSelector, false, loadChunks);
    }

    /**
     * Converts a bukkit world chunk to a polar chunk
     * @param world The bukkit world
     * @param chunkX The X coordinate of the chunk in the bukkit world
     * @param chunkZ The Z coordinate of the chunk in the bukkit world
     * @param blockSelector Used to filter which blocks are converted
     * @param saveLight Whether to save light data
     * @param loadChunks Whether to load chunks
     * @return The new PolarChunk, null if bukkit world chunk is empty
     */
    public static CompletableFuture<@Nullable PolarChunk> convert(World world, int chunkX, int chunkZ, PolarWorldAccess worldAccess, BlockSelector blockSelector, boolean saveLight, boolean loadChunks) {
        ServerLevel serverLevel = ((CraftWorld) world).getHandle();

        ChunkHolderManager chunkHolderManager = serverLevel.moonrise$getChunkTaskScheduler().chunkHolderManager;
        NewChunkHolder chunkHolder = chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        if (chunkHolder != null && chunkHolder.getCurrentChunk() != null) {
            if (isChunkEmpty(chunkHolder)) return CompletableFuture.completedFuture(null);

            return convertInternal(world, chunkHolder.getCurrentChunk(), chunkHolder.getEntityChunk(),
                    worldAccess, blockSelector, saveLight, true);
        }

        // Chunk is not already loaded

        if (!loadChunks) return CompletableFuture.completedFuture(null);

        CompletableFuture<@Nullable PolarChunk> future = new CompletableFuture<>();

        // FULL required when saving light; FEATURES sufficient otherwise
        ChunkStatus status = saveLight ? ChunkStatus.FULL : ChunkStatus.FEATURES;
        serverLevel.moonrise$getChunkTaskScheduler().scheduleChunkLoad(chunkX, chunkZ, status, true, Priority.LOW, chunkAccess -> {
            // Anything thrown in here is thrown inside the chunk system, which would leave this future
            // pending forever and hang the save that is waiting on it
            try {
                NewChunkHolder loadedHolder = chunkHolderManager.getChunkHolder(chunkX, chunkZ);
                if (isChunkEmpty(loadedHolder)) {
                    future.complete(null);
                    return;
                }

                convertInternal(world, chunkAccess, loadedHolder.getEntityChunk(), worldAccess, blockSelector, saveLight, true)
                        .whenComplete((polarChunk, ex) -> {
                            if (ex != null) future.completeExceptionally(ex);
                            else future.complete(polarChunk);
                        });
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });

        return future;
    }

    public static CompletableFuture<@Nullable PolarChunk> convert(World world, ChunkAccess chunkAccess, @Nullable ChunkEntitySlices entityChunk, PolarWorldAccess worldAccess, BlockSelector blockSelector, boolean saveLight) {
        return convertInternal(world, chunkAccess, entityChunk, worldAccess, blockSelector, saveLight, true);
    }

    public static @Nullable PolarChunk convertSynchronously(World world, ChunkAccess chunkAccess,
                                                             @Nullable ChunkEntitySlices entityChunk,
                                                             PolarWorldAccess worldAccess,
                                                             BlockSelector blockSelector, boolean saveLight) {
        return convertInternal(world, chunkAccess, entityChunk, worldAccess, blockSelector, saveLight, false).join();
    }

    /**
     * Converts one chunk in two stages: a snapshot taken on the thread that owns the world, then the work of
     * turning it into the saved form off that thread.
     * <p>
     * Everything that reads the live chunk happens inside the one main thread task, so nothing can change
     * underneath it. Turning blocks into palette strings, compacting and repacking them is by far the more
     * expensive half and touches nothing but the snapshot, so it stays off the main thread.
     * <p>
     * Failures are deliberately not swallowed here. A chunk that cannot be converted must fail the whole
     * save, because writing the file without it would replace a good world with one that is missing part of
     * itself.
     */
    private static CompletableFuture<PolarChunk> convertInternal(
            World world, ChunkAccess chunkAccess, @Nullable ChunkEntitySlices entityChunk,
            PolarWorldAccess worldAccess, BlockSelector blockSelector, boolean saveLight, boolean asyncFinish) {
        CompletableFuture<ChunkSnapshot> snapshot = TaskFutures.runSync(worldAccess.getPlugin(),
                () -> snapshotChunk(chunkAccess, entityChunk, worldAccess, blockSelector));

        Function<ChunkSnapshot, PolarChunk> buildChunk = chunkSnapshot ->
                buildChunk(world, chunkAccess, worldAccess, blockSelector, saveLight, chunkSnapshot);

        return asyncFinish
                ? snapshot.thenApplyAsync(buildChunk)
                : snapshot.thenApply(buildChunk);
    }

    /**
     * Everything the saved chunk is built out of, read in one go while the world cannot change.
     */
    private record ChunkSnapshot(
            int chunkX, int chunkZ,
            int minSectionY,
            boolean lightCorrect,
            SectionSnapshot[] sections,
            List<BlockEntity> polarBlockEntities,
            Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> blockEntities,
            org.bukkit.entity.Entity[] entities,
            int[][] heightmaps
    ) {
    }

    /**
     * One section's blocks and biomes, detached from the section they came from.
     * <p>
     * The packed storage is copied because the section keeps writing to its own; the palettes are resolved
     * through {@link PolarChunk#snapshotPalette}, which explains why holding on to them is safe.
     */
    private record SectionSnapshot(
            boolean onlyAir,
            long[] blockStorage, int blockBits, IntFunction<BlockState> blockPalette,
            long[] biomeStorage, List<Holder<Biome>> biomePalette
    ) {
        private static final SectionSnapshot ONLY_AIR =
                new SectionSnapshot(true, ZERO_STORAGE, 0, _ -> null, ZERO_STORAGE, List.of());
    }

    /**
     * Reads everything the conversion needs off the live chunk. Must run on the thread that owns the world.
     */
    private static ChunkSnapshot snapshotChunk(ChunkAccess chunkAccess, @Nullable ChunkEntitySlices entityChunk,
                                               PolarWorldAccess worldAccess, BlockSelector blockSelector) {
        int sectionCount = chunkAccess.getSectionsCount();
        SectionSnapshot[] sections = new SectionSnapshot[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            sections[i] = snapshotSection(chunkAccess.getSection(i));
        }

        RegistryAccess registryAccess = ((CraftServer) Bukkit.getServer()).getServer().registryAccess();
        List<PolarChunk.BlockEntity> polarBlockEntities = new ArrayList<>();
        Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> blockEntities = new HashMap<>();
        for (BlockPos blockPos : chunkAccess.getBlockEntitiesPos()) {
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = chunkAccess.getBlockEntity(blockPos);

            if (blockEntity == null) continue;
            if (!blockSelector.test(blockPos.getX(), blockPos.getY(), blockPos.getZ())) continue;

            CompoundTag compoundTag = blockEntity.saveWithFullMetadata(registryAccess);

            Optional<String> id = compoundTag.getString("id");
            if (id.isEmpty()) {
                LOGGER.warn("No ID in block entity data at: {}", blockPos);
                LOGGER.warn("Compound tag: {}", compoundTag);
                continue;
            }

            int index = CoordConversion.chunkBlockIndex(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            polarBlockEntities.add(new BlockEntity(index, id.get(), compoundTag));
            blockEntities.put(blockPos, blockEntity);
        }

        // Entity slices are not safe to walk while the world ticks, so the list is taken here even though the
        // entities themselves are serialised later
        List<org.bukkit.entity.Entity> entities = new ArrayList<>();
        if (entityChunk != null) {
            for (net.minecraft.world.entity.Entity entity : entityChunk.getAllEntities()) {
                if (!blockSelector.test(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ())) continue;
                entities.add(entity.getBukkitEntity());
            }
        }

        int[][] heightmaps = new int[PolarChunk.MAX_HEIGHTMAPS][];
        worldAccess.saveHeightmaps(chunkAccess, heightmaps);

        return new ChunkSnapshot(
                chunkAccess.locX, chunkAccess.locZ,
                chunkAccess.getMinSectionY(),
                // Where a chunk's open sky begins is only known once it has been lit; an unlit one is saved
                // without any light at all, so that whoever reads it back lights it themselves
                chunkAccess.isLightCorrect(),
                sections,
                polarBlockEntities,
                blockEntities,
                entities.toArray(new org.bukkit.entity.Entity[0]),
                heightmaps
        );
    }

    @SuppressWarnings("unchecked")
    private static SectionSnapshot snapshotSection(LevelChunkSection section) {
        if (section.hasOnlyAir()) return SectionSnapshot.ONLY_AIR;

        // Read once each: a section that grows past its palette width replaces this whole object, so reading
        // the storage and the palette out of the same one keeps them describing the same blocks
        PalettedContainer.Data<BlockState> blockData = section.getStates().data;
        PalettedContainer.Data<Holder<Biome>> biomeData = ((PalettedContainer<Holder<Biome>>) section.getBiomes()).data;

        Palette<Holder<Biome>> biomePalette = biomeData.palette();
        List<Holder<Biome>> biomePaletteValues = new ArrayList<>(biomePalette.getSize());
        for (int i = 0; i < biomePalette.getSize(); i++) {
            biomePaletteValues.add(biomePalette.valueFor(i));
        }

        return new SectionSnapshot(
                false,
                blockData.storage().getRaw().clone(), blockData.storage().getBits(), snapshotPalette(blockData.palette()),
                // Kept in the layout the game already packed it in, which readers recover exactly
                biomeData.storage().getRaw().clone(), biomePaletteValues
        );
    }

    /**
     * A palette that can be read from any thread afterwards.
     * <p>
     * Ordinary palettes only ever gain entries, never reassign them, but the structure holding them is not
     * safe to read while it grows, so the entries are copied out. A global palette is the whole block state
     * registry: far too large to copy, and frozen once the server is up, so it is read directly.
     */
    private static IntFunction<BlockState> snapshotPalette(Palette<BlockState> palette) {
        if (palette instanceof GlobalPalette<BlockState>) return palette::valueFor;

        BlockState[] values = new BlockState[palette.getSize()];
        for (int i = 0; i < values.length; i++) {
            values[i] = palette.valueFor(i);
        }
        return index -> {
            if (index < 0 || index >= values.length) {
                throw new IllegalStateException("Block " + index + " is outside the section's " + values.length + " entry palette");
            }
            return values[index];
        };
    }

    /**
     * Turns a snapshot into the chunk as it is saved. Runs off the main thread, and reads nothing live.
     */
    private static PolarChunk buildChunk(World world, ChunkAccess chunkAccess, PolarWorldAccess worldAccess,
                                         BlockSelector blockSelector, boolean saveLight, ChunkSnapshot snapshot) {
        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        LevelLightEngine lightEngine = saveLight ? serverLevel.getLightEngine() : null;
        Registry<Biome> biomeRegistry = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);

        SectionSnapshot[] sectionSnapshots = snapshot.sections();
        int highestBlockSection = highestNonEmptySection(sectionSnapshots);

        PolarSection[] sections = new PolarSection[sectionSnapshots.length];
        for (int i = 0; i < sectionSnapshots.length; i++) {
            sections[i] = convertSection(snapshot.chunkX(), snapshot.chunkZ(), sectionSnapshots[i], biomeRegistry,
                    blockSelector, snapshot.minSectionY(), i, lightEngine,
                    snapshot.lightCorrect() && i > highestBlockSection);
        }

        ByteBuf userDataOutput = Unpooled.buffer();
        worldAccess.saveChunkData(chunkAccess, snapshot.blockEntities(), snapshot.entities(), userDataOutput);
        byte[] userData = ByteArrayUtil.outputArray(userDataOutput);

        return new PolarChunk(
                snapshot.chunkX(),
                snapshot.chunkZ(),
                sections,
                snapshot.polarBlockEntities().toArray(new BlockEntity[0]),
                snapshot.heightmaps(),
                userData
        );
    }

    private static PolarSection convertSection(int chunkX, int chunkZ, SectionSnapshot section, Registry<Biome> biomeRegistry, BlockSelector blockSelector, int minSection, int sectionI, @Nullable LevelLightEngine lightEngine, boolean openSky) {
        int sectionY = minSection + sectionI;
        SectionLight light = readSectionLight(lightEngine, chunkX, sectionY, chunkZ, openSky);

        if (section.onlyAir()) return createAirSection(light);

        long[] biomeData;
        List<String> biomePaletteStrings = new ArrayList<>();

        // Working on the snapshot's own copy of the blocks, so masking and compacting cannot disturb the
        // world being saved
        IntFunction<BlockState> sourcePalette = section.blockPalette();
        int[] blockIndices = unpackIndices(section.blockStorage(), section.blockBits());
        List<String> blockPaletteStrings = compactPalette(index -> BlockStateCodec.toPaletteString(sourcePalette.apply(index)), blockIndices);

        if (!blockSelector.containsEntireSection(chunkX, chunkZ, sectionY)) {
            int airIndex = blockPaletteStrings.indexOf(AIR_PALETTE_ENTRY);
            if (airIndex == -1) {
                blockPaletteStrings.add(AIR_PALETTE_ENTRY);
                airIndex = blockPaletteStrings.size() - 1;
            }
            for (int index = 0; index < blockIndices.length; index++) {
                if (blockSelector.test(index, chunkX, chunkZ, sectionY)) continue;
                blockIndices[index] = airIndex;
            }

            // Blanking blocks can orphan whatever they used to reference, so compact what is left
            List<String> maskedPalette = blockPaletteStrings;
            blockPaletteStrings = compactPalette(maskedPalette::get, blockIndices);
        }

        // A single entry palette needs no data at all, the reader fills the section with that one block
        long[] blockData = blockPaletteStrings.size() > 1
                ? PaletteUtil.pack(blockIndices, packedBitsFor(blockPaletteStrings.size()))
                : null;

        // Resolved by index rather than by filtering the palette: dropping an entry that cannot be read would
        // shift every later index, silently reassigning the biome of every block that referenced them.
        for (Holder<Biome> biome : section.biomePalette()) {
            biomePaletteStrings.add(biomeKeyOrDefault(biomeRegistry, biome));
        }

        biomeData = section.biomeStorage();

        // sanity check
        if (biomeData.length == 0 && biomePaletteStrings.size() > 1) {
            biomePaletteStrings = List.of(biomePaletteStrings.getFirst());
            biomeData = null;
        }

        return new PolarSection(
                blockPaletteStrings.toArray(new String[0]), blockData,
                biomePaletteStrings.toArray(new String[0]), biomeData,
                light.blockLightContent(), light.blockLight(),
                light.skyLightContent(), light.skyLight()
        );
    }

    /**
     * The section a run of nothing but air is saved as, which is a single flag in the file once there is no
     * light to record alongside it.
     */
    private static PolarSection createAirSection(SectionLight light) {
        if (light.blockLightContent() == PolarSection.LightContent.MISSING
                && light.skyLightContent() == PolarSection.LightContent.MISSING) {
            return new PolarSection();
        }

        return new PolarSection(
                light.blockLightContent(), light.blockLight(),
                light.skyLightContent(), light.skyLight()
        );
    }

    /**
     * The light of one section as it is saved.
     */
    private record SectionLight(
            PolarSection.LightContent blockLightContent, byte @Nullable [] blockLight,
            PolarSection.LightContent skyLightContent, byte @Nullable [] skyLight
    ) {
        private static final SectionLight NONE = new SectionLight(
                PolarSection.LightContent.MISSING, null,
                PolarSection.LightContent.MISSING, null
        );
    }

    /**
     * Reads a section's light out of the light engine.
     *
     * @param openSky whether the section sits above every block in its chunk, where sky light is always full
     */
    private static SectionLight readSectionLight(@Nullable LevelLightEngine lightEngine, int chunkX, int sectionY, int chunkZ, boolean openSky) {
        if (lightEngine == null) return SectionLight.NONE;

        SectionPos sectionPos = SectionPos.of(chunkX, sectionY, chunkZ);
        DataLayer blockLightLayer = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
        DataLayer skyLightLayer = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

        // A sky layer the engine holds no storage for is not light that went missing: over open sky that is
        // how starlight records "fully lit", so it is saved as such and the file keeps describing its own
        // light for whichever reader picks it up next.
        PolarSection.LightContent skyLightContent = openSky ? PolarSection.LightContent.FULL : PolarSection.LightContent.MISSING;
        if (skyLightLayer != null) skyLightContent = LightUtil.getLightContent(skyLightLayer);

        return new SectionLight(
                blockLightLayer == null ? PolarSection.LightContent.MISSING : LightUtil.getLightContent(blockLightLayer),
                blockLightLayer == null ? null : copyLightData(blockLightLayer),
                skyLightContent,
                skyLightLayer == null ? null : copyLightData(skyLightLayer)
        );
    }

    /**
     * The index of the highest section holding blocks, or {@link #NO_SECTION} for a chunk that is only air.
     * Everything above it is open sky.
     */
    private static int highestNonEmptySection(SectionSnapshot[] sections) {
        for (int sectionIndex = sections.length - 1; sectionIndex >= 0; sectionIndex--) {
            if (!sections[sectionIndex].onlyAir()) return sectionIndex;
        }
        return NO_SECTION;
    }

    public static boolean isChunkEmpty(@Nullable NewChunkHolder chunkHolder) {
        if (chunkHolder == null) return true;

        ChunkAccess chunkAccess = chunkHolder.getCurrentChunk();
        if (chunkAccess == null) return true;

        ChunkEntitySlices entityChunk = chunkHolder.getEntityChunk();

        // if any entities that should be saved, return false
        if (entityChunk != null) {
            for (net.minecraft.world.entity.Entity nmsEntity : entityChunk.getAllEntities()) {
                if (!nmsEntity.shouldBeSaved()) continue;
                return false;
            }
        }

        // if any non-air sections, return false
        for (LevelChunkSection section : chunkAccess.getSections()) {
            if (section.hasOnlyAir()) continue;
            return false;
        }

        return true;
    }

    /**
     * The key a biome is saved under, falling back to the default biome for entries this server cannot name,
     * so that the entry keeps its place in the palette.
     */
    private static String biomeKeyOrDefault(Registry<Biome> biomeRegistry, Holder<Biome> biomeHolder) {
        if (biomeHolder != null && biomeHolder.value() instanceof Biome biome) {
            Identifier key = biomeRegistry.getKey(biome);
            if (key != null) return key.toString();
        }

        LOGGER.warn("Unnameable biome in section palette, saving it as {}", DEFAULT_BIOME_PALETTE_ENTRY);
        return DEFAULT_BIOME_PALETTE_ENTRY;
    }

    /**
     * The palette index of every block of a section, unpacked from the copy the snapshot took.
     * <p>
     * Laid out exactly as the game packs it: whole entries per long, with the leftover top bits unused, so
     * this recovers the same indices the section itself would report.
     */
    private static int[] unpackIndices(long[] storage, int bits) {
        int[] indices = new int[PolarSection.BLOCK_PALETTE_SIZE];
        // A section holding a single block throughout stores nothing per block, so every index is its first
        // and only palette entry
        if (bits == 0 || storage.length == 0) return indices;

        PaletteUtil.unpack(indices, storage, bits);
        return indices;
    }

    /**
     * Rewrites {@code indices} to reference only the palette entries they actually use, returning that palette.
     * <p>
     * Editing a world only ever grows a section's palette, so without this a saved section keeps entries for
     * blocks that were removed long ago. Every unused entry can push the palette over a power of two and cost
     * a bit on all 4096 blocks, in the file and in the loaded world alike. A section that overflowed to the
     * global palette would otherwise store every block state the server knows.
     *
     * @param resolveEntry maps an index of the current palette to the string the polar format stores it as
     */
    private static List<String> compactPalette(IntFunction<String> resolveEntry, int[] indices) {
        int maxIndex = 0;
        for (int index : indices) {
            if (index > maxIndex) maxIndex = index;
        }

        int[] compactedByIndex = new int[maxIndex + 1];
        Arrays.fill(compactedByIndex, UNUSED_PALETTE_ENTRY);

        List<String> palette = new ArrayList<>();
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            if (compactedByIndex[index] == UNUSED_PALETTE_ENTRY) {
                compactedByIndex[index] = palette.size();
                palette.add(resolveEntry.apply(index));
            }
            indices[i] = compactedByIndex[index];
        }
        return palette;
    }

    /**
     * The number of bits a section's blocks are packed with.
     * <p>
     * Readers recover the bit count from the length of the packed array, which is ambiguous for a few counts:
     * a 4096 entry section packs to the same 820 longs at both 11 and 12 bits. Rounding up to the next count
     * that survives the round trip keeps a compacted palette readable, at worst costing one bit per block.
     */
    private static int packedBitsFor(int paletteSize) {
        int bits = Mth.ceillog2(paletteSize);
        while (!roundTripsThroughPackedLength(bits)) {
            bits++;
        }
        return bits;
    }

    private static boolean roundTripsThroughPackedLength(int bits) {
        int valuesPerLong = Long.SIZE / bits;
        int longLength = (PolarSection.BLOCK_PALETTE_SIZE + valuesPerLong - 1) / valuesPerLong;
        return PaletteUtil.getBitsForLongLength(longLength, PolarSection.BLOCK_PALETTE_SIZE) == bits;
    }

    /**
     * The light of a layer as it should be saved, or null if it is uniform and therefore fully described by
     * its {@link PolarSection.LightContent}.
     * <p>
     * Copied because the light engine keeps writing to the layer while the world is being saved.
     */
    private static byte @Nullable [] copyLightData(DataLayer dataLayer) {
        if (dataLayer.isDefinitelyHomogenous()) return null;
        return dataLayer.getData().clone();
    }

}
