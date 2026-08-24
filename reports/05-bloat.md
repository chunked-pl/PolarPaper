# Report 05 — Overengineering, Bloat & Solution Quality

Scanner #5 of 5 · scope: every class pays rent; no single-impl abstractions, no speculative
generality, no dead files, no unwired features, thin version modules.
All 82 Java sources read; every claim below verified by grep over tracked sources
(`reports/`, `build/`, `.git/` excluded from counts).

**Context that shapes this report:** the shaded plugin jar *is* the published artifact (root
`build.gradle.kts` publishes `components["shadow"]`); `core` is never published separately.
The consumer-facing API is whatever is `public` and reachable, but nothing outside this repo
can depend on a class that nothing inside it references either. Where a symbol is plausibly
"API for downstream plugins" I say so instead of pretending it's free to delete.

---

## Verdict up front

The hot paths (reader/writer/archive/stream loader) are tight and well-commented — no bloat
there. The bloat is concentrated in four places:

1. **Four abandoned debug/dev commands kept alive only by a comment** (~282 LOC) and the
   `BytesPolarSource` they were the last user of.
2. **A layer of speculative API** (`PolarChunkStore`, `updateChunks`, `NOOP`, `withUserData`,
   `circle()`, `both()`…) added "for consumers" that zero callers, in-repo or documented,
   have ever touched.
3. **One-and-a-half generator classes**: `PolarGenerator` is abstract with exactly one
   subclass whose `getPolarWorld()` unconditionally returns `null`, leaving half the
   `extraChunks` plumbing permanently dead.
4. **Duplicate constant homes** (`PolarConstants` vs `PolarWorld`/`PolarChunk`/`PolarSection`)
   and a duplicate NMS block between the two version modules.

The `paper_*` modules themselves are **good**: 1–2 classes each, genuinely thin NMS adapters,
no copied skeletons. Do not "deduplicate" them away.

---

## 1. DELETE outright

### Whole files

