package live.minehub.polarpaper.core.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import live.minehub.polarpaper.core.world.PolarChunk;
import net.minecraft.nbt.NbtIo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public class ByteArrayUtil {

    public static byte[] outputArray(ByteBuf bb) {
        byte[] bytes = new byte[bb.readableBytes()];
        bb.readBytes(bytes);
        return bytes;
    }

    public static byte[] getByteArray(ByteBuf bb) {
        int packedLength = getVarInt(bb);
        requireReadableLength(bb, packedLength, 1, "byte array");
        byte[] bytes = new byte[packedLength];
        bb.readBytes(bytes);
        return bytes;
    }

    public static long[] getLongArray(ByteBuf bb) {
        int packedLength = getVarInt(bb);
        requireReadableLength(bb, packedLength, Long.BYTES, "long array");
        long[] longs = new long[packedLength];
        for (int i = 0; i < packedLength; i++) {
            longs[i] = bb.readLong();
        }
        return longs;
    }

    // Copyright 2019 Google LLC
    public static int getVarInt(ByteBuf bb) {
        int result = 0;
        for (int position = 0; position < Integer.BYTES + 1; position++) {
            byte current = bb.readByte();
            if (position == Integer.BYTES && (current & 0xf0) != 0) {
                throw new IllegalArgumentException("VarInt exceeds 32 bits");
            }
            result |= (current & 0x7f) << (position * 7);
            if (current >= 0) return result;
        }
        throw new IllegalArgumentException("VarInt is longer than 5 bytes");
    }

    public static @NotNull String getString(ByteBuf bb) {
        int length = getVarInt(bb);
        requireReadableLength(bb, length, 1, "string");
        byte[] bytes = new byte[length];
        bb.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    public static @Nullable String getStringOptional(ByteBuf bb) {
        boolean present = bb.readByte() == 1;
        if (!present) return null;
        return getString(bb);
    }
    public static String[] getStringList(ByteBuf bb, int maxSize) {
        int length = getVarInt(bb);
        if (length < 0 || length > maxSize || length > bb.readableBytes()) {
            throw new IllegalArgumentException("Invalid string list length: " + length);
        }
        String[] strings = new String[length];
        for (int i = 0; i < length; i++) {
            strings[i] = getString(bb);
        }
        return strings;
    }
    public static byte[] getLightData(ByteBuf bb) {
        byte[] bytes = new byte[LightUtil.LIGHT_LENGTH];
        bb.readBytes(bytes);
        return bytes;
    }



    public static void writeBlockEntity(@NotNull ByteBuf bb, @NotNull PolarChunk.BlockEntity blockEntity) {
        bb.writeInt(blockEntity.index());
        bb.writeByte(blockEntity.id() == null ? 0 : 1);
        if (blockEntity.id() != null) {
            writeString(blockEntity.id(), bb);
        }

        bb.writeByte(blockEntity.data() == null ? 0 : 1);
        if (blockEntity.data() != null) {
            try {
                ByteBufOutputStream byteBufOutputStream = new ByteBufOutputStream(bb);
                NbtIo.writeAnyTag(blockEntity.data(), byteBufOutputStream);
                byteBufOutputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void writeVarInt(int v, ByteBuf bb) {
        while (true) {
            int bits = v & 0x7f;
            v >>>= 7;
            if (v == 0) {
                bb.writeByte((byte) bits);
                return;
            }
            bb.writeByte((byte) (bits | 0x80));
        }
    }

    public static void writeString(String s, ByteBuf bb) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length, bb);
        bb.writeBytes(bytes);
    }

    public static void writeByteArray(byte[] bytes, ByteBuf bb) {
        writeVarInt(bytes.length, bb);
        bb.writeBytes(bytes);
    }

    public static void writeLongArray(long[] longs, ByteBuf bb) {
        writeVarInt(longs.length, bb);
        for (long aLong : longs) {
            bb.writeLong(aLong);
        }
    }

    public static void writeStrings(Collection<String> strings, ByteBuf bb) {
        writeVarInt(strings.size(), bb);
        for (String aString : strings) {
            writeString(aString, bb);
        }
    }

    public static void writeStringArray(String[] strings, ByteBuf bb) {
        writeVarInt(strings.length, bb);
        for (String aString : strings) {
            writeString(aString, bb);
        }
    }

    private static void requireReadableLength(ByteBuf bb, int length, int elementBytes, String description) {
        if (length < 0 || length > bb.readableBytes() / elementBytes) {
            throw new IllegalArgumentException("Invalid " + description + " length: " + length);
        }
    }

}
