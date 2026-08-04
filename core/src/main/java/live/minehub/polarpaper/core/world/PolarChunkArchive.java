package live.minehub.polarpaper.core.world;

import com.github.luben.zstd.Zstd;
import live.minehub.polarpaper.core.util.CoordConversion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the chunks of a world that are not present in it, in the form they are written to disk.
 * <p>
 * A world does not have to make every chunk of its file live to keep it. Chunks left out are kept here as
 * the exact bytes the file holds for them, still compressed, which is around a tenth of what the same chunk
 * costs once it is a real chunk in the world. Saving writes them straight back out, so a chunk that was
 * never made live is preserved byte for byte instead of being lost.
 * <p>
 * Safe to use from any thread.
 */
public final class PolarChunkArchive {

    /**
     * A chunk body compresses to a few kilobytes, so the level is chosen for speed rather than size; this is
     * paid on every world load, and the result only has to live in memory.
     */
    private static final int COMPRESSION_LEVEL = 3;

    private final Map<Long, CompressedChunk> chunks = new ConcurrentHashMap<>();
    private final AtomicLong compressedBytes = new AtomicLong();

    /**
     * Takes a chunk body, which is everything the file holds for a chunk after its coordinates.
     */
    public void store(int chunkX, int chunkZ, byte @NotNull [] chunkBody) {
        CompressedChunk compressed = new CompressedChunk(
                Zstd.compress(chunkBody, COMPRESSION_LEVEL), chunkBody.length);

        CompressedChunk previous = this.chunks.put(CoordConversion.chunkIndex(chunkX, chunkZ), compressed);
        this.compressedBytes.addAndGet(compressed.data().length - (previous == null ? 0 : previous.data().length));
    }

    /**
     * Removes a chunk and gives back its body, or null if this archive does not hold that chunk.
     * <p>
     * Removing is the point: a chunk that has been made live in the world belongs to the world from then on,
     * and leaving a copy here would have saving write it twice.
     */
    public byte @Nullable [] take(int chunkX, int chunkZ) {
        CompressedChunk compressed = this.chunks.remove(CoordConversion.chunkIndex(chunkX, chunkZ));
        if (compressed == null) return null;

        this.compressedBytes.addAndGet(-compressed.data().length);
        return Zstd.decompress(compressed.data(), compressed.uncompressedLength());
    }

    public boolean contains(int chunkX, int chunkZ) {
        return this.chunks.containsKey(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    public @Range(from = 0, to = Integer.MAX_VALUE) int size() {
        return this.chunks.size();
    }

    public boolean isEmpty() {
        return this.chunks.isEmpty();
    }

    /**
     * What this archive is holding on the heap, for reporting. Excludes the map around it.
     */
    public long compressedBytes() {
        return this.compressedBytes.get();
    }

    /**
     * Everything this archive holds right now, still compressed.
     * <p>
     * Saving has to write a chunk count before the chunks themselves, so it needs a set that cannot change
     * underneath it: a chunk made live half way through would leave the file claiming more chunks than it
     * contains, which is a corrupt world. Taking references to the compressed bodies costs nothing, and each
     * one is expanded only as it is written.
     */
    public @NotNull @Unmodifiable List<ArchivedChunk> snapshot() {
        List<ArchivedChunk> snapshot = new ArrayList<>(this.chunks.size());
        for (Map.Entry<Long, CompressedChunk> entry : this.chunks.entrySet()) {
            snapshot.add(new ArchivedChunk(
                    CoordConversion.chunkX(entry.getKey()),
                    CoordConversion.chunkZ(entry.getKey()),
                    entry.getValue()));
        }
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * One archived chunk, held compressed until something asks for its body.
     */
    public static final class ArchivedChunk {

        private final int chunkX;
        private final int chunkZ;
        private final CompressedChunk compressed;

        private ArchivedChunk(int chunkX, int chunkZ, CompressedChunk compressed) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.compressed = compressed;
        }

        public int chunkX() {
            return this.chunkX;
        }

        public int chunkZ() {
            return this.chunkZ;
        }

        public byte @NotNull [] body() {
            return Zstd.decompress(this.compressed.data(), this.compressed.uncompressedLength());
        }
    }

    private record CompressedChunk(byte @NotNull [] data, int uncompressedLength) {
    }
}
