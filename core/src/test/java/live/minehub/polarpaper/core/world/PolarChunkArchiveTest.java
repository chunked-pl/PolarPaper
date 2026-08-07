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
import java.util.Set;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.getVarInt;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PolarChunkArchiveTest {

    private static final byte MIN_SECTION = -4;
    private static final byte MAX_SECTION = 19;
    private static final int SECTION_COUNT = MAX_SECTION - MIN_SECTION + 1;
    private static final int WORLD_SIZE = 12;

    private static final int LIVE_CHUNKS = 3;

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

        assertTrue(fixture.archive.retainedBytes() * 10L < bodyBytes,
                "the archive retains " + fixture.archive.retainedBytes()
                        + " bytes for " + bodyBytes + " bytes of chunks");
    }

    @Test
    void claimReturnsTheStoredBodyAndKeepsThePosition() {
        Fixture fixture = Fixture.build();
        long position = fixture.anyArchived();
        int chunkX = CoordConversion.chunkX(position);
        int chunkZ = CoordConversion.chunkZ(position);
        int held = fixture.archive.size();

        byte[] body = fixture.archive.claim(chunkX, chunkZ);

        assertArrayEquals(fixture.original.get(position), body);
        assertEquals(held, fixture.archive.size(), "claiming must not give up the position");
        assertTrue(fixture.archive.contains(chunkX, chunkZ));
        assertNull(fixture.archive.claim(chunkX, chunkZ), "the same chunk was handed out twice");
    }

    @Test
    void keepsAClaimedChunkInTheFileUntilItIsActuallyLive() {
        Fixture fixture = Fixture.build();
        long position = fixture.anyArchived();

        fixture.archive.claim(CoordConversion.chunkX(position), CoordConversion.chunkZ(position));

        Map<Long, byte[]> written = bodies(fixture.save());

        assertEquals(fixture.original.size(), written.size(),
                "a chunk being expanded was written to neither the archive nor the world");
        assertArrayEquals(fixture.original.get(position), written.get(position),
                "the chunk came back as something other than what it was");
    }

    @Test
    void writesAChunkOnceWhenItGoesLiveAfterTheSnapshot() {
        Fixture fixture = Fixture.build();
        long position = fixture.anyArchived();

        PolarChunkArchive.Snapshot snapshot = fixture.archive.snapshot();
        fixture.archive.claim(CoordConversion.chunkX(position), CoordConversion.chunkZ(position));
        fixture.goLive(position);

        Map<Long, byte[]> written = bodies(fixture.save(snapshot));

        assertEquals(fixture.original.size(), written.size(),
                "buying a chunk mid save either duplicated it or dropped it");
        assertEquals(fixture.original.keySet(), written.keySet());
    }

    @Test
    void keepsAChunkThatStoppedBeingLiveWithoutGoingBackToTheArchive() {
        Fixture fixture = Fixture.build();
        long position = fixture.anyArchived();
        fixture.goLive(position);
        fixture.live.remove(position);

        Map<Long, byte[]> written = bodies(fixture.saveKeepingOrphans());

        assertEquals(fixture.original.size(), written.size(),
                "a chunk that left the archive and stopped being live was dropped from the file");
        assertArrayEquals(fixture.original.get(position), written.get(position),
                "the recovered chunk came back as something other than what it was");
    }

    @Test
    void abandonLeavesTheChunkArchivedAfterMakingItLiveFailed() {
        Fixture fixture = Fixture.build();
        long position = fixture.anyArchived();
        int chunkX = CoordConversion.chunkX(position);
        int chunkZ = CoordConversion.chunkZ(position);

        fixture.archive.claim(chunkX, chunkZ);
        fixture.archive.abandon(chunkX, chunkZ);

        assertTrue(fixture.archive.contains(chunkX, chunkZ));
        assertArrayEquals(fixture.original.get(position), fixture.archive.claim(chunkX, chunkZ),
                "an abandoned chunk could not be claimed again");
        assertEquals(fixture.original.size(), bodies(fixture.save()).size());
    }

    @Test
    void refusesToSaveWhenTheSourceIsMissingAChunk() {
        Fixture fixture = Fixture.build();
        fixture.archive.markArchived(9_999, 9_999);

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
            fixture.archive.claim(CoordConversion.chunkX(position), CoordConversion.chunkZ(position));
            fixture.goLive(position);
        }

        assertTrue(fixture.archive.isEmpty());
        assertEquals(fixture.original.size(), bodies(fixture.save()).size());
    }

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

        void goLive(long position) {
            this.live.add(position);
            this.archive.release(CoordConversion.chunkX(position), CoordConversion.chunkZ(position));
        }

        byte[] save() {
            return this.save(this.archive.snapshot());
        }

        byte[] saveKeepingOrphans() {
            return this.save(this.archive.snapshotIncluding(this.archive.snapshot(), Set.copyOf(this.live)));
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
