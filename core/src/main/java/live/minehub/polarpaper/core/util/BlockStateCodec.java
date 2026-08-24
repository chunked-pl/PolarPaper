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

public final class BlockStateCodec {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockStateCodec.class);
    private static final int MAX_PARSED_STATE_CACHE_SIZE = 65_536;

    private static final Map<String, BlockState> STATES_BY_PALETTE_STRING = new ConcurrentHashMap<>();
    private static final Map<BlockState, String> PALETTE_STRINGS_BY_STATE = new ConcurrentHashMap<>();

    private BlockStateCodec() {
    }

    public static @NotNull BlockState fromPaletteString(@NotNull String paletteString) {
        BlockState cached = STATES_BY_PALETTE_STRING.get(paletteString);
        if (cached != null) return cached;

        BlockState parsed = parse(paletteString);

        if (STATES_BY_PALETTE_STRING.size() >= MAX_PARSED_STATE_CACHE_SIZE) return parsed;

        BlockState previous = STATES_BY_PALETTE_STRING.putIfAbsent(paletteString, parsed);
        return previous == null ? parsed : previous;
    }

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

        return blockState.toString().replace("Block{", "").replace("}", "");
    }
}
