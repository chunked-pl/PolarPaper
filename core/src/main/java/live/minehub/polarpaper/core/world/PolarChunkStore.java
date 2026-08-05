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

public final class PolarChunkStore {

    private static final int COMPRESSION_LEVEL = 3;

    private final short version;
    private final int dataVersion;
    private final byte minSection;
    private final byte maxSection;
    private final byte[] userData;
    private final Map<Long, CompressedChunk> chunks;
    private final long compressedBytes;

    private PolarChunkStore(short version, int dataVersion, byte minSection, byte maxSection,
                            byte @NotNull [] userData, @NotNull Map<Long, CompressedChunk> chunks,
                            long compressedBytes) {
        this.version = version;
        this.dataVersion = dataVersion;
        this.minSection = minSection;
        this.maxSection = maxSection;
        this.userData = userData;
        this.chunks = chunks;
        this.compressedBytes = compressedBytes;
    }

    public static @NotNull PolarChunkStore read(byte @NotNull [] data) {
        PolarContentReader.Content content = PolarContentReader.open(data, PolarDataConverter.DEFAULT);
        ByteBuf buffer = content.body();

        Map<Long, CompressedChunk> chunks = HashMap.newHashMap(content.chunkCount());
        long compressedBytes = 0L;

        for (int i = 0; i < content.chunkCount(); i++) {
            int chunkX = getVarInt(buffer);
            int chunkZ = getVarInt(buffer);

            int start = buffer.readerIndex();
            PolarReader.skipChunkBody(content.version(), buffer, content.sectionCount());
            int length = buffer.readerIndex() - start;

            byte[] body = new byte[length];
            buffer.getBytes(start, body);

            CompressedChunk compressed = new CompressedChunk(Zstd.compress(body, COMPRESSION_LEVEL), length);
            chunks.put(CoordConversion.chunkIndex(chunkX, chunkZ), compressed);
            compressedBytes += compressed.data().length;
        }

        return new PolarChunkStore(content.version(), content.dataVersion(), content.minSection(),
                content.maxSection(), content.userData(), Collections.unmodifiableMap(chunks), compressedBytes);
    }

    @Contract(pure = true)
    public @Nullable PolarChunk chunkAt(int chunkX, int chunkZ) {
        CompressedChunk compressed = this.chunks.get(CoordConversion.chunkIndex(chunkX, chunkZ));
        if (compressed == null) return null;

        byte[] body = Zstd.decompress(compressed.data(), compressed.uncompressedLength());
        return PolarReader.readChunkBody(PolarDataConverter.DEFAULT, this.version, this.dataVersion,
                Unpooled.wrappedBuffer(body), this.sectionCount(), chunkX, chunkZ);
    }

    public boolean contains(int chunkX, int chunkZ) {
        return this.chunks.containsKey(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    @Contract(pure = true)
    public @NotNull @Unmodifiable Set<Long> positions() {
        return this.chunks.keySet();
    }

    public @Range(from = 0, to = Integer.MAX_VALUE) int size() {
        return this.chunks.size();
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

    public long compressedBytes() {
        return this.compressedBytes;
    }

    private record CompressedChunk(byte @NotNull [] data, int uncompressedLength) {
    }
}
