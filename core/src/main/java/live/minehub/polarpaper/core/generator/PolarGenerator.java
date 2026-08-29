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

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class PolarGenerator extends ChunkGenerator {
    private volatile Config config;
    private volatile @Nullable PolarSource source;
    private final PolarWorldAccess worldAccess;
    private final PolarChunkArchive chunkArchive = new PolarChunkArchive();
    private final Set<Long> placeholderChunks = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> gameruleDecisions = new ConcurrentHashMap<>();

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
        this.gameruleDecisions.clear();
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

    public @NotNull PolarChunkArchive getChunkArchive() {
        return this.chunkArchive;
    }

    public void markPlaceholderChunk(int chunkX, int chunkZ) {
        this.placeholderChunks.add(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    public void clearPlaceholderChunk(int chunkX, int chunkZ) {
        this.placeholderChunks.remove(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    public @NotNull @Unmodifiable Set<Long> getPlaceholderChunks() {
        return Set.copyOf(this.placeholderChunks);
    }

    public @NotNull BlockSelector getWorldBlockSelector() {
        Location spawn = config.spawn();
        return BlockSelector.horizontalCircle(spawn.getBlockX(), spawn.getBlockZ(), config.worldRadiusBlocks());
    }

    public byte @NotNull [] getUserData() {
        PolarWorld polarWorld = getPolarWorld();
        return polarWorld == null ? new byte[0] : polarWorld.userData();
    }

    public abstract @Nullable PolarWorld getPolarWorld();

    public abstract Component getInfoComponent(World world);

    @Override
    public @Nullable Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {

        Location spawn = getConfig().spawn().clone();
        spawn.setWorld(world);
        return spawn;
    }

    public boolean gameruleEnabled(@NotNull String key, boolean fallback) {
        return this.gameruleDecisions.computeIfAbsent(key,
                _ -> {
                    Object value = this.getConfig().gamerules().get(key);
                    return value instanceof Boolean enabled ? enabled : fallback;
                });
    }

    public static @Nullable PolarGenerator fromWorld(World world) {
        if (world == null) return null;
        ChunkGenerator generator = world.getGenerator();
        if (generator instanceof PolarGenerator polarGenerator) return polarGenerator;
        return null;
    }
}
