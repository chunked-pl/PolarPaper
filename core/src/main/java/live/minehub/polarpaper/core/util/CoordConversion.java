package live.minehub.polarpaper.core.util;

public class CoordConversion {

    public static final int SECTION_BOUND = 15;

    public static int sectionBlockIndexGetY(int index) {
        return (index >> 8) & SECTION_BOUND;
    }

    public static int sectionBlockIndexGetX(int index) {
        return index & SECTION_BOUND;
    }

    public static int sectionBlockIndexGetZ(int index) {
        return (index >> 4) & SECTION_BOUND;
    }

    public static int globalToChunk(int xz) {

        return xz >> 4;
    }

    public static long chunkIndex(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }
    public static int chunkX(long chunkIndex) {
        return (int)(chunkIndex >> 32);
    }
    public static int chunkZ(long chunkIndex) {
        return (int)chunkIndex;
    }

    public static int chunkBlockIndex(int x, int y, int z) {
        x = globalToSectionRelative(x);
        z = globalToSectionRelative(z);

        int index = x & 0xF;
        if (y > 0) {
            index |= (y << 4) & 0x07FFFFF0;
        } else {
            index |= ((-y) << 4) & 0x7FFFFF0;
            index |= 1 << 27;
        }
        index |= (z << 28) & 0xF0000000;
        return index;
    }

    public static int chunkBlockIndexGetX(int index) {
        return index & 0xF;
    }

    public static int chunkBlockIndexGetY(int index) {
        int y = (index & 0x07FFFFF0) >>> 4;
        if (((index >>> 27) & 1) == 1) y = -y;
        return y;
    }

    public static int chunkBlockIndexGetZ(int index) {
        return (index >> 28) & 0xF;
    }

    public static int globalToSectionRelative(int xyz) {
        return xyz & 0xF;
    }

    public static int sectionIndex(int y, int minSection) {
        return sectionIndex(y) - minSection;
    }
    public static int sectionIndex(int y) {
        return (int)Math.floor((double)y / 16);
    }
    public static int sectionIndexToY(int sectionIndex) {
        return sectionIndex * 16;
    }

}
