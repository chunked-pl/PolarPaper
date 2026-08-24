# Security Audit — PolarPaper (scanner #3 of 5)

Scope: all `.java` in `core/`, `src/`, `paper_1_21_11/`, `paper_latest/`, `paper_26_1_2/`.
Threat model adapted from OWASP for a Bukkit/Paper plugin whose attack surface is:
untrusted **world files** placed in `plugins/PolarPaper/worlds/`, permission-gated
**commands**, and **file IO** around the worlds folder + config.

Legend: CRITICAL / HIGH / MEDIUM / LOW. Line numbers verified against working tree.

---

## HIGH

### H-1 | Unlimited-heap NBT deserialization of untrusted world data
| File | Line |
|---|---|
| `core/src/main/java/live/minehub/polarpaper/core/world/PolarReader.java` | 351 |
| `core/src/main/java/live/minehub/polarpaper/core/world/PolarReader.java` | 219 (`skipBlockEntity`) |
| `core/src/main/java/live/minehub/polarpaper/core/world/PolarEntity.java` | 29 |

```java
nbt = (CompoundTag) NbtIo.readAnyTag(bbis, NbtAccounter.unlimitedHeap());   // PolarReader:351
NbtIo.readAnyTag(input, NbtAccounter.unlimitedHeap());                      // PolarReader:219
compound = NbtIo.read(dataInput, NbtAccounter.unlimitedHeap());             // PolarEntity:29
```

**Vulnerability.** Block-entity NBT and stored-entity NBT are parsed from the `.polar`
file with `NbtAccounter.unlimitedHeap()` — no byte budget, no depth limit. Every other
length in this format is carefully bounded (`ByteArrayUtil.requireReadableLength`,
palette caps, chunk-count sanity checks), so NBT is the one unguarded door left.

**Exploit scenario.** Anyone who can place a file in the worlds folder (uploaded world,
shared host, compromised panel account) crafts a `.polar` whose zstd frame declares ≤
1 GiB uncompressed (cap at `PolarReader.java:35`) and contains one block entity whose
NBT is a classic NBT bomb (nested `byte[]` arrays / deep nesting totalling gigabytes of
heap). On `/polar load`, `/polar paste`, or even the *skip* path used by streaming saves
(`skipBlockEntity`), the server allocates until `OutOfMemoryError` kills it mid-tick.
The same bytes reach live objects later via `PolarStreamLoader.addBlockEntity`
(`loadWithComponents`, PolarStreamLoader.java:511) and `EntityType.create`
(EntitySerializerImpl compoundToEntity).

**Fix.**
- Parse with a bounded accounter, e.g. `NbtAccounter.create(2L * 1024 * 1024)` per
  block entity / entity (vanilla uses ~2 MiB budgets for chunk data), and additionally
  clamp to `bb.readableBytes()` so a small buffer cannot promise a huge payload.
- Consider a global per-world NBT-bytes-read budget during load.

---

### H-2 | Unbounded region/radius iteration from command args → permanent main-thread hang
| File | Line |
|---|---|
| `src/main/java/live/minehub/polarpaper/commands/ConvertCommand.java` | 63, 166 |
| `src/main/java/live/minehub/polarpaper/commands/CreateFromRegionCommand.java` | 155–183 |
| `core/src/main/java/live/minehub/polarpaper/core/world/BlockSelector.java` | 109–123 (`square.forEachChunk`), 284–296 (`RegionBlockSelector.forEachChunk`) |
| `core/src/main/java/live/minehub/polarpaper/core/world/PolarWorld.java` | 277–279 (`forEachChunk` consumed when `loadChunks=true`) |

**Vulnerability.** Both selectors' `forEachChunk` iterate `(max-min)/16`² positions with
bounds taken straight from sender-supplied integers:

- `ConvertCommand`: `"chunk radius"` is `IntegerArgumentType.integer(1)` — minimum 1,
  **no maximum**. `BlockSelector.square` computes `minX = centerX - radius` in `int`
  arithmetic (overflow possible near `Integer.MAX_VALUE`, wrapping loop bounds).
- `CreateFromRegionCommand` console variant takes `x1..z2` as unrestricted ints;
  `RegionBlockSelector.forEachChunk` then walks from `min.x/16 - 1` to `max.x/16 + 1`.

`PolarWorld.convert(...)` runs this enumeration synchronously on the caller's thread
(the main thread when invoked from a command) and there is no watchdog-friendly yield
inside these plain loops. The 10-minute timeout (`awaitChunks`) never gets a chance —
it guards the *chunk futures*, not the enumeration itself.

**Exploit scenario.** Any player granted `polarpaper.convert` runs
`/polar convert foo 2000000000`. The server's main thread enters a loop over ~10¹⁹
candidate chunks (or, with overflow, over a wrapped range) and never returns: soft-lock,
TPS 0, restart required. Same with `/polar createfromregion name world -2000000000 -64 -2000000000 2000000000 319 2000000000`.

