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
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
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
        int sectionCount = sections().length;
        ChunkPos chunkPos = new ChunkPos(x, z);
        int minSectionY = serverLevel.getMinSectionY();

        ChunkLight chunkLight = new ChunkLight(serverLevel, sectionCount);
        LevelChunkSection[] levelChunkSections = new LevelChunkSection[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            PolarSection polarSection = sections()[i];
            levelChunkSections[i] = polarSection.createLevelChunkSection(serverLevel, chunkPos, minSectionY + i);
            chunkLight.addSection(i, polarSection);
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
        CompletableFuture<@Nullable PolarChunk> future = new CompletableFuture<>();

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();

        ChunkHolderManager chunkHolderManager = serverLevel.moonrise$getChunkTaskScheduler().chunkHolderManager;
        NewChunkHolder chunkHolder = chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        if (chunkHolder != null && chunkHolder.getCurrentChunk() != null) {
            if (isChunkEmpty(chunkHolder)) return CompletableFuture.completedFuture(null);

            convertAsync(future, world, chunkHolder.getCurrentChunk(), chunkHolder.getEntityChunk(), worldAccess, blockSelector, saveLight);
            return future;
        }

        // Chunk is not already loaded

        if (!loadChunks) return CompletableFuture.completedFuture(null);

        // FULL required when saving light; FEATURES sufficient otherwise
        ChunkStatus status = saveLight ? ChunkStatus.FULL : ChunkStatus.FEATURES;
        serverLevel.moonrise$getChunkTaskScheduler().scheduleChunkLoad(chunkX, chunkZ, status, true, Priority.LOW, chunkAccess -> {
            NewChunkHolder loadedHolder = chunkHolderManager.getChunkHolder(chunkX, chunkZ);
            if (isChunkEmpty(loadedHolder)) {
                future.complete(null);
                return;
            }

            convertAsync(future, world, chunkAccess, loadedHolder.getEntityChunk(), worldAccess, blockSelector, saveLight);
        });

        return future;
    }

    /**
     * Converts a chunk off the main thread, completing {@code target} with the result.
     * <p>
     * The conversion itself returns a future, so both of them have to be settled before {@code target} is;
     * otherwise a failure part way through would leave whoever is waiting on it hanging forever.
     */
    private static void convertAsync(CompletableFuture<@Nullable PolarChunk> target, World world, ChunkAccess chunkAccess,
                                     @Nullable ChunkEntitySlices entityChunk, PolarWorldAccess worldAccess,
                                     BlockSelector blockSelector, boolean saveLight) {
        CompletableFuture.supplyAsync(() -> convert(world, chunkAccess, entityChunk, worldAccess, blockSelector, saveLight))
                .thenCompose(Function.identity())
                .whenComplete((polarChunk, ex) -> {
                    if (ex != null) {
                        target.completeExceptionally(ex);
                        return;
                    }
                    target.complete(polarChunk);
                });
    }

    public static CompletableFuture<@Nullable PolarChunk> convert(World world, ChunkAccess chunkAccess, @Nullable ChunkEntitySlices entityChunk, PolarWorldAccess worldAccess, BlockSelector blockSelector, boolean saveLight) {
        int chunkX = chunkAccess.locX;
        int chunkZ = chunkAccess.locZ;
        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        LevelLightEngine lightEngine = saveLight ? serverLevel.getLightEngine() : null;

        Registry<Biome> biomeRegistry = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);

        int sectionCount = chunkAccess.getSectionsCount();
        int minSection = chunkAccess.getMinSectionY();

        // Where a chunk's open sky begins is only known once it has been lit; an unlit one is saved without
        // any light at all, so that whoever reads it back lights it themselves
        boolean lit = chunkAccess.isLightCorrect();
        int highestBlockSection = highestNonEmptySection(chunkAccess);

        PolarSection[] sections = new PolarSection[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            LevelChunkSection chunkAccessSection = chunkAccess.getSection(i);
            sections[i] = convertSection(chunkX, chunkZ, chunkAccessSection, biomeRegistry, blockSelector, minSection, i, lightEngine, lit && i > highestBlockSection);
        }

        var registryAccess = ((CraftServer) Bukkit.getServer()).getServer().registryAccess();
        List<PolarChunk.BlockEntity> polarBlockEntities = new ArrayList<>();
        Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> blockEntities = new HashMap<>();

        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            FoliaUtil.scheduleOnRegionIfFolia(worldAccess.getPlugin(), world, chunkX, chunkZ, () -> {
                try {
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

                    future.complete(null);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }

        Function<Void, PolarChunk> finishConversion = _ -> {
            int[][] heightMaps = new int[PolarChunk.MAX_HEIGHTMAPS][];
            worldAccess.saveHeightmaps(chunkAccess, heightMaps);

            ByteBuf userDataOutput = Unpooled.buffer();
            List<net.minecraft.world.entity.Entity> allEntities = entityChunk == null ? List.of() : entityChunk.getAllEntities();
            List<org.bukkit.entity.Entity> newAllEntities = new ArrayList<>();
            for (net.minecraft.world.entity.Entity ent : allEntities) {
                if (blockSelector.test(ent.getBlockX(), ent.getBlockY(), ent.getBlockZ())) newAllEntities.add(ent.getBukkitEntity());
            }
            org.bukkit.entity.Entity[] entitiesArray = newAllEntities.toArray(new org.bukkit.entity.Entity[0]);
            worldAccess.saveChunkData(chunkAccess, blockEntities, entitiesArray, userDataOutput);
            byte[] userData = ByteArrayUtil.outputArray(userDataOutput);

            return new PolarChunk(
                    chunkX,
                    chunkZ,
                    sections,
                    polarBlockEntities.toArray(new BlockEntity[0]),
                    heightMaps,
                    userData
            );
        };

        // A Folia region task must not block while entity serialization schedules work back onto entity threads.
        CompletableFuture<PolarChunk> converted = FoliaUtil.isFolia()
                ? future.thenApplyAsync(finishConversion)
                : future.thenApply(finishConversion);
        return converted.exceptionally(e -> {
            LOGGER.error("Failed to convert chunk", e);
            return null;
        });
    }

    private static PolarSection convertSection(int chunkX, int chunkZ, LevelChunkSection chunkAccessSection, Registry<Biome> biomeRegistry, BlockSelector blockSelector, int minSection, int sectionI, @Nullable LevelLightEngine lightEngine, boolean openSky) {
        int sectionY = minSection + sectionI;
        SectionLight light = readSectionLight(lightEngine, chunkX, sectionY, chunkZ, openSky);

        if (chunkAccessSection.hasOnlyAir()) return createAirSection(light);

        long[] biomeData;
        List<String> biomePaletteStrings = new ArrayList<>();

        PalettedContainer.Data<BlockState> blockPaletteData = chunkAccessSection.getStates().data;
        Palette<BlockState> sourcePalette = blockPaletteData.palette();

        // Read the blocks out as plain indices. Working on a copy of the section's own data rather than the
        // live storage, so masking and compacting cannot disturb the world being saved.
        int[] blockIndices = readIndices(blockPaletteData.storage());
        List<String> blockPaletteStrings = compactPalette(index -> BlockStateCodec.toPaletteString(sourcePalette.valueFor(index)), blockIndices);

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
        PalettedContainer.Data<Holder<Biome>> biomePaletteData = ((PalettedContainer<Holder<Biome>>)chunkAccessSection.getBiomes()).data;
        Palette<Holder<Biome>> sourceBiomePalette = biomePaletteData.palette();
        for (int i = 0; i < sourceBiomePalette.getSize(); i++) {
            biomePaletteStrings.add(biomeKeyOrDefault(biomeRegistry, sourceBiomePalette.valueFor(i)));
        }

        // Kept in the layout the game already packed it in, which readers recover exactly
        biomeData = biomePaletteData.storage().getRaw().clone();

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
    private static int highestNonEmptySection(ChunkAccess chunkAccess) {
        LevelChunkSection[] sections = chunkAccess.getSections();
        for (int sectionIndex = sections.length - 1; sectionIndex >= 0; sectionIndex--) {
            if (!sections[sectionIndex].hasOnlyAir()) return sectionIndex;
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
    private static String biomeKeyOrDefault(Registry<Biome> biomeRegistry, @Nullable Holder<Biome> biomeHolder) {
        if (biomeHolder != null && biomeHolder.value() instanceof Biome biome) {
            Identifier key = biomeRegistry.getKey(biome);
            if (key != null) return key.toString();
        }

        LOGGER.warn("Unnameable biome in section palette, saving it as {}", DEFAULT_BIOME_PALETTE_ENTRY);
        return DEFAULT_BIOME_PALETTE_ENTRY;
    }

    private static int[] readIndices(BitStorage storage) {
        int[] indices = new int[storage.getSize()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = storage.get(i);
        }
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
