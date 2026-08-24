package live.minehub.polarpaper;

import io.netty.buffer.Unpooled;
import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import live.minehub.polarpaper.core.generator.PolarStreamingGenerator;
import live.minehub.polarpaper.core.source.BytesPolarSource;
import live.minehub.polarpaper.core.source.FilePolarSource;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.util.CoordConversion;
import live.minehub.polarpaper.core.util.TaskFutures;
import live.minehub.polarpaper.core.world.*;
import live.minehub.polarpaper.nms.VersionUtil;
import live.minehub.polarpaper.util.EntitiesWorldAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("unused")
public class Polar {

    private static final Logger LOGGER = LoggerFactory.getLogger(Polar.class);

    private static final Set<NamespacedKey> LOADING_WORLDS = new CopyOnWriteArraySet<>();
    private static final Map<NamespacedKey, BukkitTask> AUTOSAVE_TASK_MAP = new ConcurrentHashMap<>();

    private static final AtomicReference<String> PENDING_CONFIG = new AtomicReference<>();
    private static final AtomicBoolean CONFIG_WRITE_SCHEDULED = new AtomicBoolean();
    private static final Object CONFIG_WRITE_LOCK = new Object();

    private Polar() {

    }

    public static FilePolarSource getDefaultFolderSource(String worldName) {
        Path pluginFolder = PolarPaper.getPlugin().getDataPath();
        Path worldsFolder = pluginFolder.resolve("worlds");
        Path path = worldsFolder.resolve(worldName + ".polar");
        return new FilePolarSource(path);
    }

    public static boolean isLoading(NamespacedKey worldKey) {
        return LOADING_WORLDS.contains(worldKey);
    }

