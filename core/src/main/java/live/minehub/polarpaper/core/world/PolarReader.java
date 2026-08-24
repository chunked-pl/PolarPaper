package live.minehub.polarpaper.core.world;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.userdata.EntityUtil;
import live.minehub.polarpaper.core.util.ByteArrayUtil;
import live.minehub.polarpaper.core.util.LightUtil;
import live.minehub.polarpaper.core.util.PaletteUtil;
import net.kyori.adventure.key.Key;
import net.minecraft.nbt.*;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static live.minehub.polarpaper.core.util.ByteArrayUtil.*;

public class PolarReader {

    private static final int MAX_BLOCK_PALETTE_SIZE = 16 * 16 * 16;
    private static final int MAX_BIOME_PALETTE_SIZE = 8 * 8 * 8;

    private static final int MAX_UNDECLARED_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024;

    public static @NotNull PolarWorld read(PolarSource source) throws IOException {
        try {
            return read(source.readBytes());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public static @NotNull PolarWorld read(PolarSource source, @NotNull PolarDataConverter dataConverter) throws IOException {
        try {
            return read(source.readBytes(), dataConverter);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public static @NotNull PolarWorld read(byte @NotNull [] data) {
        return read(data, PolarDataConverter.DEFAULT);
    }

    public static @NotNull PolarWorld read(byte @NotNull [] data, @NotNull PolarDataConverter dataConverter) {
        ByteBuf bb = Unpooled.wrappedBuffer(data);

        int magic = bb.readInt();
        assertThat(magic == PolarWorld.MAGIC_NUMBER, "Invalid magic number");

        short version = bb.readShort();
        validateVersion(version);

        int dataVersion = version >= PolarWorld.VERSION_DATA_CONVERTER
                ? getVarInt(bb)
                : dataConverter.defaultDataVersion();

        byte compressionByte = bb.readByte();
        PolarWorld.CompressionType compression = PolarWorld.CompressionType.fromId(compressionByte);
        assertThat(compression != null, "Invalid compression type");

        int compressedDataLength = getVarInt(bb);

        ByteBuf uncompressed = decompressBuffer(bb, compression, compressedDataLength);

        byte minSection = uncompressed.readByte();
        byte maxSection = uncompressed.readByte();
        assertThat(minSection < maxSection, "Invalid section range");

        byte[] userData = new byte[0];
        if (version > PolarWorld.VERSION_WORLD_USERDATA) {
            userData = getByteArray(uncompressed);
        }

        int chunkCount = getVarInt(uncompressed);
        validateChunkCount(chunkCount, uncompressed, maxSection - minSection + 1);
        List<PolarChunk> chunks = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            chunks.add(readChunk(dataConverter, version, dataVersion, uncompressed, maxSection - minSection + 1));
        }

        return new PolarWorld(version, dataVersion, compression, minSection, maxSection, userData, chunks);
    }

    protected static @NotNull PolarChunk readChunk(@NotNull PolarDataConverter dataConverter, short version, int dataVersion, @NotNull ByteBuf bb, int sectionCount) {
        int chunkX = getVarInt(bb);
        int chunkZ = getVarInt(bb);
        return readChunkBody(dataConverter, version, dataVersion, bb, sectionCount, chunkX, chunkZ);
    }

    public static @NotNull PolarChunk readChunkBody(@NotNull PolarDataConverter dataConverter, short version, int dataVersion,
                                                    @NotNull ByteBuf bb, int sectionCount, int chunkX, int chunkZ) {
        PolarSection[] sections = new PolarSection[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            sections[i] = readSection(dataConverter, version, dataVersion, bb);
        }

        int blockEntityCount = getVarInt(bb);
        PolarChunk.BlockEntity[] blockEntities = new PolarChunk.BlockEntity[blockEntityCount];
        for (int i = 0; i < blockEntityCount; i++) {
            blockEntities[i] = readBlockEntity(dataConverter, dataVersion, bb);
        }

        List<PolarEntity> entities = null;
        if (version == PolarWorld.VERSION_DEPRECATED_ENTITIES) {
            entities = new ArrayList<>();
            int entityCount = getVarInt(bb);
            for (int i = 0; i < entityCount; i++) {
                entities.add(new PolarEntity(
                        bb.readDouble(),
                        bb.readDouble(),
                        bb.readDouble(),
                        bb.readFloat(),
                        bb.readFloat(),
                        getByteArray(bb)
                ));
            }
        }

        int[][] heightmaps = readHeightmaps(bb);

        byte[] userData = getByteArray(bb);

        if (entities != null) {
            ByteBuf newData = Unpooled.buffer();
            newData.writeByte((byte) 1);
            EntityUtil.writeEntities(entities, newData);

            userData = ByteArrayUtil.outputArray(newData);
        }

        return new PolarChunk(
                chunkX, chunkZ,
                sections,
                blockEntities,
                heightmaps,
                userData
        );
    }

    protected static void skipChunkBody(short version, @NotNull ByteBuf bb, int sectionCount) {
        for (int i = 0; i < sectionCount; i++) skipSection(version, bb);

        int blockEntityCount = getVarInt(bb);
        if (blockEntityCount < 0 || blockEntityCount > bb.readableBytes() / 6) {
            throw new IllegalArgumentException("Invalid block entity count: " + blockEntityCount);
        }
        for (int i = 0; i < blockEntityCount; i++) skipBlockEntity(bb);

        if (version == PolarWorld.VERSION_DEPRECATED_ENTITIES) {
            int entityCount = getVarInt(bb);
            if (entityCount < 0 || entityCount > bb.readableBytes() / 33) {
                throw new IllegalArgumentException("Invalid entity count: " + entityCount);
            }
            for (int i = 0; i < entityCount; i++) {
                bb.skipBytes(Double.BYTES * 3 + Float.BYTES * 2);
                skipByteArray(bb);
            }
        }

        skipHeightmaps(bb);
        skipByteArray(bb);
    }

    private static void skipSection(short version, @NotNull ByteBuf bb) {
        if (bb.readByte() == 1) return;

        int blockPaletteLength = skipStringList(bb, MAX_BLOCK_PALETTE_SIZE);
        if (blockPaletteLength > 1) skipLongArray(bb);

        int biomePaletteLength = skipStringList(bb, MAX_BIOME_PALETTE_SIZE);
        if (biomePaletteLength > 1) skipLongArray(bb);

        if (readLightContent(version, bb) == PolarSection.LightContent.PRESENT) {
            skipFixedBytes(bb, LightUtil.LIGHT_LENGTH, "block light");
        }
        if (readLightContent(version, bb) == PolarSection.LightContent.PRESENT) {
            skipFixedBytes(bb, LightUtil.LIGHT_LENGTH, "sky light");
        }
    }

    private static void skipBlockEntity(@NotNull ByteBuf bb) {
        skipFixedBytes(bb, Integer.BYTES, "block entity position");
        if (bb.readByte() == 1) skipString(bb);
        if (bb.readByte() != 1) return;

        try (ByteBufInputStream input = new ByteBufInputStream(bb)) {
            NbtIo.readAnyTag(input, NbtAccounter.create(2 * 1024 * 1024));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void skipFixedBytes(@NotNull ByteBuf bb, int length, @NotNull String description) {
        if (bb.readableBytes() < length) {
            throw new IllegalArgumentException("Invalid " + description + " length: " + bb.readableBytes());
        }
        bb.skipBytes(length);
    }

    protected static void skipHeightmaps(@NotNull ByteBuf bb) {
        int heightmapMask = bb.readInt();
        for (int i = 0; i < PolarChunk.MAX_HEIGHTMAPS; i++) {
            if ((heightmapMask & (1 << i)) == 0) continue;

            int packedLength = getVarInt(bb);
            if (packedLength < 0 || packedLength > bb.readableBytes() / Long.BYTES) {
                throw new IllegalArgumentException("Invalid heightmap length: " + packedLength);
            }
            bb.skipBytes(packedLength * Long.BYTES);
        }
    }

    protected static int @NotNull [][] readHeightmaps(ByteBuf bb) {
        int[][] heightmaps = new int[PolarChunk.MAX_HEIGHTMAPS][];
        int heightmapMask = bb.readInt();
        for (int i = 0; i < PolarChunk.MAX_HEIGHTMAPS; i++) {
            if ((heightmapMask & (1 << i)) == 0)
                continue;

            long[] packed = getLongArray(bb);
            if (packed.length == 0) {
                heightmaps[i] = new int[0];
            } else {
                int bitsPerEntry = packed.length * 64 / PolarChunk.HEIGHTMAP_SIZE;
                heightmaps[i] = new int[PolarChunk.HEIGHTMAP_SIZE];
                PaletteUtil.unpack(heightmaps[i], packed, bitsPerEntry);
            }
        }
        return heightmaps;
    }

    protected static @NotNull PolarSection readSection(@NotNull PolarDataConverter dataConverter, short version, int dataVersion, @NotNull ByteBuf bb) {

        if (bb.readByte() == 1) return new PolarSection();

        String[] blockPalette = getStringList(bb, MAX_BLOCK_PALETTE_SIZE);
        if (dataVersion < dataConverter.dataVersion()) {
            dataConverter.convertBlockPalette(blockPalette, dataVersion, dataConverter.dataVersion());
        }
        if (version <= PolarWorld.VERSION_SHORT_GRASS) {
            for (int i = 0; i < blockPalette.length; i++) {
                if (blockPalette[i].contains("grass")) {
                    String strippedID = stripBlockStateSuffix(blockPalette[i]);
                    int index = strippedID.indexOf(Key.DEFAULT_SEPARATOR);
                    if (strippedID.substring(index + 1).equals("grass")) {
                        blockPalette[i] = "short_grass";
                    }
                }
            }
        }
        long[] blockData = null;
        if (blockPalette.length > 1) {
            blockData = getLongArray(bb);
        }

        String[] biomePalette = getStringList(bb, MAX_BIOME_PALETTE_SIZE);
        long[] biomeData = null;
        if (biomePalette.length > 1) {
            biomeData = getLongArray(bb);
        }

        PolarSection.LightContent blockLightContent = readLightContent(version, bb);
        byte[] blockLight = blockLightContent == PolarSection.LightContent.PRESENT ? getLightData(bb) : null;
        PolarSection.LightContent skyLightContent = readLightContent(version, bb);
        byte[] skyLight = skyLightContent == PolarSection.LightContent.PRESENT ? getLightData(bb) : null;

        return new PolarSection(
                blockPalette, blockData,
                biomePalette, biomeData,
                blockLightContent, blockLight,
                skyLightContent, skyLight
        );
    }

    private static String stripBlockStateSuffix(String blockId) {
        int bracket = blockId.indexOf('[');
        return bracket == -1 ? blockId : blockId.substring(0, bracket);
    }

    private static PolarSection.@NotNull LightContent readLightContent(short version, @NotNull ByteBuf bb) {
        if (version >= PolarWorld.VERSION_IMPROVED_LIGHT) {
            int id = bb.readUnsignedByte();
            if (id >= PolarSection.LightContent.VALUES.length) {
                throw new IllegalArgumentException("Invalid light content: " + id);
            }
            return PolarSection.LightContent.VALUES[id];
        }
        return bb.readByte() == 1 ? PolarSection.LightContent.PRESENT : PolarSection.LightContent.MISSING;
    }

    private static void fixSignNBT(CompoundTag nbt) {
        CompoundTag frontCompound = nbt.getCompound("front_text").orElse(null);
        CompoundTag backCompound = nbt.getCompound("back_text").orElse(null);
        if (frontCompound == null || backCompound == null) return;
        fixSignMessages(frontCompound.getListOrEmpty("messages"));
        fixSignMessages(backCompound.getListOrEmpty("messages"));
    }

    private static void fixSignMessages(ListTag messages) {
        for (Tag message : messages) {
            String string = message.asString().orElse(null);
            if (!"\"\"".equalsIgnoreCase(string)) return;
        }
        for (int i = 0; i < messages.size(); i++) {
            messages.set(i, StringTag.valueOf(""));
        }
    }

    protected static @NotNull PolarChunk.BlockEntity readBlockEntity(@NotNull PolarDataConverter dataConverter, int dataVersion, @NotNull ByteBuf bb) {
        int posIndex = bb.readInt();
        String id = getStringOptional(bb);

        CompoundTag nbt;
        try (ByteBufInputStream bbis = new ByteBufInputStream(bb)) {
            nbt = new CompoundTag();
            if (bb.readByte() == 1) {
                nbt = (CompoundTag) NbtIo.readAnyTag(bbis, NbtAccounter.create(2 * 1024 * 1024));
                fixSignNBT(nbt);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (dataVersion < dataConverter.dataVersion()) {
            var converted = dataConverter.convertBlockEntityData(id == null ? "" : id, nbt, dataVersion, dataConverter.dataVersion());
            id = converted.getKey();
            if (id.isEmpty()) id = null;
            nbt = converted.getValue();
            if (nbt.isEmpty()) nbt = null;
        }

        return new PolarChunk.BlockEntity(
                posIndex,
                id, nbt
        );
    }

    protected static void validateVersion(int version) {
        var invalidVersionError = String.format("Unsupported Polar version. Versions %d - %d are supported, found %d.",
                PolarWorld.LATEST_VERSION, PolarWorld.MIN_VERSION, version);
        assertThat((version <= PolarWorld.LATEST_VERSION && version >= PolarWorld.MIN_VERSION) || version == PolarWorld.VERSION_DEPRECATED_ENTITIES,
                invalidVersionError);
    }

    protected static @NotNull ByteBuf decompressBuffer(@NotNull ByteBuf buffer, @NotNull PolarWorld.CompressionType compression, int compressedLength) {
        assertThat(compressedLength >= 0, "Invalid uncompressed length: " + compressedLength);
        return switch (compression) {
            case NONE -> Unpooled.wrappedBuffer(buffer);
            case ZSTD -> {
                int length = buffer.readableBytes();

                byte[] bytes = new byte[length];
                buffer.readBytes(bytes);

                long frameContentSize = Zstd.decompressedSize(bytes);
                if (frameContentSize == 0) {
                    assertThat(compressedLength <= MAX_UNDECLARED_UNCOMPRESSED_BYTES,
                            "Uncompressed length is too large and the compressed data does not declare its own: " + compressedLength);
                } else {
                    assertThat(frameContentSize == compressedLength,
                            "Uncompressed length does not match the compressed data: " + compressedLength + " (expected " + frameContentSize + ")");
                }

                byte[] decompressed = Zstd.decompress(bytes, compressedLength);
                yield Unpooled.wrappedBuffer(decompressed);
            }
        };
    }

    @Contract("false, _ -> fail")
    private static void assertThat(boolean condition, @NotNull String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static void validateChunkCount(int chunkCount, ByteBuf data, int sectionCount) {
        int minimumChunkBytes = sectionCount + Integer.BYTES + 4;
        if (chunkCount < 0 || chunkCount > data.readableBytes() / minimumChunkBytes) {
            throw new IllegalArgumentException("Invalid chunk count: " + chunkCount);
        }
    }

}
