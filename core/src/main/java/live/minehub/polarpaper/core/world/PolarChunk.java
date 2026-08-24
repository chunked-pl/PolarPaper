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
    static final int HEIGHTMAP_SIZE = 16 * 16;
    static final int MAX_HEIGHTMAPS = 32;

    private static final String AIR_PALETTE_ENTRY = "minecraft:air";
    private static final String DEFAULT_BIOME_PALETTE_ENTRY = "minecraft:plains";
    private static final int UNUSED_PALETTE_ENTRY = -1;
    private static final int NO_SECTION = -1;

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

    public static CompletableFuture<@Nullable PolarChunk> convert(World world, int chunkX, int chunkZ, PolarWorldAccess worldAccess, BlockSelector blockSelector, boolean loadChunks) {
        return convert(world, chunkX, chunkZ, worldAccess, blockSelector, false, loadChunks);
    }

    public static CompletableFuture<@Nullable PolarChunk> convert(World world, int chunkX, int chunkZ, PolarWorldAccess worldAccess, BlockSelector blockSelector, boolean saveLight, boolean loadChunks) {
        ServerLevel serverLevel = ((CraftWorld) world).getHandle();

        ChunkHolderManager chunkHolderManager = serverLevel.moonrise$getChunkTaskScheduler().chunkHolderManager;
        NewChunkHolder chunkHolder = chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        if (chunkHolder != null && chunkHolder.getCurrentChunk() != null) {
            if (isChunkEmpty(chunkHolder)) return CompletableFuture.completedFuture(null);

            return convertInternal(world, chunkHolder.getCurrentChunk(), chunkHolder.getEntityChunk(),
                    worldAccess, blockSelector, saveLight, true);
        }

        if (!loadChunks) return CompletableFuture.completedFuture(null);

        CompletableFuture<@Nullable PolarChunk> future = new CompletableFuture<>();

        ChunkStatus status = saveLight ? ChunkStatus.FULL : ChunkStatus.FEATURES;
        serverLevel.moonrise$getChunkTaskScheduler().scheduleChunkLoad(chunkX, chunkZ, status, true, Priority.LOW, chunkAccess -> {

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

    private record SectionSnapshot(
            boolean onlyAir,
            long[] blockStorage, int blockBits, IntFunction<BlockState> blockPalette,
            long[] biomeStorage, List<Holder<Biome>> biomePalette
    ) {
        private static final SectionSnapshot ONLY_AIR =
                new SectionSnapshot(true, ZERO_STORAGE, 0, _ -> null, ZERO_STORAGE, List.of());
    }

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

                biomeData.storage().getRaw().clone(), biomePaletteValues
        );
    }

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

            List<String> maskedPalette = blockPaletteStrings;
            blockPaletteStrings = compactPalette(maskedPalette::get, blockIndices);
        }

        long[] blockData = blockPaletteStrings.size() > 1
                ? PaletteUtil.pack(blockIndices, packedBitsFor(blockPaletteStrings.size()))
                : null;

        for (Holder<Biome> biome : section.biomePalette()) {
            biomePaletteStrings.add(biomeKeyOrDefault(biomeRegistry, biome));
        }

        biomeData = section.biomeStorage();

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

    private record SectionLight(
            PolarSection.LightContent blockLightContent, byte @Nullable [] blockLight,
            PolarSection.LightContent skyLightContent, byte @Nullable [] skyLight
    ) {
        private static final SectionLight NONE = new SectionLight(
                PolarSection.LightContent.MISSING, null,
                PolarSection.LightContent.MISSING, null
        );
    }

    private static SectionLight readSectionLight(@Nullable LevelLightEngine lightEngine, int chunkX, int sectionY, int chunkZ, boolean openSky) {
        if (lightEngine == null) return SectionLight.NONE;

        SectionPos sectionPos = SectionPos.of(chunkX, sectionY, chunkZ);
        DataLayer blockLightLayer = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
        DataLayer skyLightLayer = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

        PolarSection.LightContent skyLightContent = openSky ? PolarSection.LightContent.FULL : PolarSection.LightContent.MISSING;
        if (skyLightLayer != null) skyLightContent = LightUtil.getLightContent(skyLightLayer);

        return new SectionLight(
                blockLightLayer == null ? PolarSection.LightContent.MISSING : LightUtil.getLightContent(blockLightLayer),
                blockLightLayer == null ? null : copyLightData(blockLightLayer),
                skyLightContent,
                skyLightLayer == null ? null : copyLightData(skyLightLayer)
        );
    }

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

        if (entityChunk != null) {
            for (net.minecraft.world.entity.Entity nmsEntity : entityChunk.getAllEntities()) {
                if (!nmsEntity.shouldBeSaved()) continue;
                return false;
            }
        }

        for (LevelChunkSection section : chunkAccess.getSections()) {
            if (section.hasOnlyAir()) continue;
            return false;
        }

        return true;
    }

    private static String biomeKeyOrDefault(Registry<Biome> biomeRegistry, Holder<Biome> biomeHolder) {
        if (biomeHolder != null && biomeHolder.value() instanceof Biome biome) {
            Identifier key = biomeRegistry.getKey(biome);
            if (key != null) return key.toString();
        }

        LOGGER.warn("Unnameable biome in section palette, saving it as {}", DEFAULT_BIOME_PALETTE_ENTRY);
        return DEFAULT_BIOME_PALETTE_ENTRY;
    }

    private static int[] unpackIndices(long[] storage, int bits) {
        int[] indices = new int[PolarSection.BLOCK_PALETTE_SIZE];

        if (bits == 0 || storage.length == 0) return indices;

        PaletteUtil.unpack(indices, storage, bits);
        return indices;
    }

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

    private static byte @Nullable [] copyLightData(DataLayer dataLayer) {
        if (dataLayer.isDefinitelyHomogenous()) return null;
        return dataLayer.getData().clone();
    }

}
