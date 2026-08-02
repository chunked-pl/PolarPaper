package live.minehub.polarpaper.nms;

import live.minehub.polarpaper.Polar;
import live.minehub.polarpaper.PolarPaper;
import live.minehub.polarpaper.core.userdata.EntitySerializer;
import live.minehub.polarpaper.util.EntitiesWorldAccess;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.util.Versioning;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class VersionUtil {
    private VersionUtil() {
    }

    public static CompletableFuture<@Nullable World> createNoSaveLevel(WorldCreator creator, Location spawnPos, Difficulty difficulty, Map<String, Object> gamerules, long time) {
        Polar.setLoading(creator.key(), true);
        Plugin plugin = PolarPaper.getPlugin();
        String version = Versioning.getCurrentApiVersion();
        return switch (version) {
            case "1.21.11" -> new live.minehub.polarpaper.paper_1_21_11.NoSaveLevelCreatorImpl().createLevel(plugin, creator, spawnPos, difficulty, gamerules, time);
            default -> new live.minehub.polarpaper.paper_latest.NoSaveLevelCreatorImpl().createLevel(plugin, creator, spawnPos, difficulty, gamerules, time);
        };
    }

    public static EntitiesWorldAccess getPolarFeaturesWorldAccess() {
        return new EntitiesWorldAccess(PolarPaper.getPlugin(), getEntitySerializer());
    }

    /**
     * The serializer is stateless and looking it up costs an API version check, so the single instance is
     * shared. It is called once per entity when pasting a world.
     */
    public static EntitySerializer getEntitySerializer() {
        return EntitySerializerHolder.INSTANCE;
    }

    /** Initialised on first use, so the API version is only read once the server is up. */
    private static final class EntitySerializerHolder {
        private static final EntitySerializer INSTANCE = switch (Versioning.getCurrentApiVersion()) {
            case "1.21.11", "26.1.2" -> new live.minehub.polarpaper.paper_26_1.EntitySerializerImpl();
            default -> new live.minehub.polarpaper.paper_latest.EntitySerializerImpl();
        };
    }

}
