package live.minehub.polarpaper.util;

import live.minehub.polarpaper.PolarPaper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.resources.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public class WorldKey {

    public static String getWorldName(Path path) {
        Path pluginFolder = PolarPaper.getPlugin().getDataPath();
        Path worldsFolder = pluginFolder.resolve("worlds");
        return worldsFolder.toAbsolutePath().relativize(path.toAbsolutePath()).toString()
                .replaceAll("\\.polar$", "")
                .replace(" ", "_")
                .toLowerCase();
    }

    public static @Nullable World getWorld(String worldName) {
        worldName = worldName
                .replaceAll("\\.polar$", "")
                .replace(" ", "_")
                .toLowerCase();

        NamespacedKey worldKey = NamespacedKey.fromString(worldName, PolarPaper.getPlugin());
        World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
        if (world != null) return world;

        worldKey = NamespacedKey.fromString(worldName);
        world = worldKey == null ? null : Bukkit.getWorld(worldKey);
        if (world != null) return world;

        return Bukkit.getWorld(worldName);
    }

    public static @Nullable World getWorld(Identifier id) {
        if (id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {

            NamespacedKey polarKey = NamespacedKey.fromString(id.getPath(), PolarPaper.getPlugin());
            World polarWorld = polarKey == null ? null : Bukkit.getWorld(polarKey);
            if (polarWorld != null) return polarWorld;
        }

        World world = Bukkit.getWorld(new NamespacedKey(id.getNamespace(), id.getPath()));
        if (world != null) return world;

        return id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? Bukkit.getWorld(id.getPath()) : null;
    }

    public static boolean isWithinWorldsFolder(Path path) {
        Path pluginFolder = PolarPaper.getPlugin().getDataPath();
        Path worldsFolder = pluginFolder.resolve("worlds");
        return path.normalize().startsWith(worldsFolder);
    }

    public static Path validatePath(CommandSender sender, String userPath) {
        Path pluginFolder = PolarPaper.getPlugin().getDataPath();
        Path worldsFolder = pluginFolder.resolve("worlds");
        userPath = userPath + (userPath.endsWith(".polar") ? "" : ".polar");
        Path path;
        try {
            path = worldsFolder.resolve(userPath);
        } catch (InvalidPathException e) {
            sender.sendMessage(Component.text("Invalid path", NamedTextColor.RED));
            return null;
        }

        return validatePath(sender, path);
    }

    public static Path validatePath(CommandSender sender, Path path) {
        if (!isWithinWorldsFolder(path)) {
            sender.sendMessage(Component.text("Outside of worlds folder", NamedTextColor.RED));
            return null;
        }

        return path;
    }

}
