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

public final class PolarChunkArchive {

    private static final long BYTES_PER_POSITION = 48L;
    private static final int POSITIONS_IN_MESSAGE = 4;

    private final Set<Long> positions = ConcurrentHashMap.newKeySet();
    private volatile @Nullable PolarSource source;

    public void bindSource(@Nullable PolarSource source) {
        this.source = source;
    }

    public @Nullable PolarSource source() {
        return this.source;
    }

    public void markArchived(int chunkX, int chunkZ) {
        this.positions.add(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

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

    public long retainedBytes() {
        return (long) this.positions.size() * BYTES_PER_POSITION;
    }

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

    public @NotNull Snapshot snapshot() {
        return new Snapshot(this, Set.copyOf(this.positions));
    }

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

        if (!pending.isEmpty()) {
            throw new IllegalStateException(pending.size() + " archived chunks are missing from the source file "
                    + "(" + describe(pending) + "); refusing to save a world without them");
        }
    }

    private static @NotNull String describe(@NotNull Set<Long> positions) {
        StringBuilder described = new StringBuilder();
        int shown = 0;
        for (long position : positions) {
            if (shown == POSITIONS_IN_MESSAGE) {
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

    @FunctionalInterface
    interface BodyReader {
        void read(int chunkX, int chunkZ, @NotNull ByteBuf buffer, int offset, int length);
    }

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
