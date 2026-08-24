package live.minehub.polarpaper.schematic;

import io.netty.buffer.Unpooled;
import live.minehub.polarpaper.PolarPaper;
import live.minehub.polarpaper.core.userdata.EntityUtil;
import live.minehub.polarpaper.core.userdata.WorldUserData;
import live.minehub.polarpaper.core.util.BlockStateCodec;
import live.minehub.polarpaper.core.util.CoordConversion;
import live.minehub.polarpaper.core.util.PaletteUtil;
import live.minehub.polarpaper.core.world.PolarChunk;
import live.minehub.polarpaper.core.world.PolarEntity;
import live.minehub.polarpaper.core.world.PolarSection;
import live.minehub.polarpaper.core.world.PolarWorld;
import live.minehub.polarpaper.util.BlockUtil;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector2i;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Schematic {

    private static final Logger LOGGER = LoggerFactory.getLogger(Schematic.class);
    private static final long BATCH_BUDGET_NANOS = 8_000_000L;
    private static final String BUSY_MESSAGE = "A schematic paste is already in progress";

    public static final NamespacedKey POS_1_KEY = new NamespacedKey("polarpaper", "pos1");
    public static final NamespacedKey POS_2_KEY = new NamespacedKey("polarpaper", "pos2");

    private static volatile boolean pasteRunning;

    public static void paste(PolarWorld polarWorld, Setter setter, Vector3i pasteOffset, Rotation rotation, IgnoreAir ignoreAir) {
        try {
            pasteAsync(polarWorld, setter, pasteOffset, rotation, ignoreAir).join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw e;
        }
    }

    public static CompletableFuture<Void> pasteAsync(PolarWorld polarWorld, Setter setter, Vector3i pasteOffset, Rotation rotation, IgnoreAir ignoreAir) {
        if (pasteRunning) return CompletableFuture.failedFuture(new IllegalStateException(BUSY_MESSAGE));
        pasteRunning = true;

        byte[] userData = polarWorld.userData();
        Vector3i readOffset = WorldUserData.readSchematicOffset(userData);
        Vector3i offset = readOffset == null ? new Vector3i() : readOffset;

        int minSection = polarWorld.minSection();

        CompletableFuture<Void> done = new CompletableFuture<>();
        Iterator<PolarChunk> chunks = List.copyOf(polarWorld.chunks()).iterator();
        Map<Vector3i, PolarChunk.BlockEntity> blockEntityMap = new HashMap<>();

        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(PolarPaper.getPlugin(), () -> {
            long deadline = System.nanoTime() + BATCH_BUDGET_NANOS;
            try {
                while (System.nanoTime() < deadline) {
                    if (!chunks.hasNext()) {
                        holder[0].cancel();
                        finishPaste(polarWorld, setter, pasteOffset, rotation, offset, blockEntityMap);
                        done.complete(null);
                        return;
                    }
                    pasteChunk(chunks.next(), setter, pasteOffset, rotation, offset, minSection, ignoreAir, blockEntityMap);
                }
            } catch (Throwable throwable) {
                holder[0].cancel();
                LOGGER.error("Schematic paste failed halfway through; the world is partially pasted", throwable);
                pasteRunning = false;
                done.completeExceptionally(throwable);
            }
        }, 0L, 1L);

        return done.whenComplete((_, _) -> pasteRunning = false);
    }

    private static void pasteChunk(PolarChunk chunk, Setter setter, Vector3i pasteOffset, Rotation rotation,
                                   Vector3i offset, int minSection, IgnoreAir ignoreAir,
                                   Map<Vector3i, PolarChunk.BlockEntity> blockEntityMap) {
        int i = 0;
        for (PolarSection section : chunk.sections()) {
            Vector3i blockOffset = new Vector3i(chunk.x() * 16, (i + minSection) * 16, chunk.z() * 16)
                    .sub(offset);
            boolean shouldPaste = setter.shouldPaste(chunk, section, i + minSection, blockOffset);
            i++;
            if (!shouldPaste) continue;

            pasteSection(section, setter, blockOffset, pasteOffset, rotation, ignoreAir);
        }

        handleUserData(setter, pasteOffset, rotation, chunk, offset);

        for (PolarChunk.BlockEntity blockEntity : chunk.blockEntities()) {
            int x = CoordConversion.chunkBlockIndexGetX(blockEntity.index());
            int y = CoordConversion.chunkBlockIndexGetY(blockEntity.index());
            int z = CoordConversion.chunkBlockIndexGetZ(blockEntity.index());

            Vector3i blockOffset = new Vector3i(chunk.x() * 16, 0, chunk.z() * 16).sub(offset).add(x, y, z);
            BlockUtil.rotatePos(blockOffset, rotation);
            blockOffset.add(pasteOffset);

            blockEntityMap.put(blockOffset, blockEntity);
        }
    }

    private static void finishPaste(PolarWorld polarWorld, Setter setter, Vector3i pasteOffset, Rotation rotation,
                                    Vector3i offset, Map<Vector3i, PolarChunk.BlockEntity> blockEntityMap) {
        Bukkit.getScheduler().runTask(PolarPaper.getPlugin(), () -> {
            for (Map.Entry<Vector3i, PolarChunk.BlockEntity> entry : blockEntityMap.entrySet()) {
                Vector3i blockOffset = entry.getKey();
                PolarChunk.BlockEntity blockEntity = entry.getValue();
                setter.setBlockEntity(blockOffset.x, blockOffset.y, blockOffset.z, blockEntity);
            }

            Set<Vector2i> chunksToRefresh = new HashSet<>();
            for (PolarChunk chunk : polarWorld.chunks()) {
                Vector3i chunkOffset = new Vector3i(chunk.x() * 16, 0, chunk.z() * 16)
                        .sub(offset);
                BlockUtil.rotatePos(chunkOffset, rotation);
                chunkOffset.add(pasteOffset.x, 0, pasteOffset.z);

                int cX = (int)Math.floor(chunkOffset.x / 16.0);
                int cZ = (int)Math.floor(chunkOffset.z / 16.0);

                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        chunksToRefresh.add(new Vector2i(cX + x, cZ + z));
                    }
                }
            }

            if (setter instanceof Setter.World worldSetter) worldSetter.refreshChunks(chunksToRefresh);
        });
    }

    private static void handleUserData(Setter setter, Vector3i pasteOffset, Rotation rotation, PolarChunk chunk, Vector3i offset) {
        if (chunk.userData() == null || chunk.userData().length == 0) return;

        final var bb = Unpooled.wrappedBuffer(chunk.userData());
        byte version = bb.readByte();
        List<PolarEntity> entities = EntityUtil.getEntities(bb);

        for (PolarEntity polarEntity : entities) {
            Location spawnLocation = polarEntity.getLocation(null, chunk.x(), chunk.z());
            spawnLocation.subtract(offset.x, offset.y, offset.z);
            BlockUtil.rotateLoc(spawnLocation, rotation);
            spawnLocation.add(pasteOffset.x, pasteOffset.y, pasteOffset.z);

            switch (rotation) {
                case CLOCKWISE_90 -> spawnLocation.add(1, 0, 0);
                case CLOCKWISE_180 -> spawnLocation.add(1, 0, 1);
                case CLOCKWISE_270 -> spawnLocation.add(0, 0, 1);
            }

            setter.spawnEntity(polarEntity, spawnLocation);
        }
    }

    private static void pasteSection(PolarSection polarSection, Setter setter, Vector3i offset, Vector3i pasteOffset, Rotation rotation, IgnoreAir ignoreAir) {

        long[] blockDataLongs = polarSection.blockData();
        int blockDataBits = blockDataLongs == null ? 0 : PaletteUtil.getBitsForLongLength(blockDataLongs.length, PolarSection.BLOCK_PALETTE_SIZE);
        int[] blockData = new int[PolarSection.BLOCK_PALETTE_SIZE];
        if (blockDataBits > 0) {
            PaletteUtil.unpack(blockData, blockDataLongs, blockDataBits);
        }

        String[] rawBlockPalette = polarSection.blockPalette();
        BlockState[] materialPalette = new BlockState[rawBlockPalette.length];
        for (int i = 0; i < rawBlockPalette.length; i++) {
            materialPalette[i] = BlockStateCodec.fromPaletteString(rawBlockPalette[i]).rotate(rotation.getMcRot());
        }

        boolean uniformSection = rawBlockPalette.length <= 1;
        if (uniformSection && materialPalette[0].isAir() && ignoreAir != IgnoreAir.NONE) return;

        Vector3i blockPos = new Vector3i();
        int blockIndex = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState blockState = materialPalette[uniformSection ? 0 : blockData[blockIndex]];
                    blockIndex++;
                    if (ignoreAir == IgnoreAir.ALL && blockState.isAir()) continue;

                    blockPos.set(x, y, z).add(offset);
                    BlockUtil.rotatePos(blockPos, rotation);
                    blockPos.add(pasteOffset);

                    setter.setBlock(blockPos.x, blockPos.y, blockPos.z, blockState);
                }
            }
        }
    }

    public enum IgnoreAir {

        ALL,

        EMPTY_SECTION,

        NONE
    }

}
