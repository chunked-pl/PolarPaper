# PolarPaper — Scanner 1/5: Code Quality & House Law Audit

Scope: all `.java` files under `core/`, `src/`, `paper_latest/`, `paper_1_21_11/`, `paper_26_1_2/` (82 files, ~12.1k LOC). No source files were modified.

House law checked: no comments/javadocs, no `var`, final where sensible, JetBrains nullability on parameters, early returns, defensive coding, precise names (no Manager/Helper/vague one-worders), Java 25 idioms where natural, no dead code, DRY, KISS, minimal API surface, consistent formatting.

Severity legend: **BLOCKER** = violates a hard house rule or correctness risk; **MAJOR** = real maintenance/correctness debt; **MINOR** = clear improvement, low risk; **NIT** = style polish.

---

## A. House-law violation totals (headline numbers)

| Rule | Count | Where |
|---|---|---|
| Comment/javadoc lines | **1,170** | see section E |
| `var` usages | **24** | 20 declarations + 3 for-each + 1 try-with-resources |
| Commented-out dead code blocks | **14 blocks** | PolarWorld:37–39, PolarConstants:8–10+19–34, PolarReader:70+77, PolarStreamLoader:495–496+502–503, PolarEntity:31, PolarWorldAccess:29–50, CommandManager:55–58, NoSaveLevelCreatorImpl(latest):60, NoSaveLevelCreatorImpl(1.21.11):78+202+221–223 |
| Dead classes/methods | **6** | 4 command classes, `PolarStreamLoader.assertThat`, `PolarConstants` (mostly) |
| Public mutable static state | **7 fields** | PolarWorld:46–47, LightUtil:14–15, PolarSection.LightContent.VALUES, EntitiesWorldAccess n/a |
| Regex `.polar$` bug (`.` = any char) | **3 sites** | WorldKey:22+34, BrowseCommand:101 |

---

## B. Findings — core/

### core/world

- `core/src/main/java/live/minehub/polarpaper/core/world/PolarConstants.java:1-42` | **BLOCKER** | Class is almost entirely dead code duplicating constants that live canonically in `PolarWorld` (MAGIC_NUMBER, LATEST_VERSION, MIN_VERSION, VERSION_*), `PolarChunk` (HEIGHTMAP_SIZE, MAX_HEIGHTMAPS) and `PolarSection` (BLOCK_PALETTE_SIZE, BIOME_PALETTE_SIZE). Only `MAGIC_NUMBER`, `VERSION_DATA_CONVERTER`, `VERSION_WORLD_USERDATA` are referenced (by `PolarContentReader`). Also contains 28 lines of commented-out constants (lines 8–10, 19–34) and a non-final mutable `DEFAULT_COMPRESSION`. | Fix: delete the class; move the three used constants into `PolarWorld` (or keep a minimal `PolarConstants` with exactly those three as `public static final`) and point `PolarContentReader` at it. Single source of truth per format constant.

- `core/.../world/PolarWorld.java:46-47` | **BLOCKER** | `public static CompressionType DEFAULT_COMPRESSION` and `public static int DEFAULT_COMPRESSION_LEVEL` are public **non-final** globals — any code can silently change compression defaults for every world written afterwards. | Fix: make them `final`; if runtime override is genuinely needed, route it through `Config`.

- `core/.../util/LightUtil.java:14-15` | **MAJOR** | `EMPTY_CONTENT` / `FULLY_LIT_CONTENT` are public static **mutable byte[2048] arrays** exposed to the whole codebase; one stray write corrupts light decoding globally. | Fix: make them `private static final`, or wrap in `List.of(...)`/return clones.

- `core/.../world/PolarWorld.java:99-100` | **MINOR** | `for (var chunk : chunks)` / `var index` — `var` banned by house law. | Fix: explicit types `PolarChunk` / `long`.

- `core/.../world/PolarWorld.java:162` | **NIT** | `chunks.getOrDefault(index, null)` is just `chunks.get(index)`. | Fix: use `get`.

- `core/.../world/PolarWorld.java:197-223` | **MINOR** | Three `updateChunks` overloads carry four copies of the same javadoc text. | Fix (with comment removal anyway): collapse to the most specific overload plus defaults via an overload chain without docs.

- `core/.../world/PolarWorld.java:247` | **NIT** | Missing space: `List.of(),loadChunks`. Formatting inconsistency.

- `core/.../world/PolarWorld.java:352` | **MINOR** | Fully-qualified `new java.util.LinkedHashMap<>()` mid-file while other imports are normal. | Fix: import `LinkedHashMap`.

- `core/.../world/PolarWorld.java:44` | **MINOR** | `VERSION_DEPRECATED_ENTITIES = 8` sits above `LATEST_VERSION = 7`; intentional (read-compat) but undocumented now that comments must go — rename to encode intent, e.g. `READ_ONLY_VERSION_REMOVED_ENTITIES`, so the name carries the explanation.

