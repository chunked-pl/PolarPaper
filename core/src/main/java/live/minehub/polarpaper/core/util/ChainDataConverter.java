package live.minehub.polarpaper.core.util;

import live.minehub.polarpaper.core.world.PolarDataConverter;
import org.jetbrains.annotations.NotNull;

public class ChainDataConverter implements PolarDataConverter {

    private static final int CHAIN_RENAME_VERSION = 4536;

    @Override
    public void convertBlockPalette(@NotNull String[] palette, int fromVersion, int toVersion) {
        if (fromVersion >= CHAIN_RENAME_VERSION) return;

        for (int i = 0; i < palette.length; i++) {
            String string = palette[i];
            String fixed = string.replace("minecraft:chain", "minecraft:iron_chain");
            palette[i] = fixed;
        }
    }
}
