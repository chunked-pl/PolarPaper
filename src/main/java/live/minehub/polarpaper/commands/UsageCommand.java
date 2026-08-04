package live.minehub.polarpaper.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import live.minehub.polarpaper.PolarPaper;
import live.minehub.polarpaper.core.util.WorldUsage;
import live.minehub.polarpaper.util.WorldKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.resources.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public class UsageCommand extends PolarCmd {

    private static final int DURATION_TICKS = 30 * 20;
    private static final int UPDATE_INTERVAL_TICKS = 10;
    private static final int TICKS_PER_SECOND = 20;

    private static final long BYTES_PER_KIB = 1024L;
    private static final long BYTES_PER_MIB = BYTES_PER_KIB * 1024L;

    public UsageCommand() {
        super("usage", "Show what a world's chunks are holding, on the action bar for 30 seconds");
    }

    private static int run(CommandContext<CommandSourceStack> ctx) {
        Identifier worldId = ctx.getArgument("world name", Identifier.class);
        CommandSender sender = ctx.getSource().getSender();

        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can be shown the action bar", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        World world = WorldKey.getWorld(worldId);
        if (world == null) {
            sender.sendMessage(Component.text()
                    .append(Component.text("World '", NamedTextColor.RED))
                    .append(Component.text(worldId.getPath(), NamedTextColor.RED))
                    .append(Component.text("' does not exist or is not loaded!", NamedTextColor.RED)));
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(Component.text()
                .append(Component.text("Measuring '", NamedTextColor.AQUA))
                .append(Component.text(world.getKey().getKey(), NamedTextColor.AQUA))
                .append(Component.text("' for 30 seconds.", NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text("Counts the chunks this world owns and nothing else, so it will read far "
                        + "lower than the server heap. Arrays are exact, the objects around them estimated.", NamedTextColor.GRAY)));

        track(player, world);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Refreshes the action bar until the time runs out, or until there is nobody left to show it to.
     */
    private static void track(Player player, World world) {
        int[] ticksLeft = {DURATION_TICKS};
        BukkitTask[] taskHolder = new BukkitTask[1];

        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(PolarPaper.getPlugin(), () -> {
            ticksLeft[0] -= UPDATE_INTERVAL_TICKS;

            // The world can be unloaded, and the player can leave, long before the 30 seconds are up
            if (ticksLeft[0] <= 0 || !player.isOnline() || Bukkit.getWorld(world.getKey()) == null) {
                taskHolder[0].cancel();
                return;
            }

            player.sendActionBar(usageComponent(world, ticksLeft[0] / TICKS_PER_SECOND));
        }, UPDATE_INTERVAL_TICKS, UPDATE_INTERVAL_TICKS);
    }

    private static TextComponent.Builder usageComponent(World world, int secondsLeft) {
        WorldUsage usage = WorldUsage.measure(world);
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();

        return Component.text()
                .append(Component.text(world.getKey().getKey() + " ", NamedTextColor.AQUA))
                .append(Component.text(formatBytes(usage.totalBytes()), NamedTextColor.WHITE))
                .append(field("chunks", Integer.toString(usage.chunks())))
                .append(field("sections", Integer.toString(usage.sectionsWithBlocks())))
                .append(field("light", formatBytes(usage.lightBytes())))
                .append(field("blocks", formatBytes(usage.blockBytes() + usage.biomeBytes() + usage.heightmapBytes())))
                .append(field("objects", formatBytes(usage.overheadBytes())))
                .append(usage.hasArchive()
                        ? field("archived", usage.archivedChunks() + " chunks, " + formatBytes(usage.archivedBytes()))
                        : Component.empty())
                // Shown alongside so that a panel reading several hundred megabytes is not a surprise: most of
                // the heap is the server itself, plus whatever garbage the last collection has not taken yet
                .append(field("server heap", formatBytes(heapUsed) + " / " + formatBytes(runtime.maxMemory())))
                .append(Component.text("  " + secondsLeft + "s", NamedTextColor.DARK_GRAY));
    }

    private static TextComponent.Builder field(String name, String value) {
        return Component.text()
                .append(Component.text("  " + name + " ", NamedTextColor.DARK_GRAY))
                .append(Component.text(value, NamedTextColor.GRAY));
    }

    private static String formatBytes(long bytes) {
        if (bytes >= BYTES_PER_MIB) return "%.1f MB".formatted((double) bytes / BYTES_PER_MIB);
        if (bytes >= BYTES_PER_KIB) return "%.0f KB".formatted((double) bytes / BYTES_PER_KIB);
        return bytes + " B";
    }

    @Override
    protected int executeDefault(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendMessage(
                Component.text("Usage: /polar usage <worldname>", NamedTextColor.RED)
        );
        return Command.SINGLE_SUCCESS;
    }

    @Override
    protected void addToBuilder(LiteralArgumentBuilder<CommandSourceStack> builder) {
        builder.then(createWorldNameArgument(false)
                .executes(UsageCommand::run));
    }
}
