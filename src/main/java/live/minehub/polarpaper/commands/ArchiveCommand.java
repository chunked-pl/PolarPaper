package live.minehub.polarpaper.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import live.minehub.polarpaper.util.WorldKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.resources.Identifier;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class ArchiveCommand extends PolarCmd {

    public ArchiveCommand() {
        super("archive", "Save a polar world and drop it out of memory");
    }

    protected static int run(CommandContext<CommandSourceStack> ctx) {
        Identifier worldId = ctx.getArgument("world name", Identifier.class);
        CommandSender sender = ctx.getSource().getSender();

        World world = WorldKey.getWorld(worldId);
        if (world == null) {
            sender.sendMessage(Component.text("World '" + worldId.getPath() + "' is not loaded", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        if (PolarGenerator.fromWorld(world) == null) {
            sender.sendMessage(Component.text("World '" + worldId.getPath() + "' is not a polar world", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        if (sender instanceof Player player && player.getWorld().equals(world)) {
            sender.sendMessage(Component.text("Leave '" + worldId.getPath() + "' before archiving it", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        List<Player> standing = world.getPlayers();
        if (!standing.isEmpty()) {
            sender.sendMessage(Component.text(standing.size() + " player(s) are still in '" + worldId.getPath()
                    + "': " + String.join(", ", standing.stream().map(Player::getName).toList()), NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(Component.text("Archiving '" + worldId.getPath() + "'...", NamedTextColor.AQUA));
        UnloadCommand.unload(ctx, worldId, true, true)
                .thenAccept(archived -> {
                    if (Boolean.TRUE.equals(archived)) {
                        sender.sendMessage(Component.text("Archived '" + worldId.getPath()
                                + "'; it comes back the next time somebody enters it", NamedTextColor.AQUA));
                    }
                });
        return Command.SINGLE_SUCCESS;
    }

    @Override
    protected int executeDefault(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendMessage(
                Component.text("Usage: /polar archive <worldname>", NamedTextColor.RED));
        return Command.SINGLE_SUCCESS;
    }

    @Override
    protected void addToBuilder(LiteralArgumentBuilder<CommandSourceStack> builder) {
        builder.then(createWorldNameArgument(true).executes(ArchiveCommand::run));
    }
}