**Fix.** Clamp inputs where they are parsed: `IntegerArgumentType.integer(1, 4096)`
for chunk radius (and validate again inside the executor), clamp region corners to
±30 M blocks, and make the selector math use `long` with an explicit upper bound
(e.g. reject > 1 M selected chunks) before iterating.

---

## MEDIUM

### M-1 | Whole-world file read + parse + block paste executed on the main thread, uncapped
| File | Line |
|---|---|
| `src/main/java/live/minehub/polarpaper/commands/PasteCommand.java` | 106–136 |
| `src/main/java/live/minehub/polarpaper/schematic/Schematic.java` | 115–152 |

`Files.readAllBytes(path)` (PasteCommand:118) then `PolarReader.read(polarBytes)` run
directly in the command handler; `Schematic.pasteSection` then writes up to
4096 × sections × chunks blocks via `setBlockFast` in the same tick, followed by a
relight of 9 chunks per source chunk (`Setter.refreshChunks`). There is no file-size
cap and no per-tick work budget.

**Exploit.** A `polarpaper.paste` holder repeatedly pastes a multi-hundred-MB world:
each invocation stalls the main thread for seconds-to-minutes (read + decompress +
parse + millions of palette writes) and doubles file size transiently in heap → freeze /
OOM amplification. Combined with H-1 the parse alone can kill the JVM.

**Fix.** Read+parse off-thread (the codebase already has the async plumbing in
`TaskFutures`), enforce a max input size, and spread block writes across ticks.

### M-2 | Worlds-folder containment is not symlink-safe (TOCTOU / canonicalization gap)
| File | Line |
|---|---|
| `src/main/java/live/minehub/polarpaper/util/WorldKey.java` | 68–96 |
| `core/src/main/java/live/minehub/polarpaper/core/source/FilePolarSource.java` | 10–41 |
| `src/main/java/live/minehub/polarpaper/PolarPaper.java` | 50 (`Files.walk(..., FOLLOW_LINKS)`) |

`isWithinWorldsFolder` compares `path.normalize().startsWith(worldsFolder)` where the
*base* is never normalized/`toRealPath`'d, and every subsequent operation
(`Files.exists`, `readAllBytes`, `move(REPLACE_EXISTING)`, `deleteIfExists`, temp-write
in `saveBytes`) follows symlinks. Nothing rejects a symlinked `.polar`.

**Exploit.** Anything that can write *inside* `plugins/PolarPaper/worlds/` (another
misbehaving plugin, an extracted uploaded zip, a co-tenant on a shared panel) drops
`evil.polar -> /etc/cron.d/task` or `-> ../..//<other-plugin>/config.yml`.
`validatePath` passes; `/polar load` reads the target's bytes into a world (disclosure),
`/polar delete` removes the target, and autosave's atomic `REPLACE_EXISTING` move
overwrites whatever the link pointed at.

**Fix.** Resolve both sides with `toRealPath()` before containment checks; open world
files with `LinkOption.NOFOLLOW_LINKS` (or verify `!Files.isSymbolicLink(path)` after
resolution) in `FilePolarSource` and the startup walk.

### M-3 | Filesystem existence oracle outside the worlds folder via `Identifier` path
| File | Line |
|---|---|
| `src/main/java/live/minehub/polarpaper/commands/GotoCommand.java` | 42–46 |

`GotoCommand` builds `worldsFolder.resolve(worldId.getPath() + ".polar")` directly —
unlike `LoadCommand` it never calls `WorldKey.validatePath`. Brigadier's
`IdentifierArgument` accepts `.` in paths, so `minecraft:../../etc/passwd` resolves and
escapes the folder. The reply differs for "exists" vs "does not exist".

**Exploit.** Any player with `polarpaper.goto` maps files/dirs outside the worlds
folder (`/polar goto minecraft:../../../root/.ssh/id_rsa`) — low-value but free
reconnaissance that pairs with M-2.

**Fix.** Route the resolution through `WorldKey.validatePath(sender, …)` exactly like
`LoadCommand.run` does.

---

## LOW

### L-1 | Async read-modify-write of the world file races autosave (lost update)
`SetCenterCommand.java:52–79` rewrites the whole `.polar` asynchronously
(`PolarReader.read(source)` → mutate → `saveBytes`) with no coordination with the
autosave task (`saveInProgress` CAS exists in `Polar.startAutoSaveTask` but is not
consulted here). An autosave landing between read and write is silently reverted, or
the center change is lost. Integrity only — no privilege impact.
Fix: funnel through the generator's save pipeline or hold the same in-flight-save lock.

### L-2 | Unvalidated indices from file abort world load (crash-safe but fragile)
- `PolarStreamLoader.addBlockEntity:484–492` trusts `blockEntity.index()`; y can be ±2²³,
  producing out-of-range section lookups (`getBlockState` → exception) and block entities
  created far outside world height via `BlockUtil.setBlockEntity:176–183`.
