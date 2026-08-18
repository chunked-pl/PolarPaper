package live.minehub.polarpaper.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import live.minehub.polarpaper.PolarPaper;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.resources.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public abstract class PolarCmd {

    private static final long WORLD_NAME_CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final Pattern IDENTIFIER_PATH = Pattern.compile("[a-z0-9_.\\-/]+");

    private static volatile List<String> cachedWorldNames;
    private static volatile long cachedWorldNamesAt;

    private final String name;
    private final String description;
    private final List<String> aliases;

    public PolarCmd(String name, String description) {
        this(name, description, List.of());
    }

    public PolarCmd(String name, String description, List<String> aliases) {
        this.name = name;
        this.description = description;
        this.aliases = List.copyOf(aliases);
    }

    protected abstract int executeDefault(CommandContext<CommandSourceStack> ctx);

    protected abstract void addToBuilder(LiteralArgumentBuilder<CommandSourceStack> builder);

    public void registerCommand(LiteralArgumentBuilder<CommandSourceStack> rootBuilder) {
        rootBuilder.then(buildSubcommand(name).build());
        for (String alias : aliases) {
            rootBuilder.then(buildSubcommand(alias).build());
        }
    }

    /**
     * Aliases are built from the same definition and share the primary permission, so granting
     * {@code polarpaper.goto} is enough for {@code /polar tp} as well.
     */
    private LiteralArgumentBuilder<CommandSourceStack> buildSubcommand(String literal) {
        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(literal)
                .requires(source -> source.getSender().hasPermission(getPermission()))
                .executes(this::executeDefault);

        addToBuilder(builder);
        return builder;
    }

    public String getName() {
        return name;
    }

    public List<String> getAliases() {
        return aliases;
    }

    /**
     * Every literal this subcommand answers to, the primary name first.
     */
    public List<String> getLiterals() {
        if (aliases.isEmpty()) return List.of(name);

        List<String> literals = new ArrayList<>(aliases.size() + 1);
        literals.add(name);
        literals.addAll(aliases);
        return literals;
    }

    public String getDescription() {
        return description;
    }

    public String getPermission() {
        return "polarpaper." + getName();
    }

    public RequiredArgumentBuilder<CommandSourceStack, String> createFileWorldNameArgument(boolean greedy) {
        return Commands.argument("world name", greedy ? StringArgumentType.greedyString() : StringArgumentType.string())
                .suggests((_, s) -> {
                    for (String worldName : listSavedWorldNames()) {
                        if (!worldName.toLowerCase().startsWith(s.getRemainingLowerCase())) continue;

                        s.suggest(worldName);
                    }

                    return s.buildFuture();
                });
    }

    /**
     * The names of the worlds in the worlds folder, cached briefly.
     * <p>
     * Brigadier asks for suggestions on every keystroke, so without this every character typed lists the
     * folder from disk on the thread handling the command.
     */
    private static List<String> listSavedWorldNames() {
        long now = System.nanoTime();
        if (cachedWorldNames != null && now - cachedWorldNamesAt < WORLD_NAME_CACHE_NANOS) return cachedWorldNames;

        Path worldsFolder = PolarPaper.getPlugin().getDataPath().resolve("worlds");
        List<String> worldNames = new ArrayList<>();
        try (Stream<Path> files = Files.list(worldsFolder)) {
            files.forEach(path -> worldNames.add(path.getFileName().toString().replaceAll("\\.polar$", "")));
        } catch (IOException _) {
            // Folder missing or unreadable, so there is nothing to suggest
        }

        cachedWorldNames = worldNames;
        cachedWorldNamesAt = now;
        return worldNames;
    }

    public RequiredArgumentBuilder<CommandSourceStack, Identifier> createWorldNameArgument(boolean onlyPolar) {
        return Commands.argument("world name", IdentifierArgument.id())
                .suggests((_, s) -> {
                    String typed = s.getRemainingLowerCase();
                    for (World world : Bukkit.getWorlds()) {
                        if (onlyPolar) {
                            PolarGenerator polarGenerator = PolarGenerator.fromWorld(world);
                            if (polarGenerator == null) continue;
                        }

                        String worldKey = world.getKey().toString().replace(PolarPaper.getPlugin().namespace() + ":", "");

                        if (worldKey.toLowerCase().startsWith(typed)
                            || world.getKey().getKey().toLowerCase().startsWith(typed)) {
                            s.suggest(worldKey);
                        }

                        // The server's own worlds go by their folder name rather than their key, so
                        // "world" has to be offered alongside minecraft:overworld
                        String worldName = world.getName().toLowerCase();
                        if (!worldName.equals(worldKey) && worldName.startsWith(typed)
                            && IDENTIFIER_PATH.matcher(worldName).matches()) {
                            s.suggest(worldName);
                        }
                    }
                    return s.buildFuture();
                });
    }
}
