package live.minehub.polarpaper.core.generator;

import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.util.CoordConversion;
import live.minehub.polarpaper.core.world.BlockSelector;
import live.minehub.polarpaper.core.world.ChunkResidencyPolicy;
import live.minehub.polarpaper.core.world.PolarChunkArchive;
import live.minehub.polarpaper.core.world.PolarWorld;
import live.minehub.polarpaper.core.world.PolarWorldAccess;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class PolarGenerator extends ChunkGenerator {
    private Config config;
    private @Nullable PolarSource source;
    private final PolarWorldAccess worldAccess;
    private final PolarChunkArchive chunkArchive = new PolarChunkArchive();
    private final Set<Long> placeholderChunks = ConcurrentHashMap.newKeySet();

    public PolarGenerator(Config config, @Nullable PolarSource source, PolarWorldAccess worldAccess) {
        this.config = config;
        this.source = source;
        this.worldAccess = worldAccess;
    }

    public Config getConfig() {
        return this.config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public @Nullable PolarSource getSource() {
        return source;
    }

    public void setSource(@Nullable PolarSource source) {
        this.source = source;
    }

    public PolarWorldAccess getWorldAccess() {
        return this.worldAccess;
    }

    /**
     * The chunks of this world that were not made live, kept so that saving does not lose them.
     * <p>
     * Empty for a world that loaded all of its chunks, which is how a world behaves unless it was created
     * with a {@link ChunkResidencyPolicy} that leaves some out.
     */
    public @NotNull PolarChunkArchive getChunkArchive() {
        return this.chunkArchive;
    }

    /**
     * Records that the chunk standing at a position is a stand in rather than what the world holds there.
     * <p>
     * Saving has to leave these out and write the archived chunk instead, or the world would be saved with
     * whatever was put there to look at, in place of what is actually there.
     */
    public void markPlaceholderChunk(int chunkX, int chunkZ) {
        this.placeholderChunks.add(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    /** Records that a position now holds its real chunk. */
    public void clearPlaceholderChunk(int chunkX, int chunkZ) {
        this.placeholderChunks.remove(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    /** The positions whose live chunk must not be saved, as chunk indexes. */
    public @NotNull @Unmodifiable Set<Long> getPlaceholderChunks() {
        return Set.copyOf(this.placeholderChunks);
    }

    public @NotNull BlockSelector getWorldBlockSelector() {
        Location spawn = config.spawn();
        return BlockSelector.horizontalCircle(spawn.getBlockX(), spawn.getBlockZ(), config.worldRadiusBlocks());
    }

    /**
     * The world level user data this world was loaded with, such as the schematic center.
     * <p>
     * Saving rebuilds the world from the chunks that are loaded, which knows nothing about this, so it has to
     * be carried across from here or every save would quietly drop it.
     */
    public byte @NotNull [] getUserData() {
        PolarWorld polarWorld = getPolarWorld();
        return polarWorld == null ? new byte[0] : polarWorld.userData();
    }

    public abstract @Nullable PolarWorld getPolarWorld();

    public abstract Component getInfoComponent(World world);

    @Override
    public @Nullable Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        // Copied so that callers cannot move the spawn stored in the config by mutating what they are given
        Location spawn = getConfig().spawn().clone();
        spawn.setWorld(world);
        return spawn;
    }

    public static @Nullable PolarGenerator fromWorld(World world) {
        if (world == null) return null;
        ChunkGenerator generator = world.getGenerator();
        if (generator instanceof PolarGenerator polarGenerator) return polarGenerator;
        return null;
    }
}
