package live.minehub.polarpaper.core.world;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import live.minehub.polarpaper.core.util.ByteArrayUtil;
import live.minehub.polarpaper.core.util.CoordConversion;
import live.minehub.polarpaper.core.util.PaletteUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.*;

public class PolarWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolarWriter.class);

    private PolarWriter() {
    }

    private static final int CHUNK_SECTION_SIZE = 16;

    /**
     * Rough guess used to size the content buffer, so writing a large world does not spend most of its time
     * growing that buffer by repeatedly reallocating and copying it.
     * <p>
     * Per section rather than per chunk, because that is what a chunk's size actually follows: a nether chunk
     * of 16 sections is two thirds the size of an overworld chunk of 24. Measured across real worlds at
     * around 1400 to 1700 bytes a section, and rounded up on purpose. Guessing high costs one larger
     * allocation; guessing low costs a copy of everything written so far, once per doubling.
     */
    private static final int ESTIMATED_BYTES_PER_SECTION = 1800;
    private static final int MAX_ESTIMATED_CONTENT_BYTES = 128 * 1024 * 1024;

    /** Magic number, version, data version, compression type and uncompressed length. */
    private static final int HEADER_BYTES = 4 + 2 + 5 + 1 + 5;

    public static byte[] write(@NotNull PolarWorld world) {
        return write(world, PolarDataConverter.DEFAULT);
    }

    public static byte[] write(@NotNull PolarWorld world, @NotNull PolarDataConverter dataConverter) {
        return write(world, dataConverter, PolarChunkArchive.Snapshot.EMPTY);
    }

    /**
     * Writes a world together with the chunks it was never asked to make live.
     * <p>
     * Archived chunks are copied straight across from the file they are already in, so a chunk nobody has
     * touched never goes through the palettes and comes out bit for bit unchanged.
     */
    public static byte[] writeWithArchive(@NotNull PolarWorld world, @NotNull PolarDataConverter dataConverter,
                                          @NotNull PolarChunkArchive archive) {
        return write(world, dataConverter, archive.snapshot());
    }

    /**
     * Writes a world together with an archive snapshot taken earlier.
     * <p>
     * Whoever is saving has to take that snapshot <em>before</em> it starts collecting the world's live
     * chunks. A chunk made live in between is then in both halves, and the duplicate check below keeps the
     * live one; taken the other way round it would be in neither, and would be missing from the file.
     * <p>
     * The archived bodies are read while this builds its buffer, so a source that has gone missing throws
     * here, before anything is handed back to be written. A caller that only writes what this returns can
     * never turn a failed read into a world with chunks missing.
     */
    public static byte[] write(@NotNull PolarWorld world, @NotNull PolarDataConverter dataConverter,
                               @NotNull PolarChunkArchive.Snapshot archiveSnapshot) {
        List<PolarChunk> nonEmptyChunks = world.nonEmptyChunks();
        Set<Long> archivedChunks = archivedChunksToWrite(nonEmptyChunks, archiveSnapshot);
        int chunkCount = nonEmptyChunks.size() + archivedChunks.size();

        ByteBuf content = Unpooled.buffer(
                estimateContentBytes(chunkCount, world.maxSection() - world.minSection() + 1));
        content.writeByte(world.minSection());
        content.writeByte(world.maxSection());
        writeVarInt(world.userData().length, content);
        content.writeBytes(world.userData());

        writeVarInt(chunkCount, content);
        for (PolarChunk chunk : nonEmptyChunks) {
            writeChunk(content, chunk, world.maxSection() - world.minSection() + 1);
        }
        archiveSnapshot.readBodies(archivedChunks, (chunkX, chunkZ, body, offset, length) -> {
            writeVarInt(chunkX, content);
            writeVarInt(chunkZ, content);
            content.writeBytes(body, offset, length);
        });

        byte[] contentBytes = ByteArrayUtil.outputArray(content);
        byte[] payload = switch (world.compression()) {
            case NONE -> contentBytes;
            case ZSTD -> Zstd.compress(contentBytes, world.compressionLevel());
        };

        ByteBuf out = Unpooled.buffer(HEADER_BYTES + payload.length);
        out.writeInt(PolarWorld.MAGIC_NUMBER);
        out.writeShort(PolarWorld.LATEST_VERSION);
        writeVarInt(dataConverter.dataVersion(), out);
        out.writeByte(world.compression().ordinal());
        writeVarInt(contentBytes.length, out); // uncompressed length
        out.writeBytes(payload);

        return ByteArrayUtil.outputArray(out);
    }

    /**
     * The archived chunks that are not also present in the world.
     * <p>
     * A chunk that has been made live belongs to the world from then on, and the archive drops it as it hands
     * it over. Should the two ever disagree, the live one is the newer of the pair and writing both would
     * leave the file with two entries for one position.
     */
    private static Set<Long> archivedChunksToWrite(
            @NotNull List<PolarChunk> liveChunks, @NotNull PolarChunkArchive.Snapshot archiveSnapshot) {
        if (archiveSnapshot.isEmpty()) return Set.of();

        Set<Long> liveIndexes = HashSet.newHashSet(liveChunks.size());
        for (PolarChunk chunk : liveChunks) {
            liveIndexes.add(CoordConversion.chunkIndex(chunk.x(), chunk.z()));
        }

        Set<Long> toWrite = new HashSet<>(archiveSnapshot.positions());
        // Made live after the snapshot was taken, so the live copy is the newer of the two
        toWrite.removeAll(liveIndexes);
        return toWrite;
    }

    private static int estimateContentBytes(int chunkCount, int sectionCount) {
        long estimated = (long) chunkCount * sectionCount * ESTIMATED_BYTES_PER_SECTION;
        return (int) Math.min(estimated, MAX_ESTIMATED_CONTENT_BYTES);
    }

    private static void writeChunk(@NotNull ByteBuf bb, @NotNull PolarChunk chunk, int sectionCount) {
        writeVarInt(chunk.x(), bb);
        writeVarInt(chunk.z(), bb);

        assert sectionCount == chunk.sections().length : "section count and chunk section length mismatch";

        for (var section : chunk.sections()) {
            writeSection(bb, section);
        }

        writeVarInt(chunk.blockEntities().length, bb);
        for (var blockEntity : chunk.blockEntities()) {
            writeBlockEntity(bb, blockEntity);
        }

        {
            int heightmapBits = 0;
            for (int i = 0; i < PolarChunk.MAX_HEIGHTMAPS; i++) {
                if (chunk.heightmap(i) != null)
                    heightmapBits |= 1 << i;
            }
            bb.writeInt(heightmapBits);

            int bitsPerEntry = PaletteUtil.bitsToRepresent(sectionCount * CHUNK_SECTION_SIZE);
            for (int i = 0; i < PolarChunk.MAX_HEIGHTMAPS; i++) {
                var heightmap = chunk.heightmap(i);
                if (heightmap == null) continue;
                if (heightmap.length == 0) writeLongArray(new long[0], bb);
                else writeLongArray(PaletteUtil.pack(heightmap, bitsPerEntry), bb);
            }
        }

        writeByteArray(chunk.userData(), bb);
    }

    private static void writeSection(@NotNull ByteBuf bb, @NotNull PolarSection section) {
        boolean empty = section.isEmpty();
        bb.writeByte(empty ? 1 : 0);
        if (empty) return;

        // Blocks
        var blockPalette = section.blockPalette();
        writeStringArray(blockPalette, bb);
        if (blockPalette.length > 1) {
            var blockData = section.blockData();
            writeLongArray(blockData, bb);
        }

        // Biomes
        var biomePalette = section.biomePalette();
        writeStringArray(biomePalette, bb);
        if (biomePalette.length > 1) {
            var biomeData = section.biomeData();
            writeLongArray(biomeData, bb);
        }

        // Light
        bb.writeByte((byte) section.blockLightContent().ordinal());
        if (section.blockLightContent() == PolarSection.LightContent.PRESENT)
            bb.writeBytes(section.blockLight());
        bb.writeByte((byte) section.skyLightContent().ordinal());
        if (section.skyLightContent() == PolarSection.LightContent.PRESENT)
            bb.writeBytes(section.skyLight());
    }

}