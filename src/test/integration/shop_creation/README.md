# Shop creation regression (real Fabric server)

Run from the project root after implementing the production fix:

```bash
python -X utf8 src/test/integration/shop_creation/run.py
```

Requirements: Python 3.10+, Git Bash `bash` on PATH, the project's Gradle/JDK 21 setup,
and an **absolute** writable `PI_SCRATCH_DIR` (for example `H:/pi/data/scratch/session`).
No Python packages or additional project dependencies are required. This is explicitly
opt-in; it is not part of `test`, `build`, or the production mod jar.

## Isolation and bounds

The runner discovers the project from its own parents. It writes a temporary Gradle
init script, exports the **current** Loom `runServer` launch data after
`configureLaunch` and `classes`, and compiles the probe with the exported JDK's
`javac -proc:none -encoding UTF-8`. The invocation is via Git Bash:
`bash ./gradlew -I <scratch-init> exportShopCreationLaunch --console=plain`.
No permanent Gradle changes are needed. Gradle build output, probe classes/jar,
argument files, logs, result files, and server data are retained beneath one unique
`$PI_SCRATCH_DIR/shop-creation-*` directory, printed at startup. Normal Gradle/Loom
dependency caches may still be populated. Remove that printed directory to clean up.

Only the scratch probe jar is added with `-Dfabric.addMods`; nothing is added to the
main jar. The exported classpath is flattened into one Java `@args` file rather
than nesting Loom's classpath `@file`. Named `fabric.dli.*` options, other JVM
options, server arguments, and system properties are preserved. An unexpected
Loom classpath-file format fails explicitly instead of silently dropping options.

The isolated server has its own EULA and valid flat generator, binds only to
`127.0.0.1` on ephemeral port `0`, and uses view/simulation distance `2`,
`-Xmx1G`, and four active processors. Two separate JVMs restart the **same** server
root. Each phase waits for `SERVER_STARTED` plus the first `END_SERVER_TICK`,
writes a fresh `phaseN.txt` with PASS/FAIL assertions, and requests normal halt.
Success requires both a zero JVM exit code and that phase's fresh PASS result.
Limits: launch export 600s, compilation 120s, each server 240s. On server timeout
only the runner's own JVM is terminated (then killed if needed); other servers
and Java processes are never searched for or killed. Console assertions and
failure-log tails are bounded; complete logs remain in scratch.

## Expected checks

- Non-OP `admin` rank, explicit `habi.admin`, scoped `habi.shop.admin`, and survival
  OP authorization share the player/source predicate; ordinary users are denied
  both commands and direct category/item mutations.
- GUI creation accepts Chinese/color display names, generates safe unique
  `category_*` IDs, and immediately opens the created category. Greedy Chinese
  `/shop addcategory`, no-argument `/shop add`, legacy explicit-ID `addCategory`,
  and `/shop add <id> <buy> <sell>` remain usable.
- Held BRICK and inventory AMETHYST_SHARD samples have distinct custom names and
  lore. Creation prompts buy then sell, defaults to `10`/`0`, preserves complete
  components and sample counts, and commits accepted prices. A disabled sell
  side is supported; both-zero, invalid prices, and duplicates append no drafts.
- Category player views expose `商城管理` and current-category `新增商品` only to
  administrators. Public `openCategory` and `openItem` still work. Revoking the
  scoped permission before final submission prevents creation.
- Native input has paper/cancel slot 0 and lime/confirmation slot 1. A **new vanilla
  `AnvilMenu`** receives the server display stacks/cost; its `getSlot(i).mayPickup`
  on the actual zero-XP survival fake determines which confirmation can be clicked.
  Result slot 2 is never clicked unconditionally or cited as native-clickable.
  Non-primary clicks cannot transfer icons; cancel/close restore no fake items;
  experience is unchanged. A recording synchronizer verifies `MenuGui.resync`
  forwards a full update to text input.
- The second JVM finds the saved category by display name and products by item
  type/name, verifies captured generated IDs, prices, components, and exactly
  three accepted products. No explicit manager reload or forced save substitutes
  for ordinary mutation persistence and normal shutdown.
- **A regular buyer actually purchases the newly-created product through
  `manager.buyItem` in both phases.** Assertions print the quoted total and actual
  wallet debit and verify the matching component item arrives in inventory.

## API and client caveats

The fixture expects the production fix's
`SystemShopManager.authorized(ServerPlayer)`, `authorized(CommandSourceStack)`,
`createCategory(ServerPlayer, String)` returning `SystemShopCategory`, and existing
manager/GUI APIs. Buttons are found by semantic prefixes, not chest slot indices;
only the native anvil slot contract and the deliberately seeded player inventory
slot are fixed.

This is a real dedicated Fabric server with Fabric `FakePlayer` instances whose
`openMenu`/`closeContainer` install/remove the actual production menus. Command
execution, serialization, economy mutation, and restart are real server behavior.
It is **not** a connected graphical vanilla client or a packet-level UI test:
the vanilla anvil model checks native pickability, and post-click resync is invoked
explicitly. It cannot establish rendered appearance, network ordering, or real
mouse/rename packet behavior. A manual vanilla-client check remains appropriate
for those details; server callback success alone is not proof slot 2 works.
