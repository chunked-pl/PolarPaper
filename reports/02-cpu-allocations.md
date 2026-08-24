# PolarPaper audit — scanner 2/5: Allocations & CPU hotspots

Scope: all `.java` in `core/`, `src/`, `paper_latest/`, `paper_1_21_11/`, `paper_26_1_2/` (~82 files, ~12k LOC).
Perspective: code runs on the server main thread and inside chunk load/save pipelines at hundreds-of-players scale. Ranked by real-world impact; chunk load/save path weighted hottest.

Overall the codebase is unusually allocation-disciplined for a plugin (no streams, no Optional chains, primitive-packed palettes, cached block-state codec). The remaining issues cluster around: (a) an O(filesize) archive claim path, (b) per-event/per-chunk scheduling churn, (c) one regex + several set copies on migration/save paths.

---

## Findings

### HIGH severity

**H1. Archive `claim()` re-reads and re-decompresses the entire world file per single chunk expansion**
- `core/src/main/java/live/minehub/polarpaper/core/world/PolarChunkArchive.java:71-92` (`claim`) → `:194-203` (`readBodies`) → `:159-174` (`readSourceIndex`) → `:179-192` (`openSource`)
- Every `Polar.loadChunk(...)` expansion calls `claim()`, which calls `readBodies(Set.of(position), …)`. There is **no cache**: each call does `polarSource.readBytes()` (full `Files.readAllBytes` of the `.polar` file), full Zstd decompress (`PolarContentReader.open` → `PolarReader.decompressBuffer`), and a `skipChunkBody` walk over *every* chunk in the file — to hand back one body slice.
- Why it hurts at scale: this is the "player expands/buys a chunk" path. With hundreds of players expanding chunks on a multi-hundred-MB world, each single chunk costs O(file size) disk I/O + O(world) CPU, repeatedly, plus two full-size transient byte[] allocations per call.
- Fix: build the `SourceIndex` once per source generation and cache it on the archive (invalidate/rebuild when `bindSource` is called or after a save replaces the file). `snapshotIncluding()` already demonstrates the index reuse pattern — extend it to claims. Optionally keep only the slices map warm and drop the ByteBuf after use.

**H2. Legacy-world palette fixup compiles a regex per palette entry per section during streaming load**
- `core/src/main/java/live/minehub/polarpaper/core/world/PolarReader.java:279-287`
- For files with `version <= VERSION_SHORT_GRASS` (5): every non-empty section's palette runs `.contains("grass")` then `.split("\\[")`. The two-char pattern `"\\["` does **not** hit `String.split`'s fast path, so `Pattern.compile` + matcher run per palette string, plus a `String[]` allocation per entry.
- Why it hurts: streaming load parses every section of every chunk; a legacy island world = thousands of sections × dozens of palette entries × regex compile — pure waste on the hottest read loop, repeated for every world reload since the data stays version ≤5 forever unless re-saved.
- Fix: replace with `int i = s.indexOf('['); String stripped = i < 0 ? s : s.substring(0, i);` and skip the whole loop early if no entry contains "grass" (single pre-pass).

**H3. One Bukkit scheduler task scheduled per chunk during streaming load**
- `core/src/main/java/live/minehub/polarpaper/core/world/PolarStreamLoader.java:286-288` (`retainChunk`), called from `readChunk` at `:271`
- Each streamed chunk schedules `runTask(plugin, () -> world.addPluginChunkTicket(...))`. Loading a radius world = tens/hundreds of thousands of queued main-thread tasks, each with a captured lambda + task object, all draining through the main thread while players are waiting for the world.
- Fix: accumulate ticket requests and flush them batched (e.g. every N chunks or once per tick via a single drain task), or add tickets directly under the same main-thread unit that already runs `insertChunk`'s continuation where safe.

