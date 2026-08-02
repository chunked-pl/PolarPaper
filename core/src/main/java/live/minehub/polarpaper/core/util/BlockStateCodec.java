package live.minehub.polarpaper.core.util;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts between block states and the palette strings the polar format stores them as,
 * for example {@code minecraft:oak_fence[north=true,south=false]}.
 * <p>
 * Both directions are cached. A world is built out of a small set of distinct blocks, so the same handful of
 * strings is otherwise parsed again for nearly every one of its sections, which dominates the time spent
 * loading and saving large worlds. The cache is bounded by the number of block states the server has.
 */
public final class BlockStateCodec {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockStateCodec.class);

    private static final Map<String, BlockState> STATES_BY_PALETTE_STRING = new ConcurrentHashMap<>();
    private static final Map<BlockState, String> PALETTE_STRINGS_BY_STATE = new ConcurrentHashMap<>();

    private BlockStateCodec() {
    }

    /**
     * Parses a palette string, falling back to air if it names a block this server does not know.
     * <p>
     * An unparsable string is only reported once, as the same string reappears in section after section.
     */
    public static @NotNull BlockState fromPaletteString(@NotNull String paletteString) {
        return STATES_BY_PALETTE_STRING.computeIfAbsent(paletteString, BlockStateCodec::parse);
    }

    /**
     * Formats a block state the way the polar format stores it.
     */
    public static @NotNull String toPaletteString(@NotNull BlockState blockState) {
        return PALETTE_STRINGS_BY_STATE.computeIfAbsent(blockState, BlockStateCodec::format);
    }

    private static @NotNull BlockState parse(@NotNull String paletteString) {
        try {
            return ((CraftBlockData) Bukkit.getServer().createBlockData(paletteString)).getState();
        } catch (IllegalArgumentException _) {
            LOGGER.warn("Failed to parse block state, replacing it with air: {}", paletteString);
            return Blocks.AIR.defaultBlockState();
        }
    }

    private static @NotNull String format(@NotNull BlockState blockState) {
        // e.g. Block{minecraft:oak_fence}[north=true] to minecraft:oak_fence[north=true]
        return blockState.toString().replace("Block{", "").replace("}", "");
    }
}
