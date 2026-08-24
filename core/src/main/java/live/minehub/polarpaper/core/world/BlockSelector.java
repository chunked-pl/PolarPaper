package live.minehub.polarpaper.core.world;

import live.minehub.polarpaper.core.util.CoordConversion;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector2i;
import org.joml.Vector3i;

import java.util.function.Consumer;

public interface BlockSelector {

    @NotNull BlockSelector ALL = new BlockSelector() {
        @Override
        public boolean test(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean test(int index, int chunkX, int chunkZ, int sectionY) {
            return true;
        }

        @Override
        public boolean containsEntireSection(int chunkX, int chunkZ, int sectionY) {
            return true;
        }

        @Override
        public void forEachChunk(Consumer<Vector2i> chunkConsumer) {

        }
    };

    static @NotNull BlockSelector circle(int radius) {
        return circle(0, 0, radius);
    }

    static @NotNull BlockSelector circle(int centerX, int centerZ, int radius) {
        return new BlockSelector() {
            @Override
            public boolean test(int x, int y, int z) {
                return true;
            }

            @Override
            public boolean test(int index, int chunkX, int chunkZ, int sectionY) {
                return true;
            }

            @Override
            public boolean testChunk(int x, int z) {
                int dx = x - centerX;
                int dz = z - centerZ;
                return dx * dx + dz * dz <= radius * radius;
            }

            @Override
            public boolean containsEntireSection(int chunkX, int chunkZ, int sectionY) {
                return true;
            }

            @Override
            public void forEachChunk(Consumer<Vector2i> chunkConsumer) {
                int minX = (centerX - radius) - 1;
                int minZ = (centerZ - radius) - 1;
                int maxX = (centerX + radius) + 1;
                int maxZ = (centerZ + radius) + 1;

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!testChunk(x, z)) continue;
                        chunkConsumer.accept(new Vector2i(x, z));
                    }
                }
            }
        };
    }

    static @NotNull BlockSelector square(int radius) {
        return square(0, 0, radius);
    }

    static @NotNull BlockSelector square(int centerX, int centerZ, int radius) {
        return new BlockSelector() {
            @Override
            public boolean test(int x, int y, int z) {
                return true;
            }

            @Override
            public boolean test(int index, int chunkX, int chunkZ, int sectionY) {
                return true;
            }

            @Override
            public boolean testChunk(int x, int z) {

                long dx = Math.abs(x - centerX);
                long dz = Math.abs(z - centerZ);
                return Math.max(dx, dz) <= radius;
            }

            @Override
            public boolean containsEntireSection(int chunkX, int chunkZ, int sectionY) {
                return true;
            }

            @Override
            public void forEachChunk(Consumer<Vector2i> chunkConsumer) {
                int minX = (centerX - radius) - 1;
                int minZ = (centerZ - radius) - 1;
                int maxX = (centerX + radius) + 1;
                int maxZ = (centerZ + radius) + 1;

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!testChunk(x, z)) continue;
                        chunkConsumer.accept(new Vector2i(x, z));
                    }
                }
            }
        };
    }

    static @NotNull BlockSelector horizontalCircle(int centerBlockX, int centerBlockZ, int radiusBlocks) {
        if (radiusBlocks < 0) throw new IllegalArgumentException("Radius cannot be negative: " + radiusBlocks);

        final long radiusSquared = (long) radiusBlocks * radiusBlocks;
        return new BlockSelector() {
            @Override
            public boolean test(int x, int y, int z) {
                long dx = (long) x - centerBlockX;
                long dz = (long) z - centerBlockZ;
                return dx * dx + dz * dz <= radiusSquared;
            }

            @Override
            public boolean testChunk(int chunkX, int chunkZ) {
                long minX = (long) chunkX * 16;
                long minZ = (long) chunkZ * 16;
                long nearestX = clamp(centerBlockX, minX, minX + 15);
                long nearestZ = clamp(centerBlockZ, minZ, minZ + 15);
                long dx = nearestX - centerBlockX;
                long dz = nearestZ - centerBlockZ;
                return dx * dx + dz * dz <= radiusSquared;
            }

            @Override
            public boolean containsEntireSection(int chunkX, int chunkZ, int sectionY) {
                long minX = (long) chunkX * 16;
                long minZ = (long) chunkZ * 16;
                long farthestX = Math.max(Math.abs(minX - centerBlockX), Math.abs(minX + 15 - centerBlockX));
                long farthestZ = Math.max(Math.abs(minZ - centerBlockZ), Math.abs(minZ + 15 - centerBlockZ));
                return farthestX * farthestX + farthestZ * farthestZ <= radiusSquared;
            }

            @Override
            public void forEachChunk(Consumer<Vector2i> chunkConsumer) {
                int minChunkX = (int) Math.floorDiv((long) centerBlockX - radiusBlocks, 16L);
                int minChunkZ = (int) Math.floorDiv((long) centerBlockZ - radiusBlocks, 16L);
                int maxChunkX = (int) Math.floorDiv((long) centerBlockX + radiusBlocks, 16L);
                int maxChunkZ = (int) Math.floorDiv((long) centerBlockZ + radiusBlocks, 16L);

                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                        if (testChunk(chunkX, chunkZ)) chunkConsumer.accept(new Vector2i(chunkX, chunkZ));
                    }
                }
            }
        };
    }

    static @NotNull BlockSelector intersection(@NotNull BlockSelector first, @NotNull BlockSelector second) {
        if (first == ALL) return second;
        if (second == ALL) return first;

        return new BlockSelector() {
            @Override
            public boolean test(int x, int y, int z) {
                return first.test(x, y, z) && second.test(x, y, z);
            }

            @Override
            public boolean test(int index, int chunkX, int chunkZ, int sectionY) {
                return first.test(index, chunkX, chunkZ, sectionY)
                        && second.test(index, chunkX, chunkZ, sectionY);
            }

            @Override
            public boolean testChunk(int chunkX, int chunkZ) {
                return first.testChunk(chunkX, chunkZ) && second.testChunk(chunkX, chunkZ);
            }

            @Override
            public boolean containsEntireSection(int chunkX, int chunkZ, int sectionY) {
                return first.containsEntireSection(chunkX, chunkZ, sectionY)
                        && second.containsEntireSection(chunkX, chunkZ, sectionY);
            }

            @Override
            public void forEachChunk(Consumer<Vector2i> chunkConsumer) {
                first.forEachChunk(chunk -> {
                    if (second.testChunk(chunk.x, chunk.y)) chunkConsumer.accept(chunk);
                });
            }
        };
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    boolean test(int x, int y, int z);

    default boolean test(int index, int chunkX, int chunkZ, int sectionY) {
        return test(
                CoordConversion.sectionBlockIndexGetX(index) + chunkX * 16,
                CoordConversion.sectionBlockIndexGetY(index) + sectionY * 16,
                CoordConversion.sectionBlockIndexGetZ(index) + chunkZ * 16
        );
    }

    default boolean testChunk(int chunkX, int chunkZ) {
        return true;
    }

    default boolean containsEntireSection(int chunkX, int chunkZ, int sectionY) {
        return false;
    }

    void forEachChunk(Consumer<Vector2i> chunkConsumer);

    record RegionBlockSelector(Vector3i min, Vector3i max) implements BlockSelector {
        public static RegionBlockSelector fromCorners(Vector3i corner1, Vector3i corner2) {
            return new RegionBlockSelector(corner1.min(corner2, new Vector3i()), corner1.max(corner2, new Vector3i()));
        }

        @Override
        public boolean test(int x, int y, int z) {
            return min.x <= x && max.x >= x &&
                    min.y <= y && max.y >= y &&
                    min.z <= z && max.z >= z;
        }

        @Override
        public boolean testChunk(int chunkX, int chunkZ) {
            int minX = chunkX * 16;
            int maxX = minX + 16;
            int minZ = chunkZ * 16;
            int maxZ = minZ + 16;
            return min.x <= maxX && max.x >= minX &&
                    min.z <= maxZ && max.z >= minZ;
        }

        @Override
        public boolean containsEntireSection(int chunkX, int chunkZ, int sectionY) {
            int minX = chunkX * 16;
            int minY = sectionY * 16;
            int minZ = chunkZ * 16;
            return min.x <= minX && max.x >= minX + 15 &&
                    min.y <= minY && max.y >= minY + 15 &&
                    min.z <= minZ && max.z >= minZ + 15;
        }

        @Override
        public void forEachChunk(Consumer<Vector2i> chunkConsumer) {
            int minX = (min().x / 16) - 1;
            int minZ = (min().z / 16) - 1;
            int maxX = (max().x / 16) + 1;
            int maxZ = (max().z / 16) + 1;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!testChunk(x, z)) continue;
                    chunkConsumer.accept(new Vector2i(x, z));
                }
            }
        }
    }

}
