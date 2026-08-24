package live.minehub.polarpaper.core.world;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface ChunkResidencyPolicy {

    @NotNull ChunkResidencyPolicy LOAD_EVERYTHING = (_, _) -> true;

    boolean shouldLoad(int chunkX, int chunkZ);

    @Contract(pure = true)
    static @NotNull ChunkResidencyPolicy both(@NotNull ChunkResidencyPolicy first, @NotNull ChunkResidencyPolicy second) {
        if (first == LOAD_EVERYTHING) return second;
        if (second == LOAD_EVERYTHING) return first;
        return (chunkX, chunkZ) -> first.shouldLoad(chunkX, chunkZ) && second.shouldLoad(chunkX, chunkZ);
    }
}