    public static void setLoading(NamespacedKey worldKey, boolean loading) {
        if (loading) {
            LOADING_WORLDS.add(worldKey);
        } else {
            LOADING_WORLDS.remove(worldKey);
        }
    }

    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource source, @NotNull String worldName) {
        return createWorld(source, worldName, VersionUtil.getPolarFeaturesWorldAccess());
    }

    public static CompletableFuture<@Nullable World> createWorld(PolarWorld polarWorld, @NotNull String worldName) {
        return createWorld(polarWorld, worldName, VersionUtil.getPolarFeaturesWorldAccess());
    }

    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource polarSource, @NotNull String worldName, @NotNull PolarWorldAccess worldAccess) {
        FileConfiguration fileConfig = PolarPaper.getPlugin().getConfig();
        Config config = Config.readFromConfig(fileConfig, worldName);
        return createWorld(polarSource, worldName, config, worldAccess);
    }

    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarWorld polarWorld, @NotNull String worldName, @NotNull PolarWorldAccess worldAccess) {
        FileConfiguration fileConfig = PolarPaper.getPlugin().getConfig();
        Config config = Config.readFromConfig(fileConfig, worldName);
        return createWorld(polarWorld, worldName, config, worldAccess);
    }

    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource polarSource, @NotNull String worldName, @NotNull Config config) {
        return createWorld(polarSource, worldName, config, VersionUtil.getPolarFeaturesWorldAccess());
    }

    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarWorld polarWorld, @NotNull String worldName, @NotNull Config config) {
        return createWorld(polarWorld, worldName, config, VersionUtil.getPolarFeaturesWorldAccess());
    }

    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource source, @NotNull String worldName, @NotNull Config config, @NotNull PolarWorldAccess worldAccess) {
        return createWorld(source, worldName, config, worldAccess, ChunkResidencyPolicy.LOAD_EVERYTHING);
    }

    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource source, @NotNull String worldName,
                                                                 @NotNull Config config, @NotNull PolarWorldAccess worldAccess,
                                                                 @NotNull ChunkResidencyPolicy residency) {
        PolarStreamingGenerator generator = new PolarStreamingGenerator(config, source, worldAccess);
        generator.getChunkArchive().bindSource(source);
        return createWorld(generator, worldName).thenComposeAsync(world -> {
            if (world == null) return CompletableFuture.completedFuture(null);

            CompletableFuture<Void> streamed;
            try {
                byte[] worldBytes = source == null ? null : source.readBytes();
                streamed = worldBytes == null || worldBytes.length == 0
                        ? CompletableFuture.completedFuture(null)
                        : PolarStreamLoader.stream(worldBytes, world, PolarDataConverter.DEFAULT, worldAccess,
                                BlockSelector.horizontalCircle(config.spawn().getBlockX(), config.spawn().getBlockZ(),
                                        config.worldRadiusBlocks()),
                                residency, generator.getChunkArchive());
            } catch (Throwable t) {
                streamed = CompletableFuture.failedFuture(t);
            }

            return streamed.handle((_, ex) -> ex)
                    .thenCompose(ex -> finishOrAbortLoading(world, config, worldName, ex));
        });
    }

    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarWorld polarWorld, @NotNull String worldName, @NotNull Config config, @NotNull PolarWorldAccess worldAccess) {
        PolarStreamingGenerator generator = new PolarStreamingGenerator(config, null, worldAccess);
        generator.setUserData(polarWorld.userData());
        return createWorld(generator, worldName).thenComposeAsync(world -> {
            if (world == null) return CompletableFuture.completedFuture(null);
            ServerLevel level = ((CraftWorld) world).getHandle();
            BlockSelector blockSelector = BlockSelector.horizontalCircle(
                    config.spawn().getBlockX(), config.spawn().getBlockZ(), config.worldRadiusBlocks());
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            Throwable preparationFailure = null;
            for (PolarChunk chunk : polarWorld.chunks()) {
                if (!blockSelector.testChunk(chunk.x(), chunk.z())) continue;
                try {
                    NoUnloadLevelChunk levelChunk = chunk.createLevelChunk(level, blockSelector);

                    futures.add(PolarStreamLoader.prepareChunkAsync(levelChunk)
                            .thenCompose(_ -> TaskFutures.runSync(PolarPaper.getPlugin(), () -> {
                                for (PolarChunk.BlockEntity blockEntity : chunk.blockEntities()) {
                                    if (!PolarStreamLoader.isBlockEntitySelected(blockEntity, blockSelector, chunk.x(), chunk.z())) continue;
                                    PolarStreamLoader.addBlockEntity(blockEntity, levelChunk);
                                }
                                levelChunk.tryMarkSaved();
                                if (PolarStreamLoader.insertChunk(level, levelChunk)) {
                                    worldAccess.loadChunkData(world, levelChunk, chunk.userData(), blockSelector);
                                } else {
                                    LOGGER.warn("Dropped the stored chunk at {} {} in {}, the chunk system already holds that position",
                                            chunk.x(), chunk.z(), worldName);
                                }
                                return (Void) null;
                            }))
                            .whenComplete((_, ex) -> {
                                if (ex != null) LOGGER.error("Failed to stream chunk at {} {} in {}", chunk.x(), chunk.z(), worldName, ex);
                            }));
                } catch (Throwable throwable) {
                    preparationFailure = throwable;
                    break;
                }
            }

            CompletableFuture<Void> streamed = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            if (preparationFailure != null) {
                Throwable finalPreparationFailure = preparationFailure;
                streamed = streamed.handle((_, scheduledFailure) -> {
                    if (scheduledFailure != null && scheduledFailure != finalPreparationFailure) {
                        finalPreparationFailure.addSuppressed(scheduledFailure);
                    }
                    throw new java.util.concurrent.CompletionException(finalPreparationFailure);
                });
            }

            return streamed
                    .handle((_, ex) -> ex)
                    .thenCompose(ex -> finishOrAbortLoading(world, config, worldName, ex));
        });
    }

    private static void dropPlaceholderChunks(@NotNull PolarWorld polarWorld, @NotNull PolarGenerator generator) {
        for (long chunkIndex : generator.getPlaceholderChunks()) {
            polarWorld.removeChunkAt(CoordConversion.chunkX(chunkIndex), CoordConversion.chunkZ(chunkIndex));
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static CompletableFuture<Boolean> loadChunk(@NotNull World world, int chunkX, int chunkZ) {
        PolarStreamingGenerator generator = streamingGeneratorOf(world);
        if (generator == null) return CompletableFuture.completedFuture(false);

        Short version = generator.getVersion();
        Integer dataVersion = generator.getDataVersion();
        ServerLevel level = ((CraftWorld) world).getHandle();
        BlockSelector blockSelector = generator.getWorldBlockSelector();
        int sectionCount = level.getSectionsCount();

        return CompletableFuture
                .supplyAsync(() -> {
                    byte[] body = generator.getChunkArchive().claim(chunkX, chunkZ);
                    if (body == null) return null;
                    if (version == null || dataVersion == null) {
                        throw new IllegalStateException("World " + world.getKey()
                                + " has archived chunks but was never read from polar data");
                    }

                    PolarChunk chunk = PolarReader.readChunkBody(PolarDataConverter.DEFAULT, version, dataVersion,
                            Unpooled.wrappedBuffer(body), sectionCount, chunkX, chunkZ);
                    NoUnloadLevelChunk levelChunk = chunk.createLevelChunk(level, blockSelector);
                    PolarStreamLoader.primeChunk(levelChunk);
                    return new PreparedChunk(chunk, levelChunk);
                })
                .thenCompose(prepared -> prepared == null
                        ? CompletableFuture.completedFuture(false)
                        : TaskFutures.runSync(PolarPaper.getPlugin(), () -> {
                    LevelChunk existing = liveChunkAt(level, chunkX, chunkZ);
                    if (existing != null) {

                        PolarStreamLoader.replaceChunkBlocks(level, world, existing, prepared.levelChunk(),
                                prepared.chunk(), generator.getWorldAccess(), blockSelector);
                        retainChunk(world, chunkX, chunkZ);
                        generator.clearPlaceholderChunk(chunkX, chunkZ);
                        generator.getChunkArchive().release(chunkX, chunkZ);
                        return true;
                    }

                    for (PolarChunk.BlockEntity blockEntity : prepared.chunk().blockEntities()) {
                        if (!PolarStreamLoader.isBlockEntitySelected(blockEntity, blockSelector, chunkX, chunkZ)) continue;
                        PolarStreamLoader.addBlockEntity(blockEntity, prepared.levelChunk());
                    }
                    prepared.levelChunk().tryMarkSaved();
                    if (!PolarStreamLoader.insertChunk(level, prepared.levelChunk())) {
                        LOGGER.warn("Could not expand the archived chunk at {} {} in {}, the chunk system already holds that position",
                                chunkX, chunkZ, world.getKey());

                        generator.getChunkArchive().abandon(chunkX, chunkZ);
                        return false;
                    }
                    generator.getWorldAccess().loadChunkData(world, prepared.levelChunk(), prepared.chunk().userData(), blockSelector);
                    retainChunk(world, chunkX, chunkZ);
                    generator.clearPlaceholderChunk(chunkX, chunkZ);
                    generator.getChunkArchive().release(chunkX, chunkZ);
                    return true;
                }))
                .whenComplete((_, failure) -> {
                    if (failure != null) generator.getChunkArchive().abandon(chunkX, chunkZ);
                });
    }

    public static boolean isChunkArchived(@NotNull World world, int chunkX, int chunkZ) {
        PolarStreamingGenerator generator = streamingGeneratorOf(world);
        return generator != null && generator.getChunkArchive().contains(chunkX, chunkZ);
    }

    @SuppressWarnings("UnusedReturnValue")
    public static CompletableFuture<Boolean> loadEmptyChunk(@NotNull World world, int chunkX, int chunkZ) {
        PolarStreamingGenerator generator = streamingGeneratorOf(world);
        if (generator == null) return CompletableFuture.completedFuture(false);

        ServerLevel level = ((CraftWorld) world).getHandle();
        if (liveChunkAt(level, chunkX, chunkZ) != null) return CompletableFuture.completedFuture(false);

        BlockSelector blockSelector = generator.getWorldBlockSelector();
        PolarChunk chunk = new PolarChunk(chunkX, chunkZ, level.getSectionsCount());
        NoUnloadLevelChunk levelChunk = chunk.createLevelChunk(level, blockSelector);

        return PolarStreamLoader.prepareChunkAsync(levelChunk)
                .thenCompose(_ -> TaskFutures.runSync(PolarPaper.getPlugin(), () -> {
                    if (liveChunkAt(level, chunkX, chunkZ) != null) return false;

                    levelChunk.tryMarkSaved();

                    PolarStreamLoader.insertChunk(level, levelChunk);
                    retainChunk(world, chunkX, chunkZ);

                    generator.markPlaceholderChunk(chunkX, chunkZ);
                    return true;
                }));
    }

    private static @NotNull java.util.Set<Long> positionsOf(@NotNull PolarWorld polarWorld) {
        java.util.Set<Long> positions = new java.util.HashSet<>();
        for (PolarChunk chunk : polarWorld.chunks()) {
            positions.add(live.minehub.polarpaper.core.util.CoordConversion.chunkIndex(chunk.x(), chunk.z()));
        }
        return positions;
    }

    private static @Nullable PolarStreamingGenerator streamingGeneratorOf(@NotNull World world) {
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        return generator instanceof PolarStreamingGenerator streaming ? streaming : null;
    }

    private static @Nullable LevelChunk liveChunkAt(@NotNull ServerLevel level, int chunkX, int chunkZ) {
        NewChunkHolder holder = level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        return holder != null && holder.getCurrentChunk() instanceof LevelChunk chunk ? chunk : null;
    }

    private static void retainChunk(@NotNull World world, int chunkX, int chunkZ) {
        PolarStreamLoader.retainChunk(PolarPaper.getPlugin(), world, chunkX, chunkZ);
    }

    private record PreparedChunk(PolarChunk chunk, NoUnloadLevelChunk levelChunk) {
    }

    private static void finishLoading(@NotNull World world, @NotNull Config config) {
        setLoading(world.getKey(), false);
        startAutoSaveTask(world, config);
    }

    private static CompletableFuture<@Nullable World> finishOrAbortLoading(@NotNull World world, @NotNull Config config,
                                                                           @NotNull String worldName, @Nullable Throwable failure) {
        if (failure == null) {
            finishLoading(world, config);
            return CompletableFuture.completedFuture(world);
        }

        setLoading(world.getKey(), false);
        stopAutoSaveTask(world.getKey());
        LOGGER.error("Failed to load world {}, unloading the partial world", worldName, failure);

        return TaskFutures.runSync(PolarPaper.getPlugin(), () -> Bukkit.unloadWorld(world, false))
                .handle((unloaded, unloadFailure) -> {
                    if (unloadFailure != null) {
                        LOGGER.error("Failed to unload partial world {}", worldName, unloadFailure);
                    } else if (!unloaded) {
                        LOGGER.error("Failed to unload partial world {} because it is still in use", worldName);
                    }
                    return null;
                });
    }

    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarGenerator generator, @NotNull String worldName) {
        worldName = worldName.toLowerCase().replace(" ", "_");

        NamespacedKey worldKey = NamespacedKey.fromString(worldName, PolarPaper.getPlugin());
        if (worldKey == null) {
            LOGGER.warn("Invalid world name '{}'", worldName);
            return CompletableFuture.completedFuture(null);
        }

        if (Bukkit.getWorld(worldKey) != null) {
            LOGGER.warn("A world with the name '{}' already exists, skipping.", worldName);
            return CompletableFuture.completedFuture(null);
        }

        Config config = generator.getConfig();

        WorldCreator worldCreator = WorldCreator.ofKey(worldKey)
                .type(config.worldType())
                .environment(config.environment())
                .generator(generator);

        return VersionUtil.createNoSaveLevel(worldCreator, config.spawn(), config.difficulty(), config.gamerules(), config.time())
                .whenComplete((world, ex) -> {
                    if (ex != null || world == null) {

                        setLoading(worldKey, false);
                        if (ex == null) {
                            LOGGER.error("An error occurred loading polar world '" + worldKey.getKey() + "', skipping.");
                        } else {
                            LOGGER.error("An error occurred loading polar world '" + worldKey.getKey() + "', skipping.", ex);
                        }
                        return;
                    }

                    world.setAutoSave(false);
                });
    }

    public static void stopAutoSaveTask(NamespacedKey worldKey) {

        BukkitTask prevTask = AUTOSAVE_TASK_MAP.remove(worldKey);
        if (prevTask != null) prevTask.cancel();
    }

    public static void startAutoSaveTask(World world, Config config) {
        startAutoSaveTask(world, config.autoSaveIntervalTicks(), config.announceAutosave());
    }

    public static void startAutoSaveTask(World world, int autosaveIntervalTicks, boolean announceAutosave) {
        stopAutoSaveTask(world.getKey());

        if (autosaveIntervalTicks == -1) return;
        if (autosaveIntervalTicks < 1) {
            LOGGER.warn("Autosave for '{}' is disabled because its interval is {} (expected -1 or a positive value)",
                    world.getKey().getKey(), autosaveIntervalTicks);
            return;
        }

        AtomicBoolean saveInProgress = new AtomicBoolean();

        BukkitTask autosaveTask = Bukkit.getScheduler().runTaskTimer(PolarPaper.getPlugin(), () -> {
            if (!saveInProgress.compareAndSet(false, true)) {
                LOGGER.warn("Skipping autosave for '{}' because the previous save is still running", world.getKey().getKey());
                return;
            }

            long before = System.nanoTime();
            String savingMsg = String.format("Autosaving '%s'...", world.getKey().getKey());
            LOGGER.info(savingMsg);
            if (announceAutosave) for (Player plr : Bukkit.getOnlinePlayers()) {
                if (!plr.hasPermission("polar.notifications")) continue;
                plr.sendMessage(Component.text(savingMsg, NamedTextColor.AQUA));
            }

            try {
                updateConfig(world, world.getKey().getKey());
                saveWorld(world)
                        .whenComplete((_, e) -> {
                            saveInProgress.set(false);
                            if (e != null) {

                                String errorMsg = String.format(
                                        "Failed to save '%s', its previous file is untouched. Please check logs for error",
                                        world.getKey().getKey());
                                LOGGER.error(errorMsg, e);
                                for (Player plr : Bukkit.getOnlinePlayers()) {
                                    if (!plr.hasPermission("polar.notifications")) continue;
                                    plr.sendMessage(Component.text(errorMsg, NamedTextColor.RED));
                                }
                                return;
                            }

                            int ms = (int) ((System.nanoTime() - before) / 1_000_000);
                            String savedMsg = String.format("Saved '%s' in %sms", world.getKey().getKey(), ms);
                            LOGGER.info(savedMsg);
                            if (announceAutosave) for (Player plr : Bukkit.getOnlinePlayers()) {
                                if (!plr.hasPermission("polar.notifications")) continue;
                                plr.sendMessage(Component.text(savedMsg, NamedTextColor.AQUA));
                            }
                        });
            } catch (Throwable throwable) {
                saveInProgress.set(false);
                LOGGER.error("Failed to start autosave for '{}'", world.getKey().getKey(), throwable);
            }
        }, autosaveIntervalTicks, autosaveIntervalTicks);

        AUTOSAVE_TASK_MAP.put(world.getKey(), autosaveTask);
    }

    @SuppressWarnings("unchecked")
    private static <T> void setGameRule(World world, GameRule<?> rule, Object value) {
        world.setGameRule((GameRule<T>) rule, (T)value);
    }

    public static Config updateConfig(World world, String worldName) {
        FileConfiguration fileConfig = PolarPaper.getPlugin().getConfig();
        Config defaultConfig = Config.getDefaultConfig(fileConfig);

        Config storedConfig = Config.readFromConfig(fileConfig, worldName, defaultConfig.toBuilder());
        Config newConfig = storedConfig.toBuilder().fromWorld(world).build();

        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator != null) generator.setConfig(newConfig);

        if (Config.isInConfig(fileConfig, worldName) && newConfig.equals(storedConfig)) return newConfig;

        Config.applyToConfig(fileConfig, worldName, newConfig);
        saveConfigFile(fileConfig.saveToString());

        return newConfig;
    }

    private static void saveConfigFile(String contents) {
        PENDING_CONFIG.set(contents);

        if (!PolarPaper.getPlugin().isEnabled()) {
            writePendingConfig();
            return;
        }

        if (!CONFIG_WRITE_SCHEDULED.compareAndSet(false, true)) return;
        try {
            Bukkit.getScheduler().runTaskAsynchronously(PolarPaper.getPlugin(), () -> {
                CONFIG_WRITE_SCHEDULED.set(false);
                writePendingConfig();
            });
        } catch (Throwable throwable) {

            CONFIG_WRITE_SCHEDULED.set(false);
            writePendingConfig();
        }
    }

    public static void flushPendingConfig() {
        writePendingConfig();
    }

    private static void writePendingConfig() {

        synchronized (CONFIG_WRITE_LOCK) {
            String contents = PENDING_CONFIG.getAndSet(null);
            if (contents == null) return;

            try {
                writeAtomically(PolarPaper.getConfigPath(), contents);
            } catch (Exception e) {
                LOGGER.error("Failed to write the config file", e);
            }
        }
    }

    private static void writeAtomically(Path path, String contents) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("Config file must have a parent directory: " + path);

        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    public static void reloadConfig(World world) {
        PolarPaper.getPlugin().reloadConfig();

        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null) return;

        Config config = Config.readFromConfig(PolarPaper.getPlugin().getConfig(), world);

        generator.setConfig(config);
        Location spawn = config.spawn().clone();
        spawn.setWorld(world);
        world.setSpawnLocation(spawn);

        world.setDifficulty(org.bukkit.Difficulty.valueOf(config.difficulty().name()));

        for (Map.Entry<String, Object> gamerule : config.gamerules().entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(gamerule.getKey());
            if (key == null) continue;
            GameRule<?> rule = org.bukkit.Registry.GAME_RULE.get(key);
            if (rule == null) {
                LOGGER.warn("Invalid gamerule: {}", key.asMinimalString());
                continue;
            }
            setGameRule(world, rule, gamerule.getValue());
        }

        Polar.startAutoSaveTask(world, config);
    }

    public static CompletableFuture<Void> saveWorld(World world) {
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null) return CompletableFuture.completedFuture(null);
        PolarSource source = generator.getSource();
        if (source == null) return CompletableFuture.completedFuture(null);
        return saveWorld(world, source);
    }

    @SuppressWarnings("unused")
    public static CompletableFuture<Void> saveWorld(World world, PolarSource polarSource) {
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null) return CompletableFuture.completedFuture(null);
        Collection<PolarChunk> extraChunks = generator.getPolarWorld() == null ? List.of() : generator.getPolarWorld().chunks();
        return saveWorld(world, extraChunks, polarSource, generator.getWorldAccess(), BlockSelector.ALL, generator.getConfig());
    }

    public static void saveWorldSynchronously(World world) throws Exception {
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null || generator.getSource() == null) return;
        if (Polar.isLoading(world.getKey())) {
            throw new IllegalStateException(world.getKey() + " is still loading");
        }

        Config config = generator.getConfig();
        BlockSelector blockSelector = generator.getWorldBlockSelector();
        PolarChunkArchive.Snapshot archiveSnapshot = generator.getChunkArchive().snapshot();
        Collection<PolarChunk> extraChunks = generator.getPolarWorld() == null
                ? List.of()
                : generator.getPolarWorld().chunks();
        PolarWorld polarWorld = PolarWorld.convertSynchronously(
                world, generator.getWorldAccess(), blockSelector, config, extraChunks);
        polarWorld.userData(generator.getUserData());
        dropPlaceholderChunks(polarWorld, generator);
        PolarChunkArchive.Snapshot keptSnapshot =
                generator.getChunkArchive().snapshotIncluding(archiveSnapshot, positionsOf(polarWorld));
        PolarSource source = generator.getSource();
        source.saveBytes(PolarWriter.write(polarWorld, PolarDataConverter.DEFAULT, keptSnapshot));
        generator.getChunkArchive().bindSource(source);
    }

    public static CompletableFuture<Void> saveWorld(World world, Collection<PolarChunk> extraChunks, PolarSource polarSource, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, Config config) {
        if (Polar.isLoading(world.getKey())) return CompletableFuture.failedFuture(new IllegalStateException(world.getKey() + " is still loading"));

        BlockSelector radiusSelector = BlockSelector.horizontalCircle(
                config.spawn().getBlockX(), config.spawn().getBlockZ(), config.worldRadiusBlocks());
        BlockSelector boundedSelector = BlockSelector.intersection(blockSelector, radiusSelector);

        PolarGenerator generator = PolarGenerator.fromWorld(world);
        byte[] worldUserData = generator == null ? new byte[0] : generator.getUserData();
        PolarChunkArchive.Snapshot archiveSnapshot = generator == null
                ? PolarChunkArchive.Snapshot.EMPTY
                : generator.getChunkArchive().snapshot();

        CompletableFuture<PolarWorld> future;
        try {
            future = PolarWorld.convert(world, polarWorldAccess, boundedSelector, config, extraChunks, false);
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }

        return future.thenAcceptAsync(newPolarWorld -> {
            newPolarWorld.userData(worldUserData);
            if (generator != null) dropPlaceholderChunks(newPolarWorld, generator);
            PolarChunkArchive.Snapshot keptSnapshot = generator == null
                    ? archiveSnapshot
                    : generator.getChunkArchive().snapshotIncluding(archiveSnapshot, positionsOf(newPolarWorld));
            byte[] worldBytes = PolarWriter.write(newPolarWorld, PolarDataConverter.DEFAULT, keptSnapshot);
            try {
                polarSource.saveBytes(worldBytes);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (generator != null) generator.getChunkArchive().bindSource(polarSource);
        });
    }

}
