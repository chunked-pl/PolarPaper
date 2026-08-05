package live.minehub.polarpaper.core.world;

import io.netty.buffer.ByteBuf;
import live.minehub.polarpaper.core.source.BytesPolarSource;
import live.minehub.polarpaper.core.util.CoordConversion;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.getVarInt;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive is the only thing standing between a world loaded in part and a world saved in part, so what is
 * checked here is one thing said several ways: a chunk that was never made live still comes out of the file.
 */
final class PolarChunkArchiveTest {

    private static final byte MIN_SECTION = -4;
    private static final byte MAX_SECTION = 19;
    private static final int SECTION_COUNT = MAX_SECTION - MIN_SECTION + 1;
    private static final int WORLD_SIZE = 12;

    /** The chunks kept live, standing in for the ones a player has bought. */
    private static final int LIVE_CHUNKS = 3;

    /** Polar stamps the game's data version into every file it writes, and asks the server what that is. */
    @BeforeAll
    static void detectGameVersion() {
        SharedConstants.tryDetectVersion();
    }

    @Test
    void keepsEveryChunkThatWasNeverMadeLive() {
        Fixture fixture = Fixture.build();

        Map<Long, byte[]> written = bodies(fixture.save());

        assertEquals(fixture.original.size(), written.size(), "a chunk went missing from the file");
        assertEquals(fixture.original.keySet(), written.keySet());
    }

    @Test
    void writesArchivedChunksBackByteForByte() {
        Fixture fixture = Fixture.build();

        Map<Long, byte[]> written = bodies(fixture.save());

        for (Map.Entry<Long, byte[]> chunk : fixture.original.entrySet()) {
            if (fixture.live.contains(chunk.getKey())) continue;
            assertArrayEquals(chunk.getValue(), written.get(chunk.getKey()),
                    "archived chunk " + describe(chunk.getKey()) + " came back changed");
        }
    }

    @Test
    void holdsPositionsRatherThanBodies() {
        Fixture fixture = Fixture.build();

        long bodyBytes = 0L;
        for (Map.Entry<Long, byte[]> chunk : fixture.original.entrySet()) {
            if (!fixture.live.contains(chunk.getKey())) bodyBytes += chunk.getValue().length;
        }

        // Holding a chunk archived costs a position, not the chunk. An archive that ever creeps back towards
        // the size of what it stands for is the whole point of this class undone.
        assertTrue(fixture.archive.retainedBytes() * 10L < bodyBytes,
                "the archive retains " + fixture.archive.retainedBytes()
                        + " bytes for " + bodyBytes + " bytes of chunks");
    }

    @Test
    void takeReturnsTheStoredBodyAndReleasesThePosition() {
        Fixture fixture = Fixture.build();
        long position = fixture.anyArchived();
        int chunkX = CoordConversion.chunkX(position);
        int chunkZ = CoordConversion.chunkZ(position);
        int held = fixture.archive.size();

        byte[] body = fixture.archive.take(chunkX, chunkZ);

        assertArrayEquals(fixture.original.get(position), body);
        assertEquals(held - 1, fixture.archive.size());
        assertFalse(fixture.archive.contains(chunkX, chunkZ));
        assertNull(fixture.archive.take(chunkX, chunkZ), "the same chunk was handed out twice");
    }

    @Test
    void writesAChunkOnceWhenItGoesLiveAfterTheSnapshot() {
        Fixture fixture = Fixture.build();
        long position = fixture.anyArchived();

        // The snapshot is taken first, exactly as saving does, and only then is the chunk bought
        PolarChunkArchive.Snapshot snapshot = fixture.archive.snapshot();
        fixture.archive.take(CoordConversion.chunkX(position), CoordConversion.chunkZ(position));
        fixture.makeLive(position);

        Map<Long, byte[]> written = bodies(fixture.save(snapshot));

        assertEquals(fixture.original.size(), written.size(),
                "buying a chunk mid save either duplicated it or dropped it");
        assertEquals(fixture.original.keySet(), written.keySet());
    }

    @Test
    void restorePutsAChunkBackAfterMakingItLiveFailed() {
        Fixture fixture = Fixture.build();
        long position = fixture.anyArchived();
        int chunkX = CoordConversion.chunkX(position);
        int chunkZ = CoordConversion.chunkZ(position);

        fixture.archive.take(chunkX, chunkZ);
        fixture.archive.restore(chunkX, chunkZ);

        assertTrue(fixture.archive.contains(chunkX, chunkZ));
        assertEquals(fixture.original.size(), bodies(fixture.save()).size());
    }

    @Test
    void refusesToSaveWhenTheSourceIsMissingAChunk() {
        Fixture fixture = Fixture.build();
        fixture.archive.markArchived(9_999, 9_999);

        // Writing what it could find would quietly hand back a world short of a chunk
        assertThrows(IllegalStateException.class, fixture::save);
    }

