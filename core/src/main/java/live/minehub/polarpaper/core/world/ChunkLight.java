package live.minehub.polarpaper.core.world;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine;
import live.minehub.polarpaper.core.util.LightUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Collects the sky and block light of a chunk while its sections are being built, then hands it to the
 * finished chunk.
 * <p>
 * Starlight indexes nibbles by section with one extra padding entry below and above the world.
 */
final class ChunkLight {

    private static final int NO_SECTION = -1;

    private final PolarSection[] sections;
    private final SWMRNibbleArray[] blockNibbles;
    private final SWMRNibbleArray[] skyNibbles;
    private final boolean hasSkyLight;
    private boolean anyLightStored;

    ChunkLight(ServerLevel level, int sectionCount) {
        // Dimensions without a sky serve their sky light from a dummy listener and never build a sky light
        // engine, so expanding the saved sky light there would allocate 2 KiB a section for nobody to read
        this.hasSkyLight = level.dimensionType().hasSkyLight();

        this.sections = new PolarSection[sectionCount];
        this.blockNibbles = new SWMRNibbleArray[sectionCount + 2];
        this.skyNibbles = new SWMRNibbleArray[sectionCount + 2];
        this.blockNibbles[0] = new SWMRNibbleArray();
        this.blockNibbles[sectionCount + 1] = new SWMRNibbleArray();
        this.skyNibbles[0] = new SWMRNibbleArray();
        this.skyNibbles[sectionCount + 1] = new SWMRNibbleArray();
    }

    /**
     * Takes the light of a single section, {@code sectionIndex} being its offset from the bottom of the world.
     * <p>
     * Block light is expanded straight away. Sky light waits until the finished chunk can say which of its
     * sections hold blocks, because that is what decides whether a section needs storing at all.
     */
    void addSection(int sectionIndex, PolarSection section) {
        sections[sectionIndex] = section;
        blockNibbles[sectionIndex + 1] = LightUtil.createNibbleArray(section.blockLightContent(), section.blockLight());

        anyLightStored |= section.blockLightContent() != PolarSection.LightContent.MISSING
                || skyLightContent(sectionIndex) != PolarSection.LightContent.MISSING;
    }

    /**
     * Marks the chunk as lit, relighting it from scratch if the world was saved without light data.
     */
    void applyTo(ServerLevel level, LevelChunk chunk) {
        if (!anyLightStored) {
            PolarStreamLoader.lightChunk(level, chunk);
            chunk.setLightCorrect(true);
            return;
        }

        boolean[] emptySections = emptySections(chunk);
        buildSkyNibbles(emptySections);

        // Given a copy each, because both light engines write their own emptiness back into the array they
        // were handed, off their own threads
        chunk.starlight$setSkyEmptinessMap(emptySections);
        chunk.starlight$setBlockEmptinessMap(emptySections.clone());
        chunk.starlight$setSkyNibbles(skyNibbles);
        chunk.starlight$setBlockNibbles(blockNibbles);

        chunk.setLightCorrect(true);
    }

    /**
     * Expands the saved sky light, leaving every section above the chunk's highest blocks without storage.
     * <p>
     * Sky light is 15 everywhere above a chunk's highest block, because shadow only ever falls from above and
     * so nothing in a neighbouring chunk can darken open sky. Starlight relies on exactly that: a sky nibble
     * that is null, rather than merely uninitialised, reads back as fully lit, the chunk packet leaves those
     * sections out, and the client fills them in the same way. Storing them as 2 KiB of 0xFF apiece is what
     * the light engine would do itself if light ever propagated up there, but on a world that is mostly open
     * sky it is the bulk of what every loaded chunk costs.
     */
    private void buildSkyNibbles(boolean[] emptySections) {
        int highestBlockSection = highestNonEmptySection(emptySections);

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            if (sectionIndex > highestBlockSection) {
                skyNibbles[sectionIndex + 1] = LightUtil.createOpenSkyNibbleArray();
                continue;
            }

            skyNibbles[sectionIndex + 1] = LightUtil.createNibbleArray(skyLightContent(sectionIndex), sections[sectionIndex].skyLight());
        }
    }

    private PolarSection.LightContent skyLightContent(int sectionIndex) {
        if (!hasSkyLight) return PolarSection.LightContent.MISSING;
        return sections[sectionIndex].skyLightContent();
    }

    /**
     * The index of the highest section holding blocks, or {@link #NO_SECTION} for a chunk that is only air.
     * Decided the same way starlight decides it, so that both agree on where a chunk's open sky begins.
     */
    private static int highestNonEmptySection(boolean[] emptySections) {
        for (int sectionIndex = emptySections.length - 1; sectionIndex >= 0; sectionIndex--) {
            if (!emptySections[sectionIndex]) return sectionIndex;
        }
        return NO_SECTION;
    }

    private static boolean[] emptySections(LevelChunk chunk) {
        Boolean[] values = StarLightEngine.getEmptySectionsForChunk(chunk);
        boolean[] emptySections = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            emptySections[i] = values[i] != null && values[i];
        }
        return emptySections;
    }
}
