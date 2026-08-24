package live.minehub.polarpaper.core.util;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import live.minehub.polarpaper.core.world.PolarSection;
import net.minecraft.world.level.chunk.DataLayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public class LightUtil {

    public static final int LIGHT_LENGTH = 2048;
    public static final byte[] EMPTY_CONTENT = new byte[LIGHT_LENGTH];
    public static final byte[] FULLY_LIT_CONTENT = new byte[LIGHT_LENGTH];

    static {
        Arrays.fill(FULLY_LIT_CONTENT, (byte) -1);
    }

    public static @NotNull SWMRNibbleArray createNibbleArray(@NotNull PolarSection.LightContent lightContent, byte @Nullable [] data) {
        return switch (lightContent) {
            case MISSING, EMPTY -> new SWMRNibbleArray();
            case FULL -> new SWMRNibbleArray(FULLY_LIT_CONTENT.clone());
            case PRESENT -> new SWMRNibbleArray(data == null ? null : data.clone());
        };
    }

    public static @NotNull SWMRNibbleArray createOpenSkyNibbleArray() {
        return new SWMRNibbleArray(null, true);
    }

    public static PolarSection.LightContent getLightContent(DataLayer dataLayer) {
        byte[] content = dataLayer.isDefinitelyHomogenous() ? null : dataLayer.getData();
        if (dataLayer.isDefinitelyFilledWith(0)) return PolarSection.LightContent.EMPTY;
        if (dataLayer.isDefinitelyFilledWith(15)) return PolarSection.LightContent.FULL;
        if (content == null || content.length == 0) return PolarSection.LightContent.MISSING;
        else if (Arrays.equals(content, EMPTY_CONTENT)) return PolarSection.LightContent.EMPTY;
        else if (Arrays.equals(content, FULLY_LIT_CONTENT)) return PolarSection.LightContent.FULL;
        else return PolarSection.LightContent.PRESENT;
    }

}