    @Test
    void refusesToSaveAnArchiveThatHasNoSource() {
        Fixture fixture = Fixture.build();
        fixture.archive.bindSource(null);

        assertThrows(IllegalStateException.class, fixture::save);
    }

    @Test
    void savesAWorldThatHasNothingArchived() {
        Fixture fixture = Fixture.build();
        for (long position : List.copyOf(fixture.original.keySet())) {
            if (fixture.live.contains(position)) continue;
            fixture.archive.take(CoordConversion.chunkX(position), CoordConversion.chunkZ(position));
            fixture.makeLive(position);
        }

        assertTrue(fixture.archive.isEmpty());
        assertEquals(fixture.original.size(), bodies(fixture.save()).size());
    }

    /** A world written to a source, then loaded again with all but a few of its chunks left archived. */
    private static final class Fixture {

        private final byte[] sourceBytes;
        private final Map<Long, byte[]> original;
        private final List<Long> live = new ArrayList<>();
        private final Map<Long, PolarChunk> byPosition = new LinkedHashMap<>();
        private final PolarChunkArchive archive = new PolarChunkArchive();

        private Fixture(byte[] sourceBytes, Map<Long, byte[]> original) {
            this.sourceBytes = sourceBytes;
            this.original = original;
        }

        static Fixture build() {
            List<PolarChunk> chunks = new ArrayList<>();
            for (int x = 0; x < WORLD_SIZE; x++) {
                for (int z = 0; z < WORLD_SIZE; z++) {
                    chunks.add(chunk(x, z));
                }
            }

            byte[] sourceBytes = PolarWriter.write(world(chunks), PolarDataConverter.DEFAULT);
            Fixture fixture = new Fixture(sourceBytes, bodies(sourceBytes));
            fixture.archive.bindSource(new BytesPolarSource(sourceBytes));

            for (PolarChunk chunk : chunks) {
                long position = CoordConversion.chunkIndex(chunk.x(), chunk.z());
                fixture.byPosition.put(position, chunk);
                if (fixture.live.size() < LIVE_CHUNKS) {
                    fixture.live.add(position);
                } else {
                    fixture.archive.markArchived(chunk.x(), chunk.z());
                }
            }
            return fixture;
        }

        long anyArchived() {
            for (long position : this.original.keySet()) {
                if (!this.live.contains(position)) return position;
            }
            throw new IllegalStateException("nothing is archived");
        }

        void makeLive(long position) {
            this.live.add(position);
        }

        byte[] save() {
            return this.save(this.archive.snapshot());
        }

        byte[] save(PolarChunkArchive.Snapshot snapshot) {
            List<PolarChunk> liveChunks = new ArrayList<>(this.live.size());
            for (long position : this.live) {
                liveChunks.add(this.byPosition.get(position));
            }
            return PolarWriter.write(world(liveChunks), PolarDataConverter.DEFAULT, snapshot);
        }

        private static PolarWorld world(List<PolarChunk> chunks) {
            return new PolarWorld(PolarWorld.LATEST_VERSION, PolarDataConverter.DEFAULT.dataVersion(),
                    PolarWorld.CompressionType.ZSTD, MIN_SECTION, MAX_SECTION, new byte[0], chunks);
        }

        /** A chunk whose contents differ per position, so a body written back in the wrong place shows up. */
        private static PolarChunk chunk(int chunkX, int chunkZ) {
            PolarSection[] sections = new PolarSection[SECTION_COUNT];
            Arrays.setAll(sections, index -> index == 4
                    ? new PolarSection(
                            new String[]{"minecraft:air", "minecraft:stone", "minecraft:netherrack"},
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

    /** Every chunk in a file, as the exact bytes it occupies, keyed by position. */
    private static Map<Long, byte[]> bodies(byte[] data) {
        PolarContentReader.Content content = PolarContentReader.open(data, PolarDataConverter.DEFAULT);
        ByteBuf buffer = content.body();

        Map<Long, byte[]> found = new LinkedHashMap<>();
        for (int i = 0; i < content.chunkCount(); i++) {
            int chunkX = getVarInt(buffer);
            int chunkZ = getVarInt(buffer);

            int start = buffer.readerIndex();
            PolarReader.skipChunkBody(content.version(), buffer, content.sectionCount());

            byte[] body = new byte[buffer.readerIndex() - start];
            buffer.getBytes(start, body);
            found.put(CoordConversion.chunkIndex(chunkX, chunkZ), body);
        }
        return found;
    }

    private static String describe(long position) {
        return "[" + CoordConversion.chunkX(position) + "," + CoordConversion.chunkZ(position) + "]";
    }
}
