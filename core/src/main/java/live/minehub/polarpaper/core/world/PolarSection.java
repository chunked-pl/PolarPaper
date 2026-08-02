package live.minehub.polarpaper.core.world;

import live.minehub.polarpaper.core.util.BlockStateCodec;
import live.minehub.polarpaper.core.util.PaletteUtil;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.Strategy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Representation of the latest version of the section format.
 * <p>
 * Marked as internal because of the use of mutable arrays. These arrays must _not_ be mutated.
 * This class should be considered immutable.
 */
@ApiStatus.Internal
public class PolarSection {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolarSection.class);

    public static final int BLOCK_PALETTE_SIZE = 4096;
    public static final int BIOME_PALETTE_SIZE = 64;

    public enum LightContent {
        MISSING, EMPTY, FULL, PRESENT;

        public static final LightContent[] VALUES = values();
    }

    private final boolean empty;

    private final String @NotNull [] blockPalette;
    private final long @Nullable [] blockData;

    private final String @NotNull [] biomePalette;
    private final long @Nullable [] biomeData;

    private final LightContent blockLightContent;
    private final byte @Nullable [] blockLight;
    private final LightContent skyLightContent;
    private final byte @Nullable [] skyLight;

    public PolarSection() {
        this.empty = true;

        this.blockPalette = new String[]{"minecraft:air"};
        this.blockData = null;
        this.biomePalette = new String[]{"minecraft:plains"};
        this.biomeData = null;

        this.blockLightContent = LightContent.MISSING;
        this.blockLight = null;
        this.skyLightContent = LightContent.MISSING;
        this.skyLight = null;
    }

    public PolarSection(
            String @NotNull [] blockPalette, long @Nullable [] blockData,
            String @NotNull [] biomePalette, long @Nullable [] biomeData,
            @NotNull LightContent blockLightContent, byte @Nullable [] blockLight,
            @NotNull LightContent skyLightContent, byte @Nullable [] skyLight
    ) {
        this.empty = false;

        this.blockPalette = blockPalette;
        this.blockData = blockData;
        this.biomePalette = biomePalette;
        this.biomeData = biomeData;

        this.blockLightContent = blockLightContent;
        this.blockLight = blockLight;
        this.skyLightContent = skyLightContent;
        this.skyLight = skyLight;
    }

    public PolarSection(
            @NotNull LightContent blockLightContent, byte @Nullable [] blockLight,
            @NotNull LightContent skyLightContent, byte @Nullable [] skyLight
    ) {
        this.empty = false;

        this.blockPalette = new String[]{"minecraft:air"};
        this.blockData = null;
        this.biomePalette = new String[]{"minecraft:plains"};
        this.biomeData = null;

        this.blockLightContent = blockLightContent;
        this.blockLight = blockLight;
        this.skyLightContent = skyLightContent;
        this.skyLight = skyLight;
    }

    public boolean isEmpty() {
        return empty;
    }

    public @NotNull String @NotNull [] blockPalette() {
        return blockPalette;
    }

    /**
     * Returns the uncompressed palette data. Each int corresponds to an index in the palette.
     * Always has a length of 4096.
     */
    public long[] blockData() {
        assert blockData != null : "must check length of blockPalette() before using blockData()";
        return blockData;
    }

    public @NotNull String @NotNull [] biomePalette() {
        return biomePalette;
    }

    /**
     * Returns the uncompressed palette data. Each int corresponds to an index in the palette.
     * Always has a length of 256.
     */
    public long[] biomeData() {
        assert biomeData != null : "must check length of biomePalette() before using biomeData()";
        return biomeData;
    }

    public @NotNull LightContent blockLightContent() {
        return blockLightContent;
    }

    public byte[] blockLight() {
        assert blockLight != null : "must check hasBlockLightData() before calling blockLight()";
        return blockLight;
    }

    public @NotNull LightContent skyLightContent() {
        return skyLightContent;
    }

    public byte[] skyLight() {
        assert skyLight != null : "must check hasSkyLightData() before calling skyLight()";
        return skyLight;
    }

    /**
     * Builds the chunk section this represents, in the position it belongs to.
     * <p>
     * Everything is created through the level's own {@link PalettedContainerFactory}, the way vanilla builds
     * sections when loading a chunk. Building the strategies here instead would give every single section its
     * own copy of the global palette and of the biome id map, all of which the factory already holds once for
     * the whole server, and would hide the preset states anti-xray needs.
     *
     * @param sectionY the section's position in the world, not its index within the chunk
     */
    public LevelChunkSection createLevelChunkSection(ServerLevel level, ChunkPos chunkPos, int sectionY) {
        PalettedContainerFactory containerFactory = level.palettedContainerFactory();
        if (empty) return new LevelChunkSection(containerFactory, level, chunkPos, sectionY);

        BlockState[] materialPalette = new BlockState[blockPalette.length];
        for (int i = 0; i < blockPalette.length; i++) {
            materialPalette[i] = BlockStateCodec.fromPaletteString(blockPalette[i]);
        }

        Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        Holder<Biome> defaultBiome = containerFactory.defaultBiome();
        Holder<Biome>[] biomeHolderPalette = new Holder[biomePalette.length];
        for (int i = 0; i < biomePalette.length; i++) {
            biomeHolderPalette[i] = resolveBiome(biomeRegistry, biomePalette[i], defaultBiome);
        }

        Strategy<BlockState> blockStrategy = containerFactory.blockStatesStrategy();
        Strategy<Holder<Biome>> biomeStrategy = containerFactory.biomeStrategy();

        PalettedContainer<BlockState> states = containerFactory.createForBlockStates(level, chunkPos, sectionY);
        PalettedContainer<Holder<Biome>> biomes = containerFactory.createForBiomes();
        states.data = createPaletteData(materialPalette, blockData, blockStrategy);
        biomes.data = createPaletteData(biomeHolderPalette, biomeData, biomeStrategy);

        return new LevelChunkSection(states, biomes);
    }

    private static Holder<Biome> resolveBiome(Registry<Biome> biomeRegistry, String biomeKey, Holder<Biome> fallback) {
        Identifier identifier = Identifier.tryParse(biomeKey);
        if (identifier == null) {
            LOGGER.warn("Failed to parse biome key, replacing it with the default biome: {}", biomeKey);
            return fallback;
        }

        Holder.Reference<Biome> biome = biomeRegistry.get(identifier).orElse(null);
        if (biome == null) {
            LOGGER.warn("Failed to get biome, replacing it with the default biome: {}", biomeKey);
            return fallback;
        }

        return biome;
    }

    private static <T> PalettedContainer.Data<T> createPaletteData(T[] palette, long @Nullable [] data, Strategy<T> strategy) {
        int bitsPerEntry = Mth.ceillog2(palette.length);
        if (data == null || data.length == 0 || bitsPerEntry == 0) {
            // One entry fills the whole section, which needs no per block storage at all
            Configuration configuration = PaletteUtil.getConfigurationForBitCount(strategy, 0);
            return new PalettedContainer.Data<>(
                    configuration,
                    new ZeroBitStorage(strategy.entryCount()),
                    configuration.createPalette(strategy, List.of(palette[0]))
            );
        }

        Configuration configuration = PaletteUtil.getConfigurationForBitCount(strategy, bitsPerEntry);
        long[] packed = repackForStorage(data, bitsPerEntry, configuration, strategy);

        try {
            return new PalettedContainer.Data<>(
                    configuration,
                    new SimpleBitStorage(configuration.bitsInMemory(), strategy.entryCount(), packed),
                    configuration.createPalette(strategy, Arrays.asList(palette))
            );
        } catch (SimpleBitStorage.InitializationException e) {
            throw new IllegalStateException(
                    "Cannot build a %d entry palette saved at %d bits in %d longs, the section stores %d bits per entry"
                            .formatted(palette.length, bitsPerEntry, data.length, configuration.bitsInMemory()), e);
        }
    }

    /**
     * The saved blocks in the layout the game keeps them in, repacking only when the width they were saved at
     * differs from the one this palette configuration uses in memory.
     */
    private static long[] repackForStorage(long[] data, int bitsPerEntry, Configuration configuration, Strategy<?> strategy) {
        int valuesPerLong = Long.SIZE / configuration.bitsInMemory();
        int requiredLength = (strategy.entryCount() + valuesPerLong - 1) / valuesPerLong;
        if (!configuration.alwaysRepack() && configuration.bitsInMemory() == bitsPerEntry && data.length == requiredLength) {
            return data.clone(); // already the right layout, but the section must not write into the saved array
        }

        int[] unpacked = new int[strategy.entryCount()];
        PaletteUtil.unpack(unpacked, data, PaletteUtil.getBitsForLongLength(data.length, strategy.entryCount()));
        return PaletteUtil.pack(unpacked, configuration.bitsInMemory());
    }

}