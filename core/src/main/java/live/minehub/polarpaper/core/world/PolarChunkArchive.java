package live.minehub.polarpaper.core.world;

import io.netty.buffer.ByteBuf;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.util.CoordConversion;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.getVarInt;

public final class PolarChunkArchive {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolarChunkArchive.class);

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

    /**
     * Reads the body of an archived chunk without giving up the archive's claim on it.
     * <p>
     * The position stays archived, so a save that happens while the caller is still expanding the chunk
     * writes the archived body and the chunk survives. The caller hands the position over with
     * {@link #release} at the moment the chunk becomes live, or drops its claim with {@link #abandon} if
     * making it live failed. Only one caller at a time holds a claim on a position.
     *
     * @return the chunk body, or null if the position is not archived or is already claimed
     */
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

    /**
     * Drops a claimed position, because the chunk it holds is now live in the world.
     * <p>
     * Must run in the same unit of work that makes the chunk live: between this call and the chunk being
     * visible, the position is in neither the archive nor the world, and a save landing there would write
     * the world without it.
     */
    public void release(int chunkX, int chunkZ) {
        long position = CoordConversion.chunkIndex(chunkX, chunkZ);
        this.positions.remove(position);
        this.claimed.remove(position);
    }

    /**
     * Gives up a claim without making the chunk live, leaving the position archived.
     */
    public void abandon(int chunkX, int chunkZ) {
        this.claimed.remove(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    public @NotNull Snapshot snapshot() {
        return new Snapshot(this, Set.copyOf(this.positions));
    }

    /**
     * Widens a snapshot with the positions the file holds that nothing else is about to write.
     * <p>
     * Every chunk in the file is meant to be either live in the world or archived. A chunk that left the
     * archive to become live and then stopped being live sits in neither, and saving would drop it: the
     * position would be gone from the file and come back as air. Rather than trust that a live chunk stays
     * live, this takes such positions back into the archive for the save, so the file keeps what it had.
     *
     * @param snapshot the archive positions already going into the save
     * @param covered the positions the save writes on its own, live chunks included
     * @return the snapshot, widened by whatever the file holds beyond those two sets
     */
    public @NotNull Snapshot snapshotIncluding(@NotNull Snapshot snapshot, @NotNull Set<Long> covered) {
        Set<Long> orphans;
        try {
            orphans = new HashSet<>(this.positionsInSource());
        } catch (RuntimeException | Error failure) {
            LOGGER.error("Could not list the chunks of the source file; saving without recovering any", failure);
            return snapshot;
        }

        orphans.removeAll(covered);
        orphans.removeAll(snapshot.positions());
        if (orphans.isEmpty()) return snapshot;

        LOGGER.warn("Recovering {} chunk(s) that the world no longer holds: {}", orphans.size(), describe(orphans));
        this.positions.addAll(orphans);
        Set<Long> widened = new HashSet<>(snapshot.positions());
        widened.addAll(orphans);
        return new Snapshot(this, Set.copyOf(widened));
    }

    private @NotNull Set<Long> positionsInSource() {
        PolarSource polarSource = this.source;
        if (polarSource == null) return Set.of();

        byte[] data;
        try {
            data = polarSource.readBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read the source file to list its chunks", exception);
        }
        if (data == null || data.length == 0) return Set.of();

        PolarContentReader.Content content = PolarContentReader.open(data, PolarDataConverter.DEFAULT);
        ByteBuf body = content.body();
        Set<Long> positions = new HashSet<>(content.chunkCount());
        for (int i = 0; i < content.chunkCount(); i++) {
            int chunkX = getVarInt(body);
            int chunkZ = getVarInt(body);
            PolarReader.skipChunkBody(content.version(), body, content.sectionCount());
            positions.add(CoordConversion.chunkIndex(chunkX, chunkZ));
        }
        return positions;
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
