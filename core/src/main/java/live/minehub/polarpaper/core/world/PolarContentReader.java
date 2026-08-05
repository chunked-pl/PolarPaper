package live.minehub.polarpaper.core.world;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import live.minehub.polarpaper.core.util.ByteArrayUtil;
import org.jetbrains.annotations.NotNull;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.getVarInt;

/**
 * Reads the part every polar file opens with, up to the point its chunks begin.
 * <p>
 * Streaming a world in and reading bodies back out of an archive both have to walk this header, and a second
 * copy of the walk is how a format change turns into a file that still reads as valid but is not. There is
 * one walk, here.
 */
final class PolarContentReader {

    private PolarContentReader() {
    }

    /**
     * A file opened up to its first chunk. {@code body} is positioned at that chunk, and is the decompressed
     * content rather than the bytes on disk.
     */
    record Content(short version, int dataVersion, byte minSection, byte maxSection,
                   byte @NotNull [] userData, int chunkCount, @NotNull ByteBuf body) {

        int sectionCount() {
            return this.maxSection - this.minSection + 1;
        }
    }

    static @NotNull Content open(byte @NotNull [] data, @NotNull PolarDataConverter dataConverter) {
        ByteBuf bb = Unpooled.wrappedBuffer(data);

        int magic = bb.readInt();
        if (magic != PolarConstants.MAGIC_NUMBER) {
            throw new IllegalStateException("Invalid magic number");
        }

        short version = bb.readShort();
        PolarReader.validateVersion(version);

        int dataVersion = version >= PolarConstants.VERSION_DATA_CONVERTER
                ? getVarInt(bb)
                : dataConverter.defaultDataVersion();

        byte compressionByte = bb.readByte();
        PolarWorld.CompressionType compression = PolarWorld.CompressionType.fromId(compressionByte);
        if (compression == null) {
            throw new IllegalStateException("Invalid compression type " + compressionByte);
        }

        int compressedDataLength = getVarInt(bb);
        ByteBuf content = PolarReader.decompressBuffer(bb, compression, compressedDataLength);

        byte minSection = content.readByte();
        byte maxSection = content.readByte();
        if (minSection >= maxSection) {
            throw new IllegalStateException("Invalid section range " + minSection + ".." + maxSection);
        }

        byte[] userData = new byte[0];
        if (version > PolarConstants.VERSION_WORLD_USERDATA) {
            userData = ByteArrayUtil.getByteArray(content);
        }

        int chunkCount = getVarInt(content);
        return new Content(version, dataVersion, minSection, maxSection, userData, chunkCount, content);
    }
}
