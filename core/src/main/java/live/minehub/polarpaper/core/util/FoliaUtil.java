package live.minehub.polarpaper.core.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public class FoliaUtil {

    private static final boolean FOLIA = detectFolia();

    private FoliaUtil() {
    }

    public static void scheduleOnEntityIfFolia(Plugin plugin, Entity entity, Runnable runnable) {
        scheduleOnEntityIfFolia(plugin, entity, runnable, () -> {});
    }

    public static void scheduleOnEntityIfFolia(Plugin plugin, Entity entity, Runnable runnable, Runnable retired) {
        if (isFolia()) {
            if (!entity.getScheduler().execute(plugin, runnable, retired, 1L)) retired.run();
        } else {
            runnable.run();
        }
    }

    public static void scheduleOnRegionIfFolia(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
        if (isFolia()) {
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, runnable);
        } else {
            runnable.run();
        }
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

}
