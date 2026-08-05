package live.minehub.polarpaper.core.world;

import live.minehub.polarpaper.core.util.CoordConversion;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template read this way has to answer exactly what reading the whole world would have answered, while
 * costing something closer to the file than to the world.
 */
final class PolarChunkBodiesTest {

    private static final byte MIN_SECTION = -4;
    private static final byte MAX_SECTION = 19;
    private static final int SECTION_COUNT = MAX_SECTION - MIN_SECTION + 1;
    private static final int WORLD_SIZE = 10;

    @BeforeAll
    static void detectGameVersion() {
        SharedConstants.tryDetectVersion();
    }

    @Test
    void holdsEveryChunkTheFileHolds() {
        PolarChunkBodies bodies = PolarChunkBodies.read(file());

        assertEquals(WORLD_SIZE * WORLD_SIZE, bodies.size());
        assertTrue(bodies.contains(0, 0));
        assertTrue(bodies.contains(WORLD_SIZE - 1, WORLD_SIZE - 1));
        assertFalse(bodies.contains(WORLD_SIZE, 0));
        assertEquals(SECTION_COUNT, bodies.sectionCount());
    }

    @Test
    void expandsAChunkIntoWhatReadingTheWholeWorldWouldHaveGiven() {
        byte[] file = file();
        PolarWorld whole = PolarReader.read(file);
        PolarChunkBodies bodies = PolarChunkBodies.read(file);

        for (long position : bodies.positions()) {
            int chunkX = CoordConversion.chunkX(position);
            int chunkZ = CoordConversion.chunkZ(position);

            PolarChunk expected = whole.chunkAt(chunkX, chunkZ);
            PolarChunk actual = bodies.chunkAt(chunkX, chunkZ);

            assertEquals(expected.x(), actual.x());
            assertEquals(expected.z(), actual.z());
            assertArrayEquals(expected.userData(), actual.userData());
            assertEquals(expected.sections().length, actual.sections().length);

            for (int section = 0; section < expected.sections().length; section++) {
                PolarSection before = expected.sections()[section];
                PolarSection after = actual.sections()[section];
                assertEquals(before.isEmpty(), after.isEmpty());
                assertArrayEquals(before.blockPalette(), after.blockPalette(),
                        "palette differs at chunk [" + chunkX + "," + chunkZ + "] section " + section);
                if (before.blockPalette().length > 1) {
                    assertArrayEquals(before.blockData(), after.blockData(),
                            "blocks differ at chunk [" + chunkX + "," + chunkZ + "] section " + section);
                }
            }
        }
    }

    @Test
    void reportsNothingForAChunkTheFileDoesNotHold() {
        assertNull(PolarChunkBodies.read(file()).chunkAt(9_999, 9_999));
    }

    @Test
    void costsFarLessThanTheChunksItStandsFor() {
        byte[] file = file();
        PolarChunkBodies bodies = PolarChunkBodies.read(file);

        long expanded = 0L;
        for (long position : bodies.positions()) {
            PolarChunk chunk = bodies.chunkAt(
                    CoordConversion.chunkX(position), CoordConversion.chunkZ(position));
            for (PolarSection section : chunk.sections()) {
                if (section.blockPalette().length > 1 && section.blockData() != null) {
                    expanded += (long) section.blockData().length * Long.BYTES;
                }
            }
        }

        assertTrue(bodies.compressedBytes() * 4L < expanded,
                "holding " + bodies.compressedBytes() + " bytes for " + expanded + " bytes of block data");
    }

    private static byte[] file() {
        List<PolarChunk> chunks = new ArrayList<>();
        for (int x = 0; x < WORLD_SIZE; x++) {
            for (int z = 0; z < WORLD_SIZE; z++) {
                chunks.add(chunk(x, z));
            }
        }

        PolarWorld world = new PolarWorld(PolarWorld.LATEST_VERSION,
                PolarDataConverter.DEFAULT.dataVersion(), PolarWorld.CompressionType.ZSTD,
                MIN_SECTION, MAX_SECTION, new byte[0], chunks);
        return PolarWriter.write(world, PolarDataConverter.DEFAULT);
    }

    /** Contents differ per position, so a body expanded from the wrong place would show up. */
    private static PolarChunk chunk(int chunkX, int chunkZ) {
        PolarSection[] sections = new PolarSection[SECTION_COUNT];
        Arrays.setAll(sections, index -> index == 6
                ? new PolarSection(
                        new String[]{"minecraft:air", "minecraft:stone", "minecraft:dirt"},
                        storage(chunkX, chunkZ),
                        new String[]{"minecraft:plains"}, null,
                        PolarSection.LightContent.MISSING, null,
                        PolarSection.LightContent.MISSING, null)
                : new PolarSection());

        return new PolarChunk(chunkX, chunkZ, sections, new PolarChunk.BlockEntity[0],
                new int[32][], new byte[]{(byte) chunkX, (byte) chunkZ});
    }

    private static long[] storage(int chunkX, int chunkZ) {
        long[] packed = new long[256];
        Arrays.fill(packed, ((long) chunkX << 32) ^ chunkZ);
        return packed;
    }
}
