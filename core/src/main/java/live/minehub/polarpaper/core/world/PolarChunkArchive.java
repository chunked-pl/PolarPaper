package live.minehub.polarpaper.core.world;

import io.netty.buffer.ByteBuf;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.util.CoordConversion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.getVarInt;

/**
 * Remembers which chunks of a world were left out of it, so that saving writes them back.
 * <p>
 * A world does not have to make every chunk of its file live to keep it. What is left out stays where it
 * already is, in the file, and this holds nothing but the positions: a world of a thousand archived chunks
 * costs tens of kilobytes here rather than tens of megabytes. Bodies are read back from the source at the
 * moment they are needed, which is a chunk being made live, or the world being saved.
 * <p>
 * The file is the only copy of those chunks, so every path that could lose one fails loudly instead. A body
 * that cannot be read puts its position back and throws, and a save that cannot find every chunk it promised
 * to write throws before a single byte reaches disk, leaving the previous file untouched.
 * <p>
 * Safe to use from any thread.
 */
public final class PolarChunkArchive {

    /**
     * What one archived position costs on the heap: a boxed long, the map node holding it and the reference
     * in the table. Measured against ConcurrentHashMap's layout on a 64 bit JVM with compressed oops.
     */
    private static final long BYTES_PER_POSITION = 48L;

    private final Set<Long> positions = ConcurrentHashMap.newKeySet();
    private volatile @Nullable PolarSource source;

    /**
     * Points the archive at the file its chunks live in.
     * <p>
     * Has to be called again whenever the world is written somewhere else, once that write has succeeded: the
     * bodies are then in the new file, and reading them from the old one would resurrect whatever it held.
     */
    public void bindSource(@Nullable PolarSource source) {
        this.source = source;
    }

    public @Nullable PolarSource source() {
        return this.source;
    }

    /**
     * Records that a chunk stays in the file rather than becoming part of the world.
     */
    public void markArchived(int chunkX, int chunkZ) {
        this.positions.add(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    /**
     * Puts a position back after making it live failed part way through.
     *
     * @see #take(int, int)
     */
    public void restore(int chunkX, int chunkZ) {
        this.positions.add(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    public boolean contains(int chunkX, int chunkZ) {
        return this.positions.contains(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    public @Range(from = 0, to = Integer.MAX_VALUE) int size() {
        return this.positions.size();
    }

    public boolean isEmpty() {
        return this.positions.isEmpty();
    }

    /**
     * What this archive is holding on the heap, for reporting. The chunk bodies are not on it.
     */
    public long retainedBytes() {
        return (long) this.positions.size() * BYTES_PER_POSITION;
    }

    /**
     * Reads a chunk's body out of the file and stops holding its position.
     * <p>
     * Giving it up is the point: a chunk that has been made live belongs to the world from then on, and
     * leaving the position here would have saving write it twice. A read that fails puts the position back
     * before throwing, so the chunk is never dropped on the floor.
     *
     * @return the body, or null if this archive does not hold that chunk
     * @throws IllegalStateException if the chunk is held but could not be read back
     */
    public byte @Nullable [] take(int chunkX, int chunkZ) {
        long position = CoordConversion.chunkIndex(chunkX, chunkZ);
        if (!this.positions.remove(position)) return null;

        try {
            byte[][] body = new byte[1][];
            this.readBodies(Set.of(position), (_, _, buffer, offset, length) -> {
                byte[] copy = new byte[length];
                buffer.getBytes(offset, copy);
                body[0] = copy;
            });
            return body[0];
        } catch (RuntimeException | Error failure) {
            this.positions.add(position);
            throw failure;
        }
    }

    /**
     * The positions this archive holds right now.
     * <p>
     * Saving has to write a chunk count before the chunks themselves, so it needs a set that cannot change
     * underneath it: a chunk made live half way through would leave the file claiming more chunks than it
     * contains, which is a corrupt world.
     */
    public @NotNull Snapshot snapshot() {
        return new Snapshot(this, Set.copyOf(this.positions));
    }

    /**
     * Hands every wanted chunk's body to {@code out}, in one pass over the file.
     * <p>
     * One pass because the alternative, reading the file once per chunk, is a thousand decompressions of the
     * same megabytes per save.
     *
     * @throws IllegalStateException if the source is missing, unreadable, or does not hold every wanted chunk
     */
    void readBodies(@NotNull @Unmodifiable Set<Long> wanted, @NotNull BodyReader out) {
        if (wanted.isEmpty()) return;

        PolarSource polarSource = this.source;
        if (polarSource == null) {
            throw new IllegalStateException(
                    "Archive holds " + wanted.size() + " chunks but has no source to read them from");
        }

        byte[] data;
        try {
            data = polarSource.readBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read the archive source of " + wanted.size() + " chunks",
                    exception);
        }
        if (data == null || data.length == 0) {
            throw new IllegalStateException("Archive source is empty, but it holds " + wanted.size() + " chunks");
        }

        PolarContentReader.Content content = PolarContentReader.open(data, PolarDataConverter.DEFAULT);
        ByteBuf body = content.body();
        Set<Long> pending = new HashSet<>(wanted);

        for (int i = 0; i < content.chunkCount() && !pending.isEmpty(); i++) {
            int chunkX = getVarInt(body);
            int chunkZ = getVarInt(body);

            int start = body.readerIndex();
            PolarReader.skipChunkBody(content.version(), body, content.sectionCount());
            if (!pending.remove(CoordConversion.chunkIndex(chunkX, chunkZ))) continue;

            out.read(chunkX, chunkZ, body, start, body.readerIndex() - start);
        }

        // Never write a file that is missing chunks the world is still counting on. Throwing here leaves
        // whatever is already on disk as the newest complete copy of the world.
        if (!pending.isEmpty()) {
            throw new IllegalStateException(pending.size() + " archived chunks are missing from the source file "
                    + "(" + describe(pending) + "); refusing to save a world without them");
        }
    }

    private static @NotNull String describe(@NotNull Set<Long> positions) {
        StringBuilder described = new StringBuilder();
        int shown = 0;
        for (long position : positions) {
            if (shown == 4) {
                described.append(", ...");
                break;
            }
            if (shown > 0) described.append(", ");
            described.append('[').append(CoordConversion.chunkX(position))
                    .append(',').append(CoordConversion.chunkZ(position)).append(']');
            shown++;
        }
        return described.toString();
    }

    /**
     * Receives a chunk body as a window into the decompressed file, rather than as a copy of it.
     */
    @FunctionalInterface
    interface BodyReader {
        void read(int chunkX, int chunkZ, @NotNull ByteBuf buffer, int offset, int length);
    }

    /**
     * The positions an archive held at one moment, and the archive to read them from.
     */
    public record Snapshot(@NotNull PolarChunkArchive archive, @NotNull @Unmodifiable Set<Long> positions) {

        public static final Snapshot EMPTY = new Snapshot(new PolarChunkArchive(), Set.of());

        public boolean isEmpty() {
            return this.positions.isEmpty();
        }

        void readBodies(@NotNull @Unmodifiable Set<Long> wanted, @NotNull BodyReader out) {
            this.archive.readBodies(wanted, out);
        }
    }
}