| Item | Evidence | Action | Risk |
|---|---|---|---|
| `src/.../commands/GCCommand.java` | Registered **only** in commented-out line `CommandManager.java:55`. External refs: 0. | **DELETE** | none |
| `src/.../commands/SaveZSTDCommand.java` | Commented-out `CommandManager.java:56`. Refs: 0 live (it is also the *only production user* of `BytesPolarSource`). ~100 LOC of benchmark scaffolding. | **DELETE** | none |
| `src/.../commands/SetRandomCommand.java` | Commented-out `CommandManager.java:57`. Refs: 0. Debug tool (`setrandom`) never shipped. | **DELETE** | none |
| `src/.../commands/FixSectionPaletteCommand.java` | Commented-out `CommandManager.java:58`. Refs: 0. One-off data migration hardcoded to specific palette ids of an old version. | **DELETE** | none |
| `src/.../util/NoopWorldAccess.java` | Refs anywhere (incl. javadoc): **0**. 16 LOC class implementing an interface with all-default methods. | **DELETE** | none |
| `core/.../world/PolarConstants.java` | Only 3 of its members are referenced (`MAGIC_NUMBER`, `VERSION_DATA_CONVERTER`, `VERSION_WORLD_USERDATA` — all by `PolarContentReader`), and those three already exist canonically in `PolarWorld`. Rest is duplication + 28 commented-out lines (`:8–10`, `:19–34`). Also exports mutable `public static DEFAULT_COMPRESSION` nobody reads. | **DELETE** after pointing `PolarContentReader` at `PolarWorld.*` | low |
| `core/.../world/PolarChunkStore.java` **+** its test `PolarChunkStoreTest.java` | Production refs: **0** (grep: only its own test). A speculative "random-access compressed store" API nobody wired up; duplicates the slice-walking logic `PolarChunkArchive.readSourceIndex` already implements better. ~116 + 132 LOC. | **DELETE** both | low–medium (it's `public`; but it ships in no API doc, has no consumer, and overlaps `PolarChunkArchive`) |
| repo-root `chunked.polar` / `chunked.slime` | 12 MB of local test artifacts, gitignored, unreferenced by any script/source. | **DELETE** locally | none |

### Dead members inside live files (each: grep count = 0 external references)

| Item | Location | Action | Risk |
|---|---|---|---|
| `PolarDataConverter.NOOP` | `PolarDataConverter.java:18` | **DELETE** | none |
| `PolarWriter.writeWithArchive(...)` | `PolarWriter.java:41–44` | **DELETE** | none |
| `PolarChunkArchive.source()` getter | `PolarChunkArchive.java:36–38` | **DELETE** | none |
| `PolarWorld.hasChunkAt`, `numChunks`, `updateChunkAt`, and all three `updateChunks(...)` overloads | `PolarWorld.java:157–223` | **DELETE** (~45 LOC; the instance-state `chunks` map then becomes constructor-only, enabling record-ification later) | low |
| `PolarChunk.HEIGHTMAP_NONE…WORLD_SURFACE_WG` + `static int[] HEIGHTMAPS` | `PolarChunk.java:52–67` — the array is built once and never read | **DELETE** | none |
| `PolarChunk.withUserData(...)` | `PolarChunk.java:97–99` | **DELETE** | none |
| `PolarEntity.getLocation(Chunk)` | `PolarEntity.java:104–106` | **DELETE** | none |
| `CoordConversion.globalToChunk`, `sectionIndexToY`, `sectionIndex(int y, int minSection)` | `CoordConversion.java:19–22, 67–69, 73–75` | **DELETE** | none |
| `ByteArrayUtil.writeStrings(Collection<String>, …)` | `ByteArrayUtil.java:160–165` (exact twin of `writeStringArray`) | **DELETE** | none |
| `ChunkResidencyPolicy.both(...)` | `ChunkResidencyPolicy.java:26–30` — speculative combinator, never called | **DELETE** | none |
| `BlockSelector.circle(int)`, `circle(int,int,int)`, `square(int)` | `BlockSelector.java:34–37, 79–82` — only `square(int,int,int)` (ConvertCommand) and `horizontalCircle` are used | **DELETE** | none |
| `BlockUtil.getBlockFast(...)`, `findBlocks(World, BlockState)`, `findBlocks(World, int, int, BlockState)` | `BlockUtil.java:60–87, 93–159` — 130 LOC of world-search utilities with zero callers | **DELETE** | low |
| `Rotation.rotate(Rotation)` | `Rotation.java:31–33` | **DELETE** | none |
| `Setter.World.getWorld()` / `getBlockSelector()` | `Setter.java:49–55` | **DELETE** | none |
| `PolarCmd.getAliases()` | `PolarCmd.java:75–77` | **DELETE** | none |
| `PolarStreamLoader.assertThat(...)` | `PolarStreamLoader.java:522–525` — copy-paste remnant, never called (scanner 01 flagged too) | **DELETE** | none |
| `Config.async` record field + `Builder.async` + `"async"` Property + `config.yml` key | accessor `async()` has **zero call sites**; the option is stored, round-tripped through YAML and documented as "Experimental" — a feature nobody finished | **DELETE** field + key | low (users may have the key in config.yml; harmless orphan) |

Net: ≈ **700+ LOC** deletable with zero behavioral change.

---

## 2. Abstractions to collapse

| Item | Evidence | Action | Risk |
|---|---|---|---|
| `PolarGenerator` (abstract) + single subclass `PolarStreamingGenerator` | `getPolarWorld()` is abstract; the only implementation returns `null` **always** (`PolarStreamingGenerator.java:26–28`). Consequence: `generator.getPolarWorld() == null ? List.of() : …` in `Polar.saveWorld:767` and `saveWorldSynchronously:785` evaluates to `List.of()` forever; the whole "in-memory polar world attached to a generator" concept is vestigial. | **COLLAPSE** into one concrete `PolarGenerator`; inline `getUserData()`; drop the `getPolarWorld()` abstract hole (or keep it returning `null` privately). Keep `Polar`'s public signatures as thin delegates if worried about downstream. | medium — touches the main public facade |
| `Polar.createWorld` — **8 overloads** (`Polar.java:86–215`) | In-repo callers use exactly three: `(source,name)`, `(source,name,config)`, `(generator,name)`. Four overloads taking `PolarWorld` or an explicit `PolarWorldAccess` have **zero in-repo callers**; they exist purely as downstream convenience. Each new parameter combination multiplies the surface (a residency param was already bolted on as overload #8). | **COLLAPSE** to 2 entry points: `createWorld(PolarSource, String)` and the full canonical form; document the rest as deprecated one-liners or delete. Same story for `Polar.saveWorld` (the `(World, PolarSource)` overload's only caller is the dead SaveZSTDCommand). | medium (public API shrink) |
| Duplicated NMS adapter body between `paper_latest` and `paper_1_21_11` `NoSaveLevelCreatorImpl` | ~35 identical lines each: the gamerule-application loop (`paper_latest:143–158` vs `paper_1_21_11:152–167`) and the `defaultGenSettings` JsonObject fix (`:110–113` vs `:170–172`), plus the same init/spawn epilogue. The modules should stay separate, but these pure-Java blocks belong in `core` (e.g. `VersionUtil.applyGamerules(...)`). | **COLLAPSE** shared blocks into core helpers | low |
| `Property` subclasses' constructor pyramid | `EnumProperty`/`LocationProperty`/`GamerulesProperty` each redeclare the same 3 constructors just to call `super` (`Config.java:463–474, 516–525, 585–595`). Only 4 construction sites exist, each using one constructor. | **SIMPLIFY**: keep a single super constructor, drop the 6 redundant ones | none |
| `EntitiesWorldAccess` vs `NoopWorldAccess` vs `PolarWorldAccess` | After deleting `NoopWorldAccess`, `PolarWorldAccess` has exactly one implementation (`EntitiesWorldAccess`) plus the interface-default no-op behavior. The interface itself is justified (it's the documented extension point), so this is a *note*, not an action: don't collapse it further. | — | — |

---

## 3. Commented-out registrations / abandoned-feature markers

| Location | What it signals | Action |
|---|---|---|
| `CommandManager.java:55–58` | `GCCommand`, `SaveZSTDCommand`, `SetRandomCommand`, `FixSectionPaletteCommand` registrations — the four classes above are unreachable because of this exact block | Resolve: either register or (recommended) delete classes + block |
| `PolarWorldAccess.java:29–50` | Commented-out Minestom-era `loadWorldData`/`saveWorldData` referencing `Instance`/`NetworkBuffer` types that don't exist here, plus `// TODO: these` | **DELETE** — upstream fork residue, misleading in a Paper-only fork |
| `PolarStreamLoader.java:495–496, 502–503` | Commented-out warnings/throws referencing `PolarPaper.logger()` — a method that no longer exists | **DELETE** |
| `PolarReader.java:70, 77` | Commented-out debug logs (`PolarPaper.logger().info(...)`) — same dead reference | **DELETE** |
| `PolarWorld.java:37–39` | Commented-out historical version constants (duplicated again in `PolarConstants.java:8–10`) | **DELETE** |
| `PolarConstants.java:8–10, 19–34` | Commented-out heightmap constants — the live twins in `PolarChunk` are themselves unread (see DELETE list) | Goes away with the file |
| `PolarEntity.java:31` | `// ExceptionUtil.log(e);` — utility class doesn't exist in this fork | **DELETE** comment |
| `paper_1_21_11/.../NoSaveLevelCreatorImpl.java:202, 221–223` | Commented-out spawner list and a dead world-key guard block | **DELETE** |

---

## 4. Unused dependencies / resources / config keys

| Item | Evidence | Action | Risk |
|---|---|---|---|
| Root `compileOnly(libs.zstd)` (`build.gradle.kts:32`) | No source in root `src/main/java` imports `com.github.luben.zstd` (only a string literal in the deleted SaveZSTDCommand). `core` declares its own `compileOnly(libs.zstd)`. | **REMOVE** from root | low — verify compile after removal |
| `hangarPublish { platforms.paper { jar = tasks.jar… } }` vs `jar { enabled = false }` | `build.gradle.kts:96` binds the Hangar release to a task that is disabled at `:40`; the real artifact is `shadowJar`. The publication can only ever resolve a stale/nonexistent jar. | **FIX or DELETE**: point at `tasks.shadowJar` (flatMap of its archiveFile) or drop the platform block until releases are wanted | low (config currently broken anyway) |
| `default.async` key in `src/main/resources/config.yml:11` + `Config` property | Read into the record, never consumed by any code path | **DELETE** with the `async` field (see §1) | none |
| Everything else checked clean | `junit-*` used by core tests; `resource-paper` generates plugin.yml; `config.yml` consumed by `saveDefaultConfig()`; gson/jspecify/joml/netty all imported | — | — |

---

## 5. SIMPLIFY — works today, but a shorter Java idiom is clearly better

Flagged only where the rewrite is shorter **and** clearer:

| Item | Current | Simpler | Action | Risk |
|---|---|---|---|---|
| `ListCommand.getPagedList(List,page,n)` (`ListCommand.java:101–110`) | Manual index loop building a copy | `list.subList(Math.min(start,size), Math.min(end,size))` wrapped unmodifiable (or `stream().skip().limit()`) | **SIMPLIFY**, reuse in BrowseCommand | none |
| `.replaceAll(".polar$", "")` in 4 places (`WorldKey.java:22,34`, `BrowseCommand.java:101`, `LoadCommand` suggestion path) | Compiles a regex per call to strip a literal suffix | `String.valueOf` + `endsWith`/`substring`, or a precompiled `Pattern` constant | **SIMPLIFY** | none |
| `GithubRelease` / `GithubAsset` (`VersionCommand.java:145–154`) | Private mutable classes for Gson | Gson ≥2.10 maps records directly; two small records read better | **SIMPLIFY** | low (verify Gson version bundled with Paper handles records — it does) |
| `UsageCommand.field(...)` returns a `TextComponent.Builder` per fragment | Builds a builder per field then appends builders | Return `Component` (`Component.text(name, DARK_GRAY).append(value)`) — same output, one less type in the signature | **SIMPLIFY** | none |
| `Schematic.pasteSection` unpacks the full 4096-int array even for uniform sections before checking `uniformSection` | Order-of-work nit | Check `rawBlockPalette.length <= 1` first, skip the unpack entirely | **SIMPLIFY** (minor CPU win too) | none |
| `PolarListener.onBlockBreak(PlayerInteractEvent)` named like the break handler above it | Two handlers, same method name, different events | Rename `onWandInteract` — costs nothing, prevents future "why is break firing twice" confusion | **SIMPLIFY** | none |

Not flagged (checked and judged fine): `TaskFutures` (justified Bukkit-scheduler wrapper),
`Config`'s table-driven Property system (earns its keep: 15 properties × read/write/default/
comment), `PolarChunkArchive.Snapshot` (real concurrency design), the reflection wall in
`insertChunk` (necessary evil, documented), `VersionUtil`'s string-version switches (thin and
obvious), `paper_*` module structure itself.

---

## Priority order

1. **Zero-risk purge**: §1 whole-file deletes (4 commands, `NoopWorldAccess`,
   `PolarChunkStore`+test, `PolarConstants`), all dead members, all §3 comment blocks.
   ~700 LOC gone, no behavior change.
2. **Build hygiene**: root zstd dep, Hangar jar binding, `async` config key.
3. **Collapse**: `PolarGenerator` single-impl merge; `createWorld` overload reduction;
   hoist duplicated gamerule/JSON blocks out of the version modules.
4. **Polish**: §5 simplifications.

— scanner 05/05, bloat & solution quality
