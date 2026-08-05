package live.minehub.polarpaper.core.world;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import live.minehub.polarpaper.core.util.CoordConversion;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.getVarInt;

/**
 * A polar file's chunks, held compressed, for reading one at a time.
 * <p>
 * Made for a file that is consulted rather than played: a template a plugin asks about a block here and a
 * block there. Parsing the whole thing into a {@link PolarWorld} to answer those costs an order of magnitude
 * more memory than the file does, and keeps costing it for as long as the template is around. This keeps each
 * chunk as the bytes it occupies, still compressed, and expands one only when asked.
 * <p>
 * Immutable once read, and safe to use from any thread.
 */
public final class PolarChunkBodies {

    private final short version;
    private final int dataVersion;
    private final byte minSection;
    private final byte maxSection;
    private final byte[] userData;
    private final Map<Long, CompressedBody> bodies;
    private final long compressedBytes;

    private PolarChunkBodies(short version, int dataVersion, byte minSection, byte maxSection,
                             byte @NotNull [] userData, @NotNull Map<Long, CompressedBody> bodies,
                             long compressedBytes) {
        this.version = version;
        this.dataVersion = dataVersion;
        this.minSection = minSection;
        this.maxSection = maxSection;
        this.userData = userData;
        this.bodies = bodies;
        this.compressedBytes = compressedBytes;
    }

    /**
     * Reads a polar file, keeping every chunk compressed rather than parsed.
     * <p>
     * The file is decompressed once, here, and released on the way out. What stays is roughly what the file
     * costs on disk.
     */
    public static @NotNull PolarChunkBodies read(byte @NotNull [] data) {
        PolarContentReader.Content content = PolarContentReader.open(data, PolarDataConverter.DEFAULT);
        ByteBuf buffer = content.body();

        Map<Long, CompressedBody> bodies = HashMap.newHashMap(content.chunkCount());
        long compressedBytes = 0L;

        for (int i = 0; i < content.chunkCount(); i++) {
            int chunkX = getVarInt(buffer);
            int chunkZ = getVarInt(buffer);

            int start = buffer.readerIndex();
            PolarReader.skipChunkBody(content.version(), buffer, content.sectionCount());
            int length = buffer.readerIndex() - start;

            byte[] body = new byte[length];
            buffer.getBytes(start, body);

            CompressedBody compressed = new CompressedBody(Zstd.compress(body, COMPRESSION_LEVEL), length);
            bodies.put(CoordConversion.chunkIndex(chunkX, chunkZ), compressed);
            compressedBytes += compressed.data().length;
        }

        return new PolarChunkBodies(content.version(), content.dataVersion(), content.minSection(),
                content.maxSection(), content.userData(), Collections.unmodifiableMap(bodies), compressedBytes);
    }

    /**
     * Chosen for speed rather than size: this is paid once per chunk while reading a file, and the result
     * only has to live in memory.
     */
    private static final int COMPRESSION_LEVEL = 3;

    /**
     * Expands one chunk. The caller decides how long to hold onto it.
     *
     * @return the chunk, or null if this file does not hold that position
     */
    @Contract(pure = true)
    public @Nullable PolarChunk chunkAt(int chunkX, int chunkZ) {
        CompressedBody compressed = this.bodies.get(CoordConversion.chunkIndex(chunkX, chunkZ));
        if (compressed == null) return null;

        byte[] body = Zstd.decompress(compressed.data(), compressed.uncompressedLength());
        return PolarReader.readChunkBody(PolarDataConverter.DEFAULT, this.version, this.dataVersion,
                Unpooled.wrappedBuffer(body), this.sectionCount(), chunkX, chunkZ);
    }

    public boolean contains(int chunkX, int chunkZ) {
        return this.bodies.containsKey(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    @Contract(pure = true)
    public @NotNull @Unmodifiable Set<Long> positions() {
        return this.bodies.keySet();
    }

    public @Range(from = 0, to = Integer.MAX_VALUE) int size() {
        return this.bodies.size();
    }

    public byte minSection() {
        return this.minSection;
    }

    public byte maxSection() {
        return this.maxSection;
    }

    public int sectionCount() {
        return this.maxSection - this.minSection + 1;
    }

    public byte @NotNull [] userData() {
        return this.userData;
    }

    /**
     * What the chunks are costing on the heap, compressed.
     */
    public long compressedBytes() {
        return this.compressedBytes;
    }

    private record CompressedBody(byte @NotNull [] data, int uncompressedLength) {
    }
}