- `core/.../world/PolarReader.java:104,116,172,238,251,270,343,372,379` | **MAJOR** | Nine `protected static` members but **no subclass of `PolarReader` exists anywhere** in the repo (verified by grep). Protected is wider than needed. | Fix: drop to `private static` (keep `readChunkBody`/`skipChunkBody`/`decompressBuffer`/`validateVersion` package-private if cross-class use inside `world` package stays).

- `core/.../world/PolarReader.java:359,373` | **MINOR** | `var converted`, `var invalidVersionError`. | Fix: explicit types.

- `core/.../world/PolarReader.java:255-256` | **NIT** | Odd line break splits `if (...)` from `continue;` — inconsistent formatting vs the rest of the file.

- `core/.../world/PolarReader.java:23` | **NIT** | Utility class: not `final`, no private constructor (contrast `PolarWriter`). | Fix: `public final class PolarReader { private PolarReader() {} }`.

- `core/.../world/PolarReader.java` vs `core/.../world/PolarContentReader.java:23-59` | **MAJOR** | Header parsing (magic → version → dataVersion → compression → min/max section → userData) implemented twice: `PolarReader.read()` lines 60–101 and `PolarContentReader.open()`. They even read version constants from different homes (`PolarWorld.*` vs `PolarConstants.*`) and use different exception styles (`assertThat` vs inline `if throw`). | Fix: have `PolarReader.read` delegate its prologue to `PolarContentReader.open` (or extract one `readHeader`).

- `core/.../world/PolarReader.java:413-418` vs `core/.../world/PolarStreamLoader.java:474-481` | **MAJOR** | `validateChunkCount` is byte-for-byte duplicated (including the lower-bound arithmetic). | Fix: keep one (package-private in `PolarReader`), call from the loader.

- `core/.../world/PolarWriter.java:111,116,130,146,149,154,157` | **MINOR** | Seven `var`s. | Fix: explicit types (`PolarSection`, `PolarChunk.BlockEntity`, `int[]`, `String[]`, `long[]`).

- `core/.../world/PolarWriter.java:120-135` | **NIT** | Bare `{ ... }` scoping block around heightmap writing is unusual; extract `writeHeightmaps(bb, chunk, bitsPerEntry)` instead.

- `core/.../world/PolarChunkStore.java:51-65` vs `core/.../world/PolarChunkArchive.java:164-173` | **MAJOR** | Identical "walk chunks: read x/z varints, mark start, skipChunkBody, record [start,length]" slice-building loops. | Fix: extract one `chunkSliceIndex(Content): Map<Long,int[]>` (e.g. static on `PolarContentReader`) used by both.

- `core/.../world/PolarStreamLoader.java:522-525` | **MAJOR** | `private static void assertThat(...)` is never called in the file — dead code (copy-paste from `PolarReader`). | Fix: delete.

- `core/.../world/PolarStreamLoader.java:494-504` | **MAJOR** | Two commented-out logging/throw blocks inside `addBlockEntity`. Silent `return` when a block has no `BlockEntity` hides data loss. | Fix: delete commented code; add a `LOGGER.warn` on the unexpected-null paths (defensive coding rule cuts both ways).

- `core/.../world/PolarStreamLoader.java:507` | **MINOR** | `var registryAccess`. | Fix: explicit type.

- `core/.../world/PolarStreamLoader.java:221-246` vs `core/.../world/PolarChunk.java:105-132` | **MAJOR** | The "build LevelChunkSections, mask unselected boundary sections, conditionally preserve stored light" sequence is implemented twice (`readChunk` here and `PolarChunk.createLevelChunk`). They already share `maskOutsideSelection`; the section-build + light-preserve skeleton can be shared too. | Fix: extract a `buildSections(level, sections, selector, chunkX, chunkZ)` helper returning `(levelChunkSections, preserveStoredLight)`.

- `core/.../world/PolarStreamLoader.java:62` | **NIT** | Class not `final` despite being a pure-static utility with private ctor. | Fix: `final`.

- `core/.../world/PolarChunk.java:68` | **NIT** | Trailing `// Chunk Size X * Chunk Size Z` comment (must go with the global comment purge; the name `HEIGHTMAP_SIZE = 16 * 16` already says it).

- `core/.../world/PolarChunk.java:71-72` vs `core/.../world/PolarSection.java:65,67,101,103` | **MINOR** | `"minecraft:air"` / `"minecraft:plains"` palette literals duplicated across two classes; `PolarChunk` even defines constants (`AIR_PALETTE_ENTRY`, `DEFAULT_BIOME_PALETTE_ENTRY`) that `PolarSection` doesn't use. | Fix: hoist the two constants into `PolarSection` (owner of the format default) and reference everywhere.

- `core/.../world/PolarChunk.java:538` | **MINOR** | `isChunkEmpty(NewChunkHolder)` is `public static` but has no callers outside this file/package contract. | Fix: package-private.

- `core/.../world/PolarEntity.java:55,78` | **MINOR** | `Direction[] {SOUTH, WEST, NORTH, EAST}` array literal built twice per call site. | Fix: one `private static final Direction[] HORIZONTALS` (order matters — name it `YAW_INDEX_ORDER`).

