package live.minehub.polarpaper.core.world;

import live.minehub.polarpaper.core.util.ChainDataConverter;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public interface PolarDataConverter {
    @NotNull
    PolarDataConverter DEFAULT = new ChainDataConverter();

    @NotNull
    PolarDataConverter NOOP = new PolarDataConverter() {
    };

    default int defaultDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    default int dataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    default void convertBlockPalette(@NotNull String[] palette, int fromVersion, int toVersion) {

    }

    default @NotNull Map.Entry<String, CompoundTag> convertBlockEntityData(
            @NotNull String id, @NotNull CompoundTag data,
            int fromVersion, int toVersion
    ) {
        return Map.entry(id, data);
    }

}