**H4. Physics/liquid/fade listeners do work per event across *all* worlds, including non-polar ones**
- `src/main/java/live/minehub/polarpaper/PolarListener.java:29-56` (`onBlockFade`, `onBlockFromTo`, `onBlockPhysics`), also `:59-68` (`EntityChangeBlockEvent`)
- `BlockPhysicsEvent` fires for every neighbor-update attempt — routinely tens of thousands per tick on busy servers (hoppers, pistons, water). Each invocation currently allocates a `CraftBlock` via `event.getBlock()`, walks `world → generator → config → gamerules map` and does a `HashMap.getOrDefault` lookup with a boxed default, even for worlds that aren't polar and configs that don't override these rules.
- Why it hurts: this is the only true per-tick steady-state path in the plugin; it taxes every world, not just polar worlds, and scales with total server activity rather than plugin usage.
- Fix: maintain a static `Set<UUID>`/`Set<NamespacedKey>` of polar world keys (updated on load/unload) and check it first; cache the resolved custom-gamerule booleans as primitive fields on `PolarGenerator` (e.g. `boolean physicsEnabled`) so the handler is one map/set probe and one boolean branch.

### MEDIUM severity

**M1. Autosave path copies the archive position set 4–5 times per save**
- `core/src/main/java/live/minehub/polarpaper/core/world/PolarChunkArchive.java:114-116` (`snapshot()` → `Set.copyOf(positions)`), `:140-149` (`snapshotIncluding`: `new HashSet<>(index.slices().keySet())` + 2× `removeAll` + `new HashSet<>(snapshot.positions())` + `Set.copyOf(widened)`), `:95` (`new HashSet<>(archiveSnapshot.positions())`)
- `Polar.saveWorld` (`src/.../Polar.java:827-843`) triggers snapshot → positionsOf (another HashSet) → snapshotIncluding → writer's `archivedChunksToWrite` (`core/.../PolarWriter.java:86-98`, another full copy + `removeAll`). For an archive with ~10⁵ positions that is ~5 full boxed-`Long` set rebuilds (several MB garbage) per autosave per world, all O(n) CPU off-thread but still GC pressure at scale.
- Fix: represent archive positions as a sorted `long[]` (or `LongOpenHashSet`) and implement snapshot/widen/diff as array merges; or at minimum pass the already-copied snapshot through without re-copying in `archivedChunksToWrite`.

**M2. Schematic paste: per-block world→holderManager chain, double-math coords and rotation allocations, all on the main thread**
- `src/main/java/live/minehub/polarpaper/util/BlockUtil.java:31-58` (`setBlockFast`: `(CraftWorld)` cast + `getHandle()` + moonrise scheduler chain + `Math.floor(x / 16.0)` double division ×3 per block); `:193-199` (`rotatePos(Vector3i,…)` allocates a `Vector3d` per call)
- Called per block from `Schematic.pasteSection` (`src/.../schematic/Schematic.java:137-151`) → `Setter.World.setBlock` (`src/.../schematic/Setter.java:63-67`). A world paste = millions of blocks × redundant lookups + 4096 `Vector3d` allocations per section.
- Compounding: `PasteCommand.paste` (`src/.../commands/PasteCommand.java:114-136`) reads + decompresses the whole file and pastes synchronously on the command thread (main thread) — full server freeze proportional to world size.
- Fix: hoist `ServerLevel`/`chunkHolderManager` out of the loops, resolve the chunk holder once per section (blocks are contiguous), use `x >> 4` / `x & 15`, rotate inline without the intermediate `Vector3d`; move parse+paste off-thread into staged main-thread batches.

**M3. Section long-array IO is element-wise through `ByteBuf.readLong()/writeLong()` with per-call bounds checks**
- `core/src/main/java/live/minehub/polarpaper/core/util/ByteArrayUtil.java:36-44` (`getLongArray`), `:153-158` (`writeLongArray`)
- These carry every packed block/biome/light-heightmap array on both the load path (`PolarReader.readSection`) and the write path (`PolarWriter.writeSection`). Per-long virtual dispatch + range check over e.g. 820 longs × 24 sections × thousands of chunks adds up; same for `getVarInt` byte-at-a-time (acceptable) but longs are bulk-shaped data.
- Fix: use `bb.nioBuffer()`/slice + `ByteBuffer.asLongBuffer().get(long[])` (and `put` for writes) to bulk-move the payload in one bounds-checked operation.

**M4. Extra full-size copy of compressed bytes on every file open/decompress**
- `core/src/main/java/live/minehub/polarpaper/core/world/PolarReader.java:384-387` (`decompressBuffer`: `bytes = new byte[length]; buffer.readBytes(bytes)`) before `Zstd.decompress(bytes, …)`
- Every world stream, every archive source-index read, every `PolarChunkStore.read` pays one extra file-sized array copy + allocation. Combined with H1 this multiplies.
- Fix: `Zstd.decompress(ByteBuffer, ByteBuffer)` variants over `bb.nioBuffer()` slices avoid the copy; or decompress straight from the wrapped input buffer when it is array-backed (`hasArray()`).