- `core/.../world/PolarEntity.java:29-33` | **MINOR** | `IOException` swallowed with a commented-out log line; entity silently vanishes on load. | Fix: delete comment; `LOGGER.warn("Unreadable entity bytes", _)` before returning null (defensive coding requires visibility).

- `core/.../world/PolarEntity.java:69-70,82-83` | **NIT** | `compound.put("block_pos", new IntArrayTag(...blockPos...))` duplicated in painting and item-frame branches. | Fix: small `applyBlockPos(compound, spawnLocation)` local.

- `core/.../world/PolarEntity.java:86-89` | **NIT** | `if (nmsEntity == null) return null; return nmsEntity;` → `return nmsEntity;` directly.

- `core/.../world/PolarSection.java:43-47` | **MINOR** | `LightContent.VALUES` is a public mutable enum-array. | Fix: make it `private static final` and expose `fromId(int)` like `CompressionType` does (reader currently bounds-checks against `.length` manually at PolarReader:317).

- `core/.../world/PolarSection.java:124-127,137-140,146-149,155-158` | **NIT** | Accessors rely on `assert` for precondition checks — disabled by default at runtime, so misuse returns null and NPEs far away. | Fix: `Objects.requireNonNull(..., msg)` or explicit `IllegalStateException` (defensive coding).

- `core/.../world/BlockSelector.java:63-75,109-121,284-296` | **MAJOR** | `forEachChunk` bounding-box double loop is copy-pasted three times (circle, square, RegionBlockSelector) — identical modulo the `testChunk` predicate. | Fix: one private/static helper `forEachChunkInBounds(minX,minZ,maxX,maxZ,selector,consumer)`; anonymous selectors call it.

- `core/.../world/BlockSelector.java:12-32` | **NIT** | `ALL` anonymous class overrides the 4-arg `test(int,int,int,int)` although the interface provides a default that delegates — redundant noise. | Fix: delete the override.

- `core/.../config/Config.java:20-38,148-153,240-244,260-262,268-270,294-297,303-306,317-322,328-330,412-419` etc. | **BLOCKER** (house law) | Extensive javadoc on the record, builder methods and nested classes. | Removed in the cleanup task; ensure parameter meaning migrates into names (e.g. `autoSaveIntervalTicks` is fine; `-1 disables` belongs in the property description strings which are user-facing config comments, not source comments).

- `core/.../config/Config.java:59-75` | **MAJOR** | Double-brace initialization `new HashMap<>() {{ ... }}` creates an anonymous-inner-class holding a reference to the enclosing instance (leak pattern) and is banned idiom. | Fix: `static Map<String,Object> defaultGamerules() { Map<...> m = new HashMap<>(); ... return Map.copyOf(m)? }` or build in a static method. Note values include non-string types, so a plain builder method is right.

- `core/.../config/Config.java:102-104` | **MINOR** | `getDefaultGamerules()` hands out the live mutable `DEFAULT_GAMERULES` map (callers in both NoSaveLevelCreatorImpls only read, but nothing prevents writes). | Fix: return `Map.copyOf(DEFAULT_GAMERULES)` or `Collections.unmodifiableMap`.

- `core/.../config/Config.java:168` | **MINOR** | `@SuppressWarnings("unused")` on `Builder` — masks genuine dead-API detection. | Fix: remove; if some builder setters are truly unused, delete those setters.

- `core/.../config/Config.java:432-435` | **MINOR** | Unchecked cast `(T) config.get(fullPath)` with no `@SuppressWarnings`/justification; works only because callers catch `ClassCastException`. | Fix: cast through `Object` explicitly with a named local, keeping the documented fallback behavior in `apply`.

- `core/.../config/Config.java:460-512` | **DRY MINOR** | `EnumProperty.write` re-implements `Property.write`'s "skip if equals default" logic and hand-rolls a StringBuilder join. | Fix: call `super.write`-style shared routine; replace loop with `Arrays.stream(enums).map(Enum::name).collect(Collectors.joining(", "))` prefixed `"One of: "`.

- `core/.../config/Config.java` | **NIT** | Mixed annotation libraries in one file: `org.jetbrains.@NotNull/@Nullable` and `org.jspecify.@NonNull` (line 10, 447). Project norm is JetBrains. | Fix: standardize on JetBrains annotations repo-wide (see also NoUnloadLevelChunk:14, PolarEntitySpawnEvent:9).

- `core/.../generator/PolarStreamingGenerator.java:18-19` | **MINOR** | Nullable boxed `Short version` / `Integer dataVersion` force null-checks at every consumer (Polar.loadChunk:312). | Fix: primitive with sentinel (`-1`) or a tiny `record StreamOrigin(short version, int dataVersion)` nullable once.

- `core/.../generator/PolarStreamingGenerator.java:97` | **NIT** | `((AbstractBuilder<TextComponent>)builder).build()` cast also present in CommandManager:78, ListCommand:96, BrowseCommand:176. If `build()` alone doesn't resolve, prefer `builder.build()` typed as `TextComponent` via explicit variable; otherwise isolate the cast behind one `private static TextComponent build(Component.Builder<?> b)` helper (DRY).

