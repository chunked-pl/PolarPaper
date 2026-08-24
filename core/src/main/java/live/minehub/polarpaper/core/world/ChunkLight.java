package live.minehub.polarpaper.core.world;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine;
import live.minehub.polarpaper.core.util.LightUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

final class ChunkLight {

    private static final int NO_SECTION = -1;

    private final PolarSection[] sections;
    private final SWMRNibbleArray[] blockNibbles;
    private final SWMRNibbleArray[] skyNibbles;
    private final boolean hasSkyLight;
    private boolean anyLightStored;

    ChunkLight(ServerLevel level, int sectionCount) {

        this.hasSkyLight = level.dimensionType().hasSkyLight();

        this.sections = new PolarSection[sectionCount];
        this.blockNibbles = new SWMRNibbleArray[sectionCount + 2];
        this.skyNibbles = new SWMRNibbleArray[sectionCount + 2];
        this.blockNibbles[0] = new SWMRNibbleArray();
        this.blockNibbles[sectionCount + 1] = new SWMRNibbleArray();
        this.skyNibbles[0] = new SWMRNibbleArray();
        this.skyNibbles[sectionCount + 1] = new SWMRNibbleArray();
    }

    void addSection(int sectionIndex, PolarSection section) {
        sections[sectionIndex] = section;
        blockNibbles[sectionIndex + 1] = LightUtil.createNibbleArray(section.blockLightContent(), section.blockLight());

        anyLightStored |= section.blockLightContent() != PolarSection.LightContent.MISSING
                || skyLightContent(sectionIndex) != PolarSection.LightContent.MISSING;
    }

    void applyTo(ServerLevel level, LevelChunk chunk) {
        if (!anyLightStored) {
            PolarStreamLoader.lightChunk(level, chunk);
            chunk.setLightCorrect(true);
            return;
        }

        boolean[] emptySections = emptySections(chunk);
        buildSkyNibbles(emptySections);

        chunk.starlight$setSkyEmptinessMap(emptySections);
        chunk.starlight$setBlockEmptinessMap(emptySections.clone());
        chunk.starlight$setSkyNibbles(skyNibbles);
        chunk.starlight$setBlockNibbles(blockNibbles);

        chunk.setLightCorrect(true);
    }

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
