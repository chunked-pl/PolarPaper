package live.minehub.polarpaper;

import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import live.minehub.polarpaper.core.generator.PolarStreamingGenerator;
import live.minehub.polarpaper.core.source.BytesPolarSource;
import live.minehub.polarpaper.core.source.FilePolarSource;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.util.TaskFutures;
import live.minehub.polarpaper.core.world.*;
import live.minehub.polarpaper.nms.VersionUtil;
import live.minehub.polarpaper.util.EntitiesWorldAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
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

    /** The config file as it should next be written, or null once whoever is writing has taken it. */
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

    /**
     * Load a polar world using the source defined in the config
     *
     * @param worldName The name of the world to load
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see Polar#getDefaultFolderSource(String)
     */
    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource source, @NotNull String worldName) {
        return createWorld(source, worldName, VersionUtil.getPolarFeaturesWorldAccess());
    }

    /**
     * Load a polar world with config read from config.yml and with the default PolarWorldAccess
     *
     * @param worldName The name for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     */
    public static CompletableFuture<@Nullable World> createWorld(PolarWorld polarWorld, @NotNull String worldName) {
        return createWorld(polarWorld, worldName, VersionUtil.getPolarFeaturesWorldAccess());
    }

    /**
     * Load a polar world with config read from config.yml
     *
     * @param polarSource The source to load the polar world from
     * @param worldName The name for the polar world
     * @param worldAccess Describes how userdata should be handled (default PolarWorldAccess.POLAR_PAPER_FEATURES)
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see Polar#getDefaultFolderSource(String)
     * @see BytesPolarSource
     * @see EntitiesWorldAccess
     */
    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource polarSource, @NotNull String worldName, @NotNull PolarWorldAccess worldAccess) {
        FileConfiguration fileConfig = PolarPaper.getPlugin().getConfig();
        Config config = Config.readFromConfig(fileConfig, worldName); // If world not in config, use defaults
        return createWorld(polarSource, worldName, config, worldAccess);
    }

    /**
     * Creates a polar world with config read from config.yml
     *
     * @param worldName The name for the polar world
     * @param worldAccess Describes how userdata should be handled (default PolarWorldAccess.POLAR_PAPER_FEATURES)
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see EntitiesWorldAccess
     */
    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarWorld polarWorld, @NotNull String worldName, @NotNull PolarWorldAccess worldAccess) {
        FileConfiguration fileConfig = PolarPaper.getPlugin().getConfig();
        Config config = Config.readFromConfig(fileConfig, worldName); // If world not in config, use defaults
        return createWorld(polarWorld, worldName, config, worldAccess);
    }

    /**
     * Creates a polar world with the default PolarWorldAccess
     *
     * @param polarSource The source to load the polar world from
     * @param worldName The name for the polar world
     * @param config Custom config for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see Polar#getDefaultFolderSource(String)
     * @see BytesPolarSource
     */
    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource polarSource, @NotNull String worldName, @NotNull Config config) {
        return createWorld(polarSource, worldName, config, VersionUtil.getPolarFeaturesWorldAccess());
    }

    /**
     * Creates a polar world with the default PolarWorldAccess
     *
     * @param worldName The name for the polar world
     * @param config Custom config for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     */
    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarWorld polarWorld, @NotNull String worldName, @NotNull Config config) {
        return createWorld(polarWorld, worldName, config, VersionUtil.getPolarFeaturesWorldAccess());
    }

    /**
     * Creates a polar world
     *
     * @param source The source to load the polar world from
     * @param worldName The name for the polar world
     * @param config Custom config for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see Polar#getDefaultFolderSource(String)
     * @see BytesPolarSource
     */
    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource source, @NotNull String worldName, @NotNull Config config, @NotNull PolarWorldAccess worldAccess) {
        byte[] worldBytes;
        try {
            worldBytes = source == null ? null : source.readBytes();
        } catch (Exception e) {
            LOGGER.error("Failed to load world " + worldName, e);
            return CompletableFuture.failedFuture(e);
        }

        return createWorld(new PolarStreamingGenerator(config, source, worldAccess), worldName).thenComposeAsync(world -> {
            if (world == null) return CompletableFuture.completedFuture(null);

            // stream() validates the header eagerly, so it can fail before it ever returns a future
            CompletableFuture<Void> streamed;
            try {
                streamed = worldBytes == null || worldBytes.length == 0
                        ? CompletableFuture.completedFuture(null)
                        : PolarStreamLoader.stream(worldBytes, world, worldAccess,
                                BlockSelector.horizontalCircle(config.spawn().getBlockX(), config.spawn().getBlockZ(),
                                        config.worldRadiusBlocks()));
            } catch (Throwable t) {
                streamed = CompletableFuture.failedFuture(t);
            }

            return streamed.handle((_, ex) -> ex)
                    .thenCompose(ex -> finishOrAbortLoading(world, config, worldName, ex));
        });
    }

    /**
     * Creates a polar world
     *
     * @param worldName The name for the polar world
     * @param config Custom config for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     */
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
                                PolarStreamLoader.insertChunk(level, levelChunk);
                                worldAccess.loadChunkData(world, levelChunk, chunk.userData(), blockSelector);
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

    /**
     * Marks a newly created world as ready to be saved and starts autosaving it.
     */
    private static void finishLoading(@NotNull World world, @NotNull Config config) {
        setLoading(world.getKey(), false);
        startAutoSaveTask(world, config);
    }

    /**
     * A partially streamed world is not safe to use. All chunk tasks have settled before this is called, so it
     * can be unloaded without racing tasks that still hold its level and chunks.
     */
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

    /**
     * Creates a polar world
     *
     * @param generator Generator for the world
     * @param worldName The name for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see EntitiesWorldAccess
     * @see PolarStreamingGenerator
     */
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
                        // createNoSaveLevel marks the world as loading before it starts, so clear that again
                        setLoading(worldKey, false);
                        if (ex == null) {
                            LOGGER.error("An error occurred loading polar world '" + worldKey.getKey() + "', skipping.");
                        } else {
                            LOGGER.error("An error occurred loading polar world '" + worldKey.getKey() + "', skipping.", ex);
                        }
                        return;
                    }

                    // Since saving is disabled in the level anyway, setAutoSave is now essentially setting whether
                    // chunks should be allowed to unload and be removed from memory
                    world.setAutoSave(false);
                });
    }

    public static void stopAutoSaveTask(NamespacedKey worldKey) {
        // Removed as well as cancelled: the task holds on to the world, which would otherwise stay in memory
        // for as long as the server runs after being unloaded
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
                updateConfig(world, world.getKey().getKey()); // config should only be updated synchronously
                saveWorld(world)
                        .whenComplete((_, e) -> {
                            saveInProgress.set(false);
                            if (e != null) {
                                // Nothing has been written: a save that cannot be completed in full leaves the
                                // previous file alone rather than replacing a whole world with part of one
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

    /**
     * Writes this world's properties to config (e.g. gamerules)
     * <p>
     * Must be called synchronously, as it reads the world's live state. Everything that touches the disk is
     * kept off the calling thread, because this runs on every autosave of every world.
     */
    public static Config updateConfig(World world, String worldName) {
        FileConfiguration fileConfig = PolarPaper.getPlugin().getConfig();
        Config defaultConfig = Config.getDefaultConfig(fileConfig);

        // Read from the config the plugin already holds rather than from the file. This used to reload the
        // file first, which parsed the whole of it on the main thread on every autosave, and the only thing
        // that gained was picking up hand written edits, which is what /polar reloadconfig is for.
        Config storedConfig = Config.readFromConfig(fileConfig, worldName, defaultConfig.toBuilder()); // If world not in config, use defaults
        Config newConfig = storedConfig.toBuilder().fromWorld(world).build();

        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator != null) generator.setConfig(newConfig);

        // An autosave almost never changes a world's settings, and rewriting the file to say exactly what it
        // already says is the whole cost of this method. A world that has no entry yet is always written, so
        // that it appears in the file even when every one of its settings is the default one.
        if (Config.isInConfig(fileConfig, worldName) && newConfig.equals(storedConfig)) return newConfig;

        Config.applyToConfig(fileConfig, worldName, newConfig);
        saveConfigFile(fileConfig.saveToString());

        return newConfig;
    }

    /**
     * Writes config.yml, off the calling thread while the server is running.
     * <p>
     * Each of these is the whole file, so a newer one supersedes anything still waiting and only the latest
     * has to reach the disk. While the plugin is disabling nothing else will get to run, so it is written
     * there and then instead.
     */
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
            // The plugin stopped being able to schedule between the check above and here, so it is this
            // thread's job after all. Losing the write would lose the world's settings.
            CONFIG_WRITE_SCHEDULED.set(false);
            writePendingConfig();
        }
    }

    /**
     * Writes the config file straight away if a write is still waiting to happen.
     * <p>
     * Meant for shutdown: the scheduler drops queued asynchronous tasks when the plugin stops, so a write
     * that an autosave lined up moments earlier would otherwise never reach the disk.
     */
    public static void flushPendingConfig() {
        writePendingConfig();
    }

    private static void writePendingConfig() {
        // Taken under the lock so that two writers can never hold two different versions at once and race to
        // put the older one down last
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

    /**
     * Replaces a file in one step, so that a server dying mid-write cannot leave behind half a config file
     * for every world to then load its settings from.
     */
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

    /**
     * Reads the config for the world and updates the world's properties (e.g. gamerules)
     */
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

    /**
     * Saves a polar world asynchronously using the source used to load it
     * <br>
     * Will not save if a source was not used to load the world
     *
     * @param world The bukkit world (needs to be a polar world)
     * @see PolarGenerator#getSource()
     */
    public static CompletableFuture<Void> saveWorld(World world) {
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null) return CompletableFuture.completedFuture(null);
        PolarSource source = generator.getSource();
        if (source == null) return CompletableFuture.completedFuture(null);
        return saveWorld(world, source);
    }

    /**
     * Saves a polar world asynchronously using the given source
     *
     * @param world The bukkit world (needs to be a polar world)
     * @param polarSource The source to use to save the polar world
     * @see Polar#getDefaultFolderSource(String)
     * @see BytesPolarSource
     */
    @SuppressWarnings("unused")
    public static CompletableFuture<Void> saveWorld(World world, PolarSource polarSource) {
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null) return CompletableFuture.completedFuture(null);
        Collection<PolarChunk> extraChunks = generator.getPolarWorld() == null ? List.of() : generator.getPolarWorld().chunks();
        return saveWorld(world, extraChunks, polarSource, generator.getWorldAccess(), BlockSelector.ALL, generator.getConfig());
    }

    /**
     * Saves on the current Paper main thread. Intended only for plugin shutdown, when scheduling a continuation
     * back to that same thread and waiting for it would deadlock.
     */
    public static void saveWorldSynchronously(World world) throws Exception {
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null || generator.getSource() == null) return;
        if (Polar.isLoading(world.getKey())) {
            throw new IllegalStateException(world.getKey() + " is still loading");
        }

        Config config = generator.getConfig();
        BlockSelector blockSelector = generator.getWorldBlockSelector();
        Collection<PolarChunk> extraChunks = generator.getPolarWorld() == null
                ? List.of()
                : generator.getPolarWorld().chunks();
        PolarWorld polarWorld = PolarWorld.convertSynchronously(
                world, generator.getWorldAccess(), blockSelector, config, extraChunks);
        polarWorld.userData(generator.getUserData());
        generator.getSource().saveBytes(PolarWriter.write(polarWorld));
    }

    /**
     * Updates and saves a polar world asynchronously using the given source
     * <br>
     * The future is completed exceptionally if saving failed
     *
     * @param world The bukkit world to retrieve new chunks from
     * @param extraChunks Extra chunks to include in the saved file
     * @param polarSource The source to use to save the polar world
     * @param polarWorldAccess Describes how userdata should be handled (default PolarWorldAccess.POLAR_PAPER_FEATURES)
     * @param blockSelector Used to filter which blocks should be updated (essentially a crop)
     * @param config Custom config for the polar world
     * @see EntitiesWorldAccess
     * @see BlockSelector#ALL
     */
    public static CompletableFuture<Void> saveWorld(World world, Collection<PolarChunk> extraChunks, PolarSource polarSource, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, Config config) {
        if (Polar.isLoading(world.getKey())) return CompletableFuture.failedFuture(new IllegalStateException(world.getKey() + " is still loading"));

        BlockSelector radiusSelector = BlockSelector.horizontalCircle(
                config.spawn().getBlockX(), config.spawn().getBlockZ(), config.worldRadiusBlocks());
        BlockSelector boundedSelector = BlockSelector.intersection(blockSelector, radiusSelector);

        // Converting only looks at chunks, so the world level user data has to be carried across by hand
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        byte[] worldUserData = generator == null ? new byte[0] : generator.getUserData();

        CompletableFuture<PolarWorld> future;
        try {
            future = PolarWorld.convert(world, polarWorldAccess, boundedSelector, config, extraChunks, false);
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }

        return future.thenAcceptAsync(newPolarWorld -> {
            newPolarWorld.userData(worldUserData);
            byte[] worldBytes = PolarWriter.write(newPolarWorld);
            try {
                polarSource.saveBytes(worldBytes);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

}
