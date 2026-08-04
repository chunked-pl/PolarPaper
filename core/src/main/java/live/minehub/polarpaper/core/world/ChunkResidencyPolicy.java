package live.minehub.polarpaper.core.world;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Decides which of a world's chunks are live in it, and which are only kept.
 * <p>
 * This is a separate question from {@link BlockSelector}, which decides whether a chunk belongs to the world
 * at all. A chunk the selector rejects is thrown away; a chunk this rejects is still part of the world and
 * still saved, it simply waits in a {@link PolarChunkArchive} until something asks for it. That is what lets
 * a world cost memory in proportion to the part of it that is actually in use.
 */
@FunctionalInterface
public interface ChunkResidencyPolicy {

    /** Every chunk of the world is live as soon as it loads, which is how a world behaves by default. */
    @NotNull ChunkResidencyPolicy LOAD_EVERYTHING = (_, _) -> true;

    boolean shouldLoad(int chunkX, int chunkZ);

    /**
     * Loads a chunk only if both policies want it loaded.
     */
    @Contract(pure = true)
    static @NotNull ChunkResidencyPolicy both(@NotNull ChunkResidencyPolicy first, @NotNull ChunkResidencyPolicy second) {
        if (first == LOAD_EVERYTHING) return second;
        if (second == LOAD_EVERYTHING) return first;
        return (chunkX, chunkZ) -> first.shouldLoad(chunkX, chunkZ) && second.shouldLoad(chunkX, chunkZ);
    }
}