- `core/.../source/PolarSource.java:4-6` | **MINOR** | `throws Exception` on interface methods forces every caller into `catch (Exception)`. | Fix: `throws IOException` (both impls only throw IO-ish failures; `FilePolarSource` throws `IOException`, `delete` throws `UnsupportedOperationException` which is unchecked).

- `core/.../source/BytesPolarSource.java:3-31` | **MINOR** | `@SuppressWarnings("unused")` on the class; redundant ctor/getter/setter quadruplet (`new BytesPolarSource()`, `(byte[])`, `bytes()`, `bytes(byte[])`) — only the 1-arg ctor and `bytes()` are used. | Fix: reduce to ctor + `bytes()`; drop the suppression; candidate `final class` with defensive clone if immutability desired.

- `core/.../userdata/EntityUtil.java:53-58,82-83` | **MINOR** | Seven `final var` sites — double violation (var + inconsistent `final` locals used nowhere else in the project). | Fix: explicit types, no `final`.

- `core/.../util/ByteArrayUtil.java:111-128` | **MAJOR** | Domain-specific `writeBlockEntity(PolarChunk.BlockEntity)` lives in a generic byte-buffer util — layering/naming smell ("name says what it does": this isn't array IO, it's format serialization). | Fix: move to `PolarWriter` (its only caller) next to `writeSection`.

- `core/.../util/ByteArrayUtil.java:160-165` vs `167-172` | **NIT** | `writeStrings(Collection)` and `writeStringArray(String[])` are the same loop twice; `writeStrings` has no callers. | Fix: delete `writeStrings` (dead), keep array variant.

- `core/.../util/ByteArrayUtil.java` (all read methods) | **MINOR** | Parameters lack `@NotNull ByteBuf bb` while e.g. `writeBlockEntity` annotates — inconsistent with project norm. | Fix: annotate consistently (JetBrains) across all public signatures.

- `core/.../util/ByteArrayUtil.java:52` | **NIT** | Orphan `// Copyright 2019 Google LLC` above `getVarInt` — attribution without license text, and a comment under house law. Provenance belongs in NOTICE/LICENSE, not a floating comment.

- `core/.../util/ByteArrayUtil.java:14` | **NIT** | Not `final`, no private ctor (contrast `PaletteUtil`). Same for `LightUtil` (11), `CoordConversion` (3), `WorldUsage` is a record (ok).

- `core/.../util/CoordConversion.java:19-22,67-72` | **NIT** | Comments explaining bit math; after purge, rename for clarity instead: e.g. keep method names, they're already good (`globalToChunk`, `sectionIndex`).

- `core/.../util/PaletteUtil.java:38-43,68-69` | **NIT** | `final` locals unique to this file — inconsistent with codebase norm. Drop them.

- `core/.../util/LightUtil.java:56-64` | **NIT** | `if … else if … else return` chain after returns; flatten (early returns).

- `core/.../util/TaskFutures.java:17,27` | **NIT** | Parameter named `runnable` is a `Supplier<T>`. | Fix: rename `supplier` / `task`.

### core misc

- `core/src/main/java/live/minehub/polarpaper/core/world/PolarWorldAccess.java:25` | **MINOR** | `@SuppressWarnings("unused")` on the whole interface hides dead default methods from analysis. Remove; individually assess `loadHeightmaps`/`populateChunkData` usage.
- `core/.../event/PolarEntitySpawnEvent.java:11` | **MINOR** | `@SuppressWarnings("unused")` — Bukkit events are invoked reflectively; use `@ApiStatus.NonExtendable`-style intent or nothing; the suppression adds nothing.
- `core/.../NoSaveLevelCreator.java:16` | **NIT** | Interface exposes `Logger LOGGER` as API surface; both impls reference it. Prefer each impl owning its logger (as `paper_1_21_11` already does at line 65) and drop the constant.

---

## C. Findings — src/ (plugin)

### Root / listener / loader

