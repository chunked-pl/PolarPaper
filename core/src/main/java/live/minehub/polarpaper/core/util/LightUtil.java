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

    /**
     * Builds the nibble array a chunk section is lit with.
     * <p>
     * Uniformly dark and uniformly lit sections are fully described by {@code lightContent}, so they are only
     * expanded here instead of being held as a 2 KiB array on every {@link PolarSection} of a loaded world.
     * <p>
     * A fully dark section needs no array at all. An uninitialised nibble already reads as light level 0, is
     * sent to clients as an empty section rather than 2 KiB of zeroes, and allocates itself the first time
     * starlight writes to it. This is the same state already used for sections that were saved without light.
     * <p>
     * Where an array is needed it is freshly allocated, because starlight writes to it and one PolarWorld may
     * be used to create several bukkit worlds.
     *
     * @param data the raw nibble data, only read when {@code lightContent} is {@link PolarSection.LightContent#PRESENT}
     */
    public static @NotNull SWMRNibbleArray createNibbleArray(@NotNull PolarSection.LightContent lightContent, byte @Nullable [] data) {
        return switch (lightContent) {
            case MISSING, EMPTY -> new SWMRNibbleArray();
            case FULL -> new SWMRNibbleArray(FULLY_LIT_CONTENT.clone());
            case PRESENT -> new SWMRNibbleArray(data == null ? null : data.clone());
        };
    }

    /**
     * Builds the sky nibble of a section that open sky reaches, which starlight reads as light level 15
     * without any storage at all.
     * <p>
     * The distinction from the array {@link #createNibbleArray} builds for missing light is the state, not the
     * absent data: an uninitialised nibble reads as 0 and is sent to clients as an unlit section, while a null
     * one is what both starlight and the vanilla client take as "open sky above, so fully lit".
     */
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