- `PolarReader.readHeightmaps:258–264`: a single-long heightmap yields
  `bitsPerEntry = 64/256 = 0` and `PaletteUtil.unpack:87` divides `64 / bitsPerEntry`
  → `ArithmeticException`, aborting the whole world load.

Both fail closed (load aborted), so impact is availability of the load operation only.
Fix: validate index ∈ [0,4096) and y within `[minY, maxY]`; require `bitsPerEntry ≥ 1`.

### L-3 | Unbounded entity count in chunk userdata
`EntitiesWorldAccess.loadChunkData:77` and `Schematic.handleUserData:97` call
`EntityUtil.getEntities` (`EntityUtil.java:49–63`) which trusts `entityCount` with no
bound comparable to the skip path's `entityCount > readableBytes / 33` check
(`PolarReader.java:181–185`). Buffer exhaustion stops the loop, but a crafted userdata
blob maximizes event firings (`PolarEntitySpawnEvent`) and scheduled tasks per entity
during paste (`Setter.spawnEntity` schedules one task each).
Fix: apply the same `readableBytes/33` style bound and batch spawn scheduling.

### L-4 | Misc
- `WorldKey.getWorldName:22`, `PolarCmd.java:125`, `BrowseCommand.java:101` etc. use
  regex `.polar$` — unescaped dot matches any char (`worldXpolar` → `world`);
  filename-confusion only. Use `\\.polar$`.
- `WandCommand.java:22` hands players a shared static mutable `ItemStack`; meta edits
  (anvil rename, PDC writes) propagate to every future wand. Give per-request copies.
- `ListCommand.getPagedList:101–103` — `(page - 1) * worldsPerPage` overflows for page ≈
  `Integer.MAX_VALUE/10` → negative index → exception spam. Clamp `start ≥ 0`.
- `VersionCommand.java:113` phones home to `api.github.com` on first `/polar version`
  and caches 3 h. TLS, no secrets sent — informational/privacy note only.
- `Config.isInConfig/readPrefix` build keys as `"worlds." + worldName`
  (`Config.java:107,128,157`): a dotted name nests unexpectedly in YAML.
  Mitigated because world creation validates through `NamespacedKey` afterwards, but
  `CreateBlankCommand.java:49–51` writes the config entry *before* that validation.

---

## Areas audited and found sound

- **Command permissions.** Every subcommand literal is wrapped in
  `Commands.literal(l).requires(src -> src.getSender().hasPermission("polarpaper."+name))`
  (`PolarCmd.buildSubcommand`, PolarCmd.java:62–69) — enforced server-side by Brigadier
  at dispatch, aliases share the primary permission deliberately. Root requires
  `polarpaper.use` (CommandManager.java:27–29); `help/wand/pos1/pos2` individually gated
  (CommandManager.java:60–72). Player-only flows (`paste`, `convert`, `wand`, `relight`,
  `setspawn`, `setcenter`) all check `instanceof Player` / `getExecutor()`. No
  console-only action reachable by players was found. Delete requires an explicit
  `confirm` literal whose click-event re-dispatches under permission (DeleteCommand:143–146).
- **Path traversal in load/delete/rename/copy/browse.** All resolve through
  `WorldKey.validatePath` → `normalize().startsWith(worldsFolder)`, defeating `../`
  strings (modulo the symlink caveat M-2 and the goto gap M-3).
- **Atomic writes.** `FilePolarSource.saveBytes` and `Polar.writeAtomically`
  (temp file + `ATOMIC_MOVE`, cleanup on failure) prevent torn files; failed saves leave
  the previous file intact by design.
- **Format length fields.** VarInts capped at 5 bytes; array/string/list lengths checked
  against `readableBytes()` (`ByteArrayUtil.requireReadableLength`); palette sizes capped
  (4096/512); chunk counts sanity-checked (PolarReader:413, PolarStreamLoader:474);
  zstd frame content-size cross-checked with a 1 GiB ceiling for undeclared frames
  (PolarReader:379–405); `BlockStateCodec` cache bounded at 65 536 entries.
- **Injection.** No SQL anywhere; no secrets logged; config written via Bukkit
  SnakeYAML safe API (no arbitrary-type instantiation); chat output goes through
  Adventure `Component.text(...)` (no legacy `§` injection path observed).
- **Races.** Autosave guarded by `saveInProgress` CAS; delete orders unload-before-delete
  explicitly; archive claim/release uses a claimed-set with double-check
  (PolarChunkArchive.claim). Only L-1 remains as a benign-integrity race.

## Priority remediation order

1. H-1 bounded NBT accounting (small diff, closes the worst primitive).
2. H-2 clamp command numerics (two argument definitions).
3. M-1/M-3 cheap hardening of paste/goto.
4. M-2 symlink handling if the deployment model allows third-party writes into `worlds/`.