**M5. `ChunkLight` eagerly allocated per streamed chunk even when its result is discarded**
- `core/src/main/java/live/minehub/polarpaper/core/world/PolarChunkStore.java` n/a; see `core/.../world/ChunkLight.java:25-37` and caller `core/.../world/PolarStreamLoader.java:221`
- Constructor always allocates 2 arrays sized `sections+2` plus 4 padding `SWMRNibbleArray`s; when `preserveStoredLight == false` (cropped loads) everything is thrown away after `lightChunk`. Additionally `emptySections()` (`ChunkLight.java:115-122`) unboxes a vanilla `Boolean[]` per chunk.
- Fix: construct lazily only when `preserveStoredLight` is known true (it is decided by the same loop that builds sections), and keep the Boolean→boolean conversion but note it as vanilla API cost.

**M6. Entity save path: per-entity `ByteArrayOutputStream`/`DataOutputStream` + potential main-thread round-trips inside async save**
- `paper_latest/src/main/java/live/minehub/polarpaper/paper_latest/EntitySerializerImpl.java:52-96` (same shape in `paper_26_1_2/.../EntitySerializerImpl.java:51-94`); driver `src/.../util/EntitiesWorldAccess.java:113-142`
- Each saved entity allocates BAOS+DOS and a `CompletableFuture`; on the fallback path (`saveAsPassenger` throws because an event fired) `saveOnEntityThread` blocks the saving worker up to 10 s per entity waiting for the main thread (`core/.../userdata/EntitySerializer.java:40-79`). Worlds dense in villagers/item frames turn an autosave into a sequence of main-thread stalls.
- Fix: batch entities that need the sync retry into one scheduled task (or mark entity classes known to fire events); serialize NBT into one reusable buffer sized from `compound` estimate.

**M7. `updateConfig` re-parses gamerule keys and copies maps on every autosave**
- `src/main/java/live/minehub/polarpaper/Polar.java:605-627` → `core/.../config/Config.java:209-238` (`Builder.fromWorld`: `Registry.GAME_RULE.get(Key.key("minecraft", name))` per gamerule — Key parsing + registry lookup ×~20 rules) and `Config.java:206` (`new HashMap<>(record.gamerules)` per builder construction, twice per save).
- Small absolute cost, but it is pure repeated-parsing of stable data on a recurring path for every world.
- Fix: memoize `GameRule` lookups in a static `Map<String, GameRule<?>>`; skip `fromWorld` diffing when nothing can have changed between saves (compare time/tick stamp first).

### LOW severity

**L1. Mutable static `DEFAULT_GAMERULES` exposed publicly**
- `core/src/main/java/live/minehub/polarpaper/core/config/Config.java:59-75,102-104` — public mutable `HashMap` (also double-brace init → anonymous class holding outer ref). Any caller mutation corrupts defaults for all worlds. Wrap with `Collections.unmodifiableMap` (copy-on-write for internal builder use).

**L2. `PolarGenerator.getPlaceholderChunks()` defensive-copies the live set per call**
- `core/src/main/java/live/minehub/polarpaper/core/generator/PolarGenerator.java:82-84` — `Set.copyOf` per call; currently called once per save (`Polar.dropPlaceholderChunks`, `src/.../Polar.java:277`), fine today, but the API invites hot-path callers. Return the CHAOS keyset view or document single-shot.

**L3. Regex `replaceAll(".polar$")` per call in name helpers**
- `src/main/java/live/minehub/polarpaper/util/WorldKey.java:22,34`; `commands/PolarCmd.java:125`; `commands/BrowseCommand.java:101` — startup/command-only frequency; use `endsWith`/`substring` if ever moved onto per-world loops.

**L4. `PolarChunkStore.read` zstd-compresses every chunk body individually while building the store**
- `core/src/main/java/live/minehub/polarpaper/core/world/PolarChunkStore.java:44-69` — heavy CPU, currently test-only; fine as-is, just don't put it behind a request path without caching.