- `src/main/java/live/minehub/polarpaper/Polar.java:482` | **MAJOR** | `worldName.toLowerCase()` without `Locale` — locale-sensitive (Turkish i) corruption of world names/keys. | Fix: `toLowerCase(Locale.ROOT)`.
- `src/main/java/live/minehub/polarpaper/Polar.java:43` | **MINOR** | `@SuppressWarnings("unused")` on the entire facade class. Remove.
- `src/.../Polar.java:412-417` | **MINOR** | Redundant fully-qualified names (`java.util.Set`, `java.util.HashSet`, `live.minehub.polarpaper.core.util.CoordConversion`) though imports exist (lines 10, 36). Same at 261 (`java.util.concurrent.CompletionException`). | Fix: use imported names.
- `src/.../Polar.java:687-705` vs `core/.../source/FilePolarSource.java:15-36` | **MAJOR** | Atomic temp-file+move write (incl. `AtomicMoveNotSupportedException` fallback) implemented twice. | Fix: extract `FilesX.writeAtomically(Path target, byte[]/String content)` into core util; both call it.
- `src/.../Polar.java:553-556,570-573,580-583` | **MINOR** | Triple-duplicated "broadcast to players with polar.notifications" loop inside `startAutoSaveTask`. | Fix: `notifyPlayers(String msg, NamedTextColor color)`.
- `src/.../Polar.java:723` | **NIT** | `org.bukkit.Difficulty.valueOf(config.difficulty().name())` round-trips a value that is already an `org.bukkit.Difficulty` (Config field type). | Fix: `config.difficulty()` directly.
- `src/.../Polar.java` (saveWorld family) | **NIT** | Four public overloads + `saveWorldSynchronously`; fine for a library facade, but `saveWorld(World, PolarSource)` (line 764) carries `@SuppressWarnings("unused")` — either it is API (drop suppression) or dead (remove).
- `src/main/java/live/minehub/polarpaper/PolarPaper.java:50` | **MINOR** | `try (var files = ...)`. Explicit `Stream<Path>`.
- `src/.../PolarPaper.java:66-72,86-92` | **MAJOR** | Stack-trace-to-string via `StringWriter`+`printStackTrace` twice, then logged as two warnings — loses SLF4J structured throwable. | Fix: `getLogger().log(Level.SEVERE, "Failed to load worlds on startup", e)` (the SEVERE form is already used correctly at line 112). Delete both StringWriter blocks.
- `src/.../PolarPaper.java:112` | **NIT** | Fully-qualified `java.util.logging.Level.SEVERE`. Import it.
- `src/main/java/live/minehub/polarpaper/PolarListener.java:109` | **MAJOR** | Handler on `PlayerInteractEvent` named `onBlockBreak` — collides conceptually with the real `onBlockBreak(BlockBreakEvent)` at line 92; name lies about behavior (it's a right-click second-corner set). | Fix: rename pair to `onWandFirstCorner` / `onWandSecondCorner`.
- `src/.../PolarListener.java:77-90` | **MINOR** | Duplicated wand-PDC-clear body in `onChangeWorld` and `onJoin`. | Fix: `clearWandSelection(Player)`.
- `src/.../PolarListener.java:92-107,109-126` | **MINOR** | Corner-set logic duplicated again between break-handler, interact-handler and `WandCommand.pos1/pos2` (three copies of "vector→int[]→PDC→message"). | Fix: one static `setCorner(Player, NamespacedKey key, Vector pos, String cornerName)`.
- `src/.../PolarListener.java:33` | **MINOR** | Inconsistent gamerule lookup: `blockFade` uses `gamerules().get(...)` (no default) while siblings use `getOrDefault(name, true)`. Same outcome today by accident (null fails the pattern check). | Fix: uniform `getOrDefault("blockFade", true)`.
- `src/main/java/live/minehub/polarpaper/nms/VersionUtil.java:28-29,56-57` | **MINOR** | Fully-qualified impl class names in switch arms instead of imports (works, but is the only file in the module doing this). | Fix: import the impls; the classloading-on-execution semantics are identical.
- `src/.../nms/VersionUtil.java:27-31` | **MINOR** | Defensive `future != null` exists only because `paper_1_21_11.NoSaveLevelCreatorImpl` returns bare `null` while `paper_latest` returns `completedFuture(null)` — divergent contracts for one interface method. | Fix: make 1.21.11 return `CompletableFuture.completedFuture(null)` (matching its own `@Nullable World` signature), then delete the null branch.

### util & schematic

- `src/main/java/live/minehub/polarpaper/util/WorldKey.java:21-24` | **MAJOR** | `replaceAll(".polar$", "")`: unescaped dot matches any char (`xpolar`, `--polar` suffixes stripped too). Same bug at line 34 and `BrowseCommand.java:101`. | Fix: `replaceAll("\\.polar$", "")` once in a shared `stripPolarExtension(String)`; note `PolarCmd.listSavedWorldNames` (line 125) already escapes it correctly — unify on that.
- `src/.../util/WorldKey.java:24,36` | **MINOR** | `toLowerCase()` without Locale (×2). | Fix: `Locale.ROOT`.
- `src/.../util/WorldKey.java:74-96` | **MINOR** | `validatePath(CommandSender, ...)` mixes UI messaging with path resolution (naming says validate, does validate+report). Acceptable, but consider returning a result/sealed outcome so non-command callers aren't forced through `CommandSender`. NIT-level.
- `src/.../util/NoopWorldAccess.java:6` | **MINOR** | Mutable-bean class for what is an immutable value. | Fix: `record NoopWorldAccess(Plugin plugin) implements PolarWorldAccess {}` (Java 25 idiom, natural here).
- `src/.../util/EntitiesWorldAccess.java:46` | **MINOR** | `ENTITIES_VERSION` declared, never read (only `PERSISTENT_DATA_CONTAINER_VERSION` is compared). Dead constant. | Fix: delete or actually gate entity decoding on it (defensive: old files without entities block would fail loudly today).
- `src/.../util/EntitiesWorldAccess.java:62,67,73` | **MINOR** | Stray `final` parameters and `final var bb`. | Fix: drop modifiers, explicit type `ByteBuf`.
- `src/.../util/EntitiesWorldAccess.java` | **NIT** | Constructor missing space/indent glitch line 52 (`this.plugin`); formatting pass needed.
- `src/main/java/live/minehub/polarpaper/util/BlockUtil.java:31-58,60-87,161-184` | **MAJOR** | `setBlockFast` / `getBlockFast` / `setBlockEntity` repeat a ~20-line "resolve holder → currentChunk → section index bounds → floorMod local coords" preamble three times, hand-rolling `(int)Math.floor(x / 16.0)` + manual negative-fixups that duplicate existing core utils (`CoordConversion.globalToChunk`, `globalToSectionRelative`) and JDK `Math.floorDiv/floorMod`. | Fix: extract `private static LevelChunkSection sectionAt(World,x,y,z)` + `localCoords`; delegate to `CoordConversion`.
- `src/.../util/BlockUtil.java:138-153` | **NIT** | Anonymous `IntConsumer` with mutable counter field `i`. | Fix: plain indexed loop over `storage.getAll(...)` alternative or `for (int i = 0; i < storage.getSize(); i++) storage.get(i)`.
- `src/.../util/BlockUtil.java:29` | **NIT** | Not `final`, no private ctor.
- `src/main/java/live/minehub/polarpaper/schematic/Schematic.java:133` | **MAJOR** | `materialPalette[0]` — a malformed/hostile file may store a 0-length block palette (`getStringList` permits length 0 at PolarReader), producing AIOOBE during paste. | Fix: guard `uniformSection && materialPalette.length > 0 && materialPalette[0].isAir()`, treat empty palette as air section.
- `src/.../schematic/Schematic.java:95` | **MINOR** | `final var bb`. | Fix: `ByteBuf bb`.
- `src/.../schematic/Setter.java:24-115` | **MAJOR** | Entire file avoids imports for domain types (`live.minehub.polarpaper.core.world.PolarChunk.BlockEntity`, `org.bukkit.World`, `live.minehub.polarpaper.core.world.BlockSelector`…) — 20+ inline FQNs, unlike every sibling file. | Fix: add imports.
- `src/.../schematic/Setter.java:35` | **MINOR** | Nested implementation class named `World` shadows `org.bukkit.World` mentally and forces the FQN mess above. | Fix: rename `WorldSetter` (precise, no clash).
- `src/.../schematic/Rotation.java:32` | **NIT** | `values()[...]` allocates per call while `ROTATIONS` cache exists one field up. | Fix: use `ROTATIONS[...]`.

### commands

- `src/.../commands/CommandManager.java:55-58` + the four classes | **BLOCKER** | `GCCommand`, `SaveZSTDCommand`, `SetRandomCommand`, `FixSectionPaletteCommand` are referenced only from commented-out registration lines — dead classes (~280 LOC total). `SaveZSTD`/`SetRandom`/`FixSectionPalette` contain hardcoded dev-hack constants (palette offsets 1381/2322/10642, ZSTD level sweep). | Fix: delete all four classes and the comment block (git history keeps them).
- `src/.../commands/PolarCmd.java:75-77` | **MINOR** | `getAliases()` has no callers anywhere (aliases consumed internally via `getLiterals()`/field). | Fix: delete or make private-use.
- `src/.../commands/PolarCmd.java:103,147-148,154` | **NIT** | `toLowerCase()` ×4 without Locale. | Fix: `Locale.ROOT`.
- `src/.../commands/UnloadCommand.java:34,41,66` | **MINOR** | `runOverrided` / `saveOverrided` — "overrided" is not a word and the name doesn't say what differs. | Fix: `runWithSaveFlag` / `saveOverridden` (or better: `unload(ctx, worldId, Optional<Boolean> saveOverride)`).
- `src/.../commands/RenameCommand.java:86,90` | **MINOR** | Copy-paste from DeleteCommand: IOException during a **rename** logs/messages "Failed to delete world". | Fix: correct wording ("Failed to rename").
- `src/.../commands/WandCommand.java:87-91` | **MINOR** | Sends "You have been given a polar wand" even when `addItem` reports failure (inventory full) — contradictory feedback. | Fix: early return after the failure message.
- `src/.../commands/WandCommand.java:33-53,55-75` | **NIT** | `pos1`/`pos2` near-clones (see PolarListener fix — share one corner-setter).
- `src/.../commands/ConvertCommand.java:43-47` | **MINOR** | try/catch `IllegalArgumentException` from `ctx.getArgument` to sniff optional argument presence — control flow by exception; the tree already routes the 2-arg case to `executeWithLight`. | Fix: register the base node without the arg using a dedicated executor, or `ctx.getArgumentOrNull`-style helper.
- `src/.../commands/ListCommand.java:40` | **NIT** | `if (world == null) continue;` — `Bukkit.getWorlds()` yields no nulls. Dead defensive branch.
- `src/.../commands/ListCommand.java:101-110` | **MINOR** | Generic paging util parametrized but param named `bukkitWorlds`, and it re-implements `list.subList(start,end)`. | Fix: move to a `Pages.subList(list,page,size)` util (used by BrowseCommand too), implement via `subList`, neutral param name `items`.
- `src/.../commands/BrowseCommand.java:48-49,183-184,195-196,206-207` (+ `WorldKey`, `GotoCommand:42-43`, `Polar.getDefaultFolderSource`) | **MAJOR** | `getDataPath().resolve("worlds")` recomputed at 9+ sites. | Fix: single `Polar.worldsFolder()` accessor; everything calls it.
- `src/.../commands/BrowseCommand.java:101` | **MINOR** | Regex-dot extension strip + wrong inline justification ("$ means last occurrence"). Covered by the WorldKey fix.
- `src/.../commands/BrowseCommand.java:70-72` | **MINOR** | `catch (IOException) { throw new RuntimeException(e); }` inside command execution — an unreadable folder crashes the dispatcher instead of reporting like every sibling command. | Fix: message + `LOGGER.error`, return SINGLE_SUCCESS (defensive coding norm used elsewhere).
- `src/.../commands/RelightCommand.java:55,68-69` | **NIT** | `int[] pending` / `long[] lastSentMsg` / `int[] relitChunks` single-element arrays as mutable closure captures. | Fix: `AtomicInteger`/`AtomicLong` (matches Polar's own style at Polar.java:542).
- `src/.../commands/UsageCommand.java:67-68` | **NIT** | Same array-capture trick (`ticksLeft`, `taskHolder`). | Fix: AtomicInteger + assignment is fine since cancel happens inside; keep simple but consistent.
- `src/.../commands/VersionCommand.java:82` | **MINOR** | `toRelative(String)` is public with no external callers. | Fix: private.
- `src/.../commands/VersionCommand.java:36-37` | **NIT** | Mutable statics `LAST_UPDATED`/`CACHED_RELEASE` unsynchronized (benign race, but note it once comments forcing documentation are gone — consider `volatile`).
- `src/.../commands/VersionCommand.java:145-154` | **NIT** | Gson DTO classes use raw snake_case fields (`updated_at`) leaking transport format into names; map via `@SerializedName` and use camelCase.
- All 22 command classes | **DRY MINOR** | The `Component.text().append(Component.text("'…", RED)).append(...)` five-line message scaffold repeats ~60 times. | Fix: two helpers, e.g. `Msg.error(sender, "'%s' does not exist", name)` and `Msg.ok(...)`, or a `Component join(NamedTextColor, Object… parts)`; would remove several hundred lines.
- All commands | **NIT** | `executeDefault` usage messages duplicate the brigadier literal tree; acceptable UX choice, listed once for awareness.

---

## D. Findings — paper_* modules

- `paper_latest/.../NoSaveLevelCreatorImpl.java:226` and `paper_1_21_11/.../NoSaveLevelCreatorImpl.java:267` | **MAJOR** | `private class NoSaveLevel extends ServerLevel` — **non-static** inner classes though neither touches the outer instance. Holds a hidden reference, cannot be instantiated statically, warns under `-Xlint`. | Fix: `private static final class NoSaveLevel` (or top-level package-private file `NoSaveLevel.java`, which also fixes the vague reuse of the NMS-ish name inside a 240-line method file).
- `paper_1_21_11/.../NoSaveLevelCreatorImpl.java:73-75` | **MAJOR** | Returns bare `null` future (contract says `CompletableFuture<@Nullable World>`), diverging from `paper_latest` which returns `completedFuture(null)`; `VersionUtil` papers over it with a null check. | Fix: align on `completedFuture(null)`; simplify VersionUtil.
- `paper_1_21_11/.../NoSaveLevelCreatorImpl.java` vs `paper_latest/.../NoSaveLevelCreatorImpl.java` | **MAJOR (DRY)** | Three blocks are copy-paste between versions and use only stable APIs: (1) gamerule application loop (latest:143-158 / 1.21.11:152-167) incl. `Config.getDefaultGamerules()` custom-rule skip; (2) difficulty string→enum fallback (latest:121-126 / 1.21.11:140-148); (3) `defaultGenSettings` JsonObject "layers/biome" workaround (latest:110-113 / 1.21.11:170-173). | Fix: extract `NoSaveLevels.applyGamerules(...)`, `NoSaveLevels.parseDifficulty(...)`, `defaultGeneratorSettingsJson()` into a shared helper (core or a `paper_common` source set); keep only genuinely NMS-divergent code per module.
- `paper_26_1/.../EntitySerializerImpl.java` vs `paper_latest/.../EntitySerializerImpl.java` | **MAJOR (DRY)** | Files are ~95% identical (97/96 lines); sole semantic diff is `EntityType.create(input, level, new EntitySpawnRequest(EntitySpawnReason.LOAD,false))` vs `EntityType.create(input, level, EntitySpawnReason.LOAD)`. | Fix: move `entityToBytes`/`serializeEntity`/`compoundToEntity` skeleton into core (`EntitySerializers`) taking a `ToLongBiFunction`-style creation hook supplied per version; each paper module shrinks to a few lines.
- `paper_latest/.../NoSaveLevelCreatorImpl.java:218-223` (and 1.21.11:259-264) | **NIT** | `if (async) { return X; } else { return Y; }` — house style prefers early return without `else`. | Fix: drop the else.
- `paper_1_21_11/.../NoSaveLevelCreatorImpl.java:200` | **NIT** | `long i = BiomeManager.obfuscateSeed(...)` — vague one-letter name. | Fix: `biomeSeed`.
- `paper_1_21_11/.../NoSaveLevelCreatorImpl.java:268` | **MINOR** | Fully-qualified `@org.jspecify.annotations.Nullable` on a ctor param while the rest of the project uses JetBrains annotations. | Fix: standardize.
- Both NoSaveLevelCreatorImpls | **NIT** | Wildcard imports (`org.bukkit.*`, `net.minecraft.world.level.levelgen.*`) mixed with single-type imports elsewhere. Pick one convention (project leans explicit).
- `paper_26_1/.../EntitySerializerImpl.java` | **NIT** | Module directory `paper_26_1_2` vs package `paper_26_1` mismatch — confusing when grepping. | Fix (low prio): align package to module name or document why.

---

## E. Comment-line census (feeds cleanup task)

Counted per physical line participating in `//` line comments or `/* */`/javadoc blocks (block open/close lines included):

| Module | Java files | Total LOC | Comment lines | % of LOC |
|---|---|---|---|---|
| `core/` | 38 | 6,120 | **796** | 13.0% |
| `src/` | 40 | 5,309 | **349** | 6.6% |
| `paper_latest/` | 2 | 337 | **9** | 2.7% |
| `paper_1_21_11/` | 1 | 281 | **15** | 5.3% |
| `paper_26_1_2/` | 1 | 96 | **1** | 1.0% |
| **TOTAL** | **82** | **12,143** | **1,170** | **9.6%** |

Heaviest comment carriers in `core/` (top offenders for the cleanup task): `PolarChunk` (~120 comment lines), `PolarStreamLoader` (~85), `Config` (~80), `PolarWorld` (~75), `PolarReader` (~55), `ChunkLight` (~45), `WorldUsage` (~45), `LightUtil` (~40), `PolarChunkArchive` (~40), `BlockSelector` (~30), `PolarSection` (~30).

⚠️ Cleanup guidance: a large share of these comments document **non-obvious concurrency/format invariants** (archive claim/release protocol, starlight nibble semantics, packed-bits round-trip ambiguity, shutdown-write ordering). Deleting them outright loses hard-won knowledge. Recommendation for the cleanup task: where the comment states an invariant, encode it in a precise identifier or an assertion/exception message (which survive house law); only then delete.

---

## F. Top 10 worst findings (priority order)

1. **BLOCKER** `PolarConstants.java` — dead duplicate of format constants; only 3 of 13 members used. Single source of truth broken.
2. **BLOCKER** 1,170 comment/javadoc lines repo-wide vs house law of zero (core/ alone: 796).
3. **BLOCKER** 4 dead dev-hack command classes (`GCCommand`, `SaveZSTDCommand`, `SetRandomCommand`, `FixSectionPaletteCommand`, ~280 LOC) reachable only through commented-out registrations.
4. **BLOCKER** `PolarWorld.DEFAULT_COMPRESSION`/`DEFAULT_COMPRESSION_LEVEL` + `LightUtil.EMPTY_CONTENT`/`FULLY_LIT_CONTENT` — public mutable global state that corrupts every world/light decode if written.
5. **MAJOR** `paper_1_21_11.NoSaveLevelCreatorImpl.createLevel` returns bare `null` future, breaking the interface contract that `paper_latest` honors; masked by a compensating null-check in `VersionUtil`.
6. **MAJOR** `EntitySerializerImpl` duplicated ~95% across `paper_latest`/`paper_26_1` (plus three copy-pasted blocks between the two `NoSaveLevelCreatorImpl`s) — version modules should hold only NMS-divergent hooks.
7. **MAJOR** Header-parsing duplicated (`PolarReader.read` vs `PolarContentReader.open`), chunk-slice walking duplicated (`PolarChunkStore.read` vs `PolarChunkArchive.readSourceIndex`), `validateChunkCount` duplicated verbatim — three DRY breaks in the hot save/load path.
8. **MAJOR** `BlockUtil` triplicates chunk/section/local-coordinate resolution with hand-rolled float math while `CoordConversion.globalToChunk/globalToSectionRelative` and `Math.floorDiv/floorMod` exist.
9. **MAJOR** `.polar$` regex-dot bug (strips any-char+"polar" suffixes) in `WorldKey` ×2 and `BrowseCommand`, compounded by `toLowerCase()` without `Locale.ROOT` at 10+ sites including world-name canonicalization (`Polar.createWorld`).
10. **MAJOR** `PolarReader`'s nine `protected static` members have no subclasses anywhere; `PolarStreamLoader.assertThat` is dead; `PolarStreamLoader`/`PolarChunk` duplicate the section-mask/light-preserve build — API surface and dead weight in core.

*Honorable mentions:* non-static inner `NoSaveLevel` classes (both creator impls), atomic-file-write duplication (`Polar.writeAtomically` vs `FilePolarSource.saveBytes`), misleading `onBlockBreak` listener name, `RenameCommand` "Failed to delete" copy-paste, `Schematic.pasteSection` empty-palette AIOOBE on hostile input.
