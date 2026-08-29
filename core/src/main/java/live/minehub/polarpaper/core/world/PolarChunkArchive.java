package live.minehub.polarpaper.core.world;

import io.netty.buffer.ByteBuf;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.util.CoordConversion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.getVarInt;

public final class PolarChunkArchive {

    private static final long BYTES_PER_POSITION = 48L;
    private static final int POSITIONS_IN_MESSAGE = 4;

    private final Set<Long> positions = ConcurrentHashMap.newKeySet();
    private final Set<Long> claimed = ConcurrentHashMap.newKeySet();
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

    public byte @Nullable [] claim(int chunkX, int chunkZ) {
        long position = CoordConversion.chunkIndex(chunkX, chunkZ);
        if (!this.positions.contains(position)) return null;
        if (!this.claimed.add(position)) return null;
        if (!this.positions.contains(position)) {
            this.claimed.remove(position);
            return null;
        }

        try {
            byte[][] body = new byte[1][];
            this.readBodies(Set.of(position), (_, _, buffer, offset, length) -> {
                byte[] copy = new byte[length];
                buffer.getBytes(offset, copy);
                body[0] = copy;
            });
            return body[0];
        } catch (RuntimeException | Error failure) {
            this.claimed.remove(position);
            throw failure;
        }
    }

    public void release(int chunkX, int chunkZ) {
        long position = CoordConversion.chunkIndex(chunkX, chunkZ);
        this.positions.remove(position);
        this.claimed.remove(position);
    }

    public void abandon(int chunkX, int chunkZ) {
        this.claimed.remove(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    public @NotNull Snapshot snapshot() {
        return new Snapshot(this, Set.copyOf(this.positions));
    }

    private @Nullable SourceIndex buildSourceIndex() {
        PolarContentReader.Content content = this.openSource();
        if (content == null) return null;

        ByteBuf body = content.body();
        Map<Long, int[]> slices = HashMap.newHashMap(content.chunkCount());
        for (int i = 0; i < content.chunkCount(); i++) {
            int chunkX = getVarInt(body);
            int chunkZ = getVarInt(body);

            int start = body.readerIndex();
            PolarReader.skipChunkBody(content.version(), body, content.sectionCount());
            slices.put(CoordConversion.chunkIndex(chunkX, chunkZ), new int[] {start, body.readerIndex() - start});
        }
        return new SourceIndex(body, slices);
    }

    private @Nullable PolarContentReader.Content openSource() {
        PolarSource polarSource = this.source;
        if (polarSource == null) return null;

        byte[] data;
        try {
            data = polarSource.readBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read the archive source", exception);
        }
        if (data == null || data.length == 0) return null;

        return PolarContentReader.open(data, PolarDataConverter.DEFAULT);
    }

    void readBodies(@NotNull @Unmodifiable Set<Long> wanted, @NotNull BodyReader out) {
        if (wanted.isEmpty()) return;

        SourceIndex index = this.buildSourceIndex();
        if (index == null) {
            throw new IllegalStateException(
                    "Archive holds " + wanted.size() + " chunks but has no source to read them from");
        }
        index.readBodies(wanted, out);
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

    private record SourceIndex(@NotNull ByteBuf content, @NotNull Map<Long, int[]> slices) {

        void readBodies(@NotNull @Unmodifiable Set<Long> wanted, @NotNull BodyReader out) {
            Set<Long> missing = null;
            for (long position : wanted) {
                int[] slice = this.slices.get(position);
                if (slice == null) {
                    if (missing == null) missing = new HashSet<>();
                    missing.add(position);
                    continue;
                }
                out.read(CoordConversion.chunkX(position), CoordConversion.chunkZ(position),
                        this.content, slice[0], slice[1]);
            }

            if (missing != null) {
                throw new IllegalStateException(missing.size() + " archived chunks are missing from the source file "
                        + "(" + describe(missing) + "); refusing to save a world without them");
            }
        }
    }

    public record Snapshot(@NotNull PolarChunkArchive archive, @NotNull @Unmodifiable Set<Long> positions) {

        public static final Snapshot EMPTY = new Snapshot(new PolarChunkArchive(), Set.of());

        public boolean isEmpty() {
            return this.positions.isEmpty();
        }

        void readBodies(@NotNull @Unmodifiable Set<Long> wanted, @NotNull BodyReader out) {
            if (wanted.isEmpty()) return;
            this.archive.readBodies(wanted, out);
        }
    }
}