**L5. Temp `LevelStorageAccess` lifetime in world creation**
- `paper_1_21_11/src/main/java/live/minehub/polarpaper/paper_1_21_11/NoSaveLevelCreatorImpl.java:112-120` (mirrored in `paper_latest`) — created against `plugin temp folder`; relies on level close to release file handles. Verify `unloadWorld` closes it, else handle/temp dirs accumulate per create/unload cycle.

**L6. Minor steady-state allocations (accepted, listed for completeness)**
- `PolarStreamLoader.readChunk:263-275`: 2 CF stages + lambda captures per chunk (inherent to design).
- `PolarChunk.convertSection:421-447`: `ArrayList` palettes + boxing-free compacting — good; `indexOf(AIR)` once per masked section OK.
- `PolarWorld.chunks` (`ConcurrentHashMap<Long,PolarChunk>`) retains full converted world for non-streaming API users — by design, but worth documenting memory expectations for plugin consumers.
- `PolarListener.onJoin/onBlockBreak:100-125`: `String.format` per wand click — player-frequency only.
- `PolarEntity.toNMSEntity:55,78`: two `Direction[4]` literals allocated per entity — hoist to constants.

---

## Memory-leak review (no critical findings)

- `AUTOSAVE_TASK_MAP` (`Polar.java:49`): cleaned via `WorldUnloadEvent` (`PolarListener.onWorldUnload:71-73`) and failure paths; task no longer pins World after removal. ✔
- `LOADING_WORLDS` (`Polar.java:48`): cleared in `finishOrAbortLoading` on both success/failure branches. ✔
- `BlockStateCodec` caches (`core/.../util/BlockStateCodec.java:27-28`): forward cache bounded at 65 536 entries; reverse bounded by distinct BlockStates. ✔
- `PolarChunkArchive.claimed` (`PolarChunkArchive.java:29`): released via `release`/`abandon`, including the `whenComplete` failure hook (`Polar.java:355-357`). ✔
- `placeholderChunks` (`PolarGenerator.java:28`): persists for never-expanded chunks; holds boxed Longs only, bounded by world radius. Acceptable.
- Watch item L1/L5 above.

---

## Top 10 hottest spots

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| 1 | `PolarChunkArchive.java:71-92,159-203` | Full file read + full Zstd decompress + walk of all chunks **per single archived-chunk claim** (player chunk-expansion path) | HIGH |
| 2 | `PolarReader.java:279-287` | `String.split("\\[")` regex compile per palette entry per section on every legacy-version world load | HIGH |
| 3 | `PolarStreamLoader.java:286-288` | One Bukkit scheduler task + lambda capture per streamed chunk flooding the main-thread queue | HIGH |
| 4 | `PolarListener.java:29-68` | Map lookups + CraftBlock deref chain per BlockPhysics/FromTo/Fade event, taxing all worlds per tick | HIGH |
| 5 | `Schematic.java:115-151` + `BlockUtil.java:31-58,193-199` + `PasteCommand.java:114-136` | Per-block world→holderManager lookups, `Math.floor(double)` coords, per-block `Vector3d` rotation allocs; whole paste synchronous on main thread | MED |
| 6 | `PolarChunkArchive.java:114-116,140-149` + `PolarWriter.java:86-98` + `Polar.java:827-843` | 4–5 full copies of the archive position set per autosave (boxed-Long set churn) | MED |
| 7 | `ByteArrayUtil.java:36-44,153-158` | Element-wise `ByteBuf` long reads/writes with per-call bounds checks on every section of every chunk | MED |
| 8 | `PolarReader.java:384-387` | Extra file-sized byte[] copy before every Zstd decompress (every load + every archive index build) | MED |
| 9 | `ChunkLight.java:25-37,115-122` + `PolarStreamLoader.java:221` | ChunkLight + 4 nibbles allocated per chunk even when discarded; `Boolean[]`→`boolean[]` unbox per chunk | MED |
| 10 | `EntitySerializerImpl.java:52-96` (latest & 26_1) + `EntitySerializer.java:40-79` | Per-entity BAOS/DOS/CF allocations; up-to-10-s main-thread blocking waits per entity inside async save on fallback | MED |

*(runner-up: `Config.fromWorld` Key parsing per gamerule per autosave — M7)*
