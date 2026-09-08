# LagFixer — Folia Compatibility Audit Report

> **Date:** 2026-09-06
> **Scope:** All plugin modules, hooks, and core classes in `Folia-lagfixer`.
> **Goal:** Identify every place that is **not Folia-safe**, and provide a concrete TODO list to convert each one to proper Folia threading.

---

## 1. Background — how the plugin already supports Folia

The project already ships a Folia-aware scheduling abstraction. Understanding it is the key to every fix below.

| Layer | Class | Role |
|-------|-------|------|
| Abstract | `xyz.lychee.lagfixer.objects.AbstractFork` | Defines the scheduling API. |
| Spigot fallback | `xyz.lychee.lagfixer.support.SpigotSupport` (priority `-1`) | Plain `Bukkit.getScheduler()`. Used when Paper classes are absent. |
| Folia impl | `xyz.lychee.lagfixer.support.PaperSupport` (priority `1`) | Uses `Bukkit.getGlobalRegionScheduler()`, `Bukkit.getRegionScheduler(loc)`, `Bukkit.getAsyncScheduler()`. |
| Accessor | `SupportManager.getInstance().getFork()` | Returns whichever impl is active. |

The fork exposes six scheduling methods:

```java
runNow(boolean async, Location loc, Runnable r)        // run once
runLater(boolean async, Runnable r, long delayInMs)     // delayed once (ms)
runTimer(boolean async, Runnable r, long initDelay, long delay) // repeating (ms)
runLater(boolean async, Runnable r, long delay, TimeUnit unit)
runTimer(boolean async, Runnable r, long initDelay, long delay, TimeUnit unit)
```

**The contract on Folia:**

- `async = true` → runs on `AsyncScheduler`. **Must NEVER touch the Bukkit API, entities, players, or NMS.** Thread-safe data only.
- `async = false, loc = null` → runs on `GlobalRegionScheduler`. On Folia this is the **global tick thread** — it must NOT touch any entity/player (no per-entity state, no `getLocation`, no `teleport`, no `hidePlayer`).
- `async = false, loc = <location>` → runs on `RegionScheduler` for that exact chunk/region. **This is the only legal way to touch an entity** — pass the entity's own location so Folia dispatches to the owning region thread.

> ⚠️ **The single most common bug in this codebase:** entity-touching code is scheduled with `async = true` (or on the global thread without a location). On Folia this throws `UnsupportedOperationException` / corrupts entity state.

---

## 2. Confirmed Folia issues — by module

### 2.1 `AFKOptimizerModule.java` — 🔴 CRITICAL (the reported issue)

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/AFKOptimizerModule.java`

**What it does:** Detects AFK players and kicks / teleports / hides entities / throttles packets for them.

**Issue 1 — async timer that touches entities (line 194):**
```java
this.task = SupportManager.getInstance().getFork().runTimer(true, this,
        this.afk_check_interval, this.afk_check_interval, TimeUnit.MILLISECONDS);
```
The `run()` method (lines 77–184) iterates **all** `afk_players`, calls `player.getLocation()`, `player.isOnline()`, `player.kickPlayer()`, `player.teleport()`, `Bukkit.getOnlinePlayers()`, `player.hidePlayer()`, `player.getNearbyEntities()`, `nms.hideEntity()`/`nms.showEntity()`. All of this runs on the **async thread** because of `async = true` → **illegal on Folia.**

**Issue 2 — `runNow(false, currentLocation, ...)` is correct in isolation, but is called from inside the async timer**, so the outer loop still reads entity state (`player.getLocation()`, `isOnline()`) off the region thread (lines 82–83, 135, 142).

**Issue 3 — `load()` reads `Bukkit.getOnlinePlayers()` and `player.getLocation()` on the main thread during enable (lines 190–192)** — acceptable on enable, but the `AfkPlayer` constructor also calls `player.getLocation()` (line 288).

**Fix:**
1. Change the timer to a **global-region** timer: `runTimer(false, this, ...)`.
2. Inside `run()`, do NOT read entity state directly. Instead, for each player dispatch a `runNow(false, player.getLocation(), ...)` and read+mutate state **inside** that region-thread task.
3. Collect the list of UUIDs first (thread-safe), then per-player schedule region tasks.

---

### 2.2 `HopperOptimizerModule.java` — 🔴 CRITICAL

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/HopperOptimizerModule.java`

**What it does:** Throttles hopper item transfers, caps hoppers per chunk, sets NMS hopper cooldowns.

**Issue 1 — three async timers that touch blocks/containers (lines 71, 73, 75):**
```java
optimizationTask = fork.runTimer(true, this::optimizeHoppers, 50L, checkInterval, TimeUnit.MILLISECONDS);
cleanupTask      = fork.runTimer(true, this::cleanupInactiveHoppers, 100L, 200L, TimeUnit.MILLISECONDS);
resetTask        = fork.runTimer(true, this::resetTransferCounters, 1L, 1L, TimeUnit.SECONDS);
```
`optimizeHoppers` iterates worlds/blocks and calls `hopper.isPlaced()`, `hopper.getChunk().isLoaded()`, `hopper.getInventory()`, and `hopperOptimizer.hopperCooldown(...)` (NMS) — all on the **async** thread. Block/container state must be read/written on the region thread owning that block. (`resetTransferCounters` only clears a `ConcurrentHashMap` and is actually async-safe.)

**Issue 2 — BONUS: the `HopperOptimizer` NMS class is MISSING from every NMS version → the module is dead code.** `loadConfig()` (line 304) does `ReflectionUtils.createInstance("HopperOptimizer", this)`, which loads `xyz.lychee.lagfixer.nms.<version>.HopperOptimizer`. **No such class exists in any `nms/v*_*/` directory** (v1_16_R3 through v26_2). So `createInstance` returns null, `loadConfig()` returns false, and `ModuleManager` skips the module entirely — timers never start, listener never registers. The smart-throttling/cooldown feature is inert on every server version. (The `InventoryMoveItemEvent` handler's `shouldBlockTransfer` still runs regardless, but the actual optimization is dead.)

**Fix:** Run the iterators on the global thread (`async = false`) and wrap every block/container access in `runNow(false, block.getLocation(), ...)`. And either implement the missing `HopperOptimizer` NMS class or remove the module.

---

### 2.3 `RedstoneLimiterModule.java` — 🔴 CRITICAL

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/RedstoneLimiterModule.java`

**What it does:** Counts redstone/piston ticks per chunk; in `complete()`, if a chunk exceeds the limit it alerts ops and optionally breaks the offending blocks (sets them to AIR). A periodic task (every 2s) drives `run()` to roll up the counters.

**Issue 1 — async timer that touches blocks (line 126):**
```java
this.task = SupportManager.getInstance().getFork().runTimer(true, this, 1L, 2L, TimeUnit.SECONDS);
```
The `run()` method drives `counter.complete(...)`, which calls `block.setType(Material.AIR)`. Block mutation on the async thread is illegal on Folia. (Note: line 188 `runNow(false, loc, () -> blockSet.forEach(...))` is correct in isolation, but it's invoked from within the async `run()`.)

**Issue 2 — data race on `TickCounter` state.** `TickCounter.addTick(...)` is called from `onRedstone`/`onPiston` (which fire on the **owning chunk's region thread**) and does `this.blocks.add(block); this.ticks += size;`. `TickCounter.complete(...)` is called from the **async timer's `run()`** and reads+clears `this.blocks`/`this.ticks`. `HashSet<Block> blocks` is not thread-safe and `ticks` is a plain `int` → concurrent `addTick` vs `complete` on the same counter is a data race (possible `ConcurrentModificationException` / miscount) on Folia's multi-region threads.

**Fix:** Change timer to `runTimer(false, this, ...)` (global thread) and wrap each block mutation in `runNow(false, block.getLocation(), ...)`. Plus make each `TickCounter` thread-safe: synchronize `addTick` and `complete` on the counter (or use `AtomicInteger` for ticks and a synchronized/copied set for blocks).

---

### 2.4 `TrashDisposalModule.java` — 🟡 MEDIUM

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/TrashDisposalModule.java`

**What it does:** A `/trash` command giving players a trash inventory, with a periodic cleanup of stored items and optional Abyss integration.

**Issue 1 — async timer mutates a Bukkit `Inventory` (line 89, `run()` lines 122–142):**
```java
this.task = fork.runTimer(true, this, this.cleanupInterval, this.cleanupInterval, TimeUnit.SECONDS);
```
`run()` calls `inv.clear()` and iterates `inv.getContents()` on a Bukkit `Inventory` from the **async** thread — violates Folia rule #2 (async must not touch the Bukkit API). Note: the agent-verified finding is that this does **not** touch entities directly; it's an Inventory/Bukkit-API mutation from async.

**Issue 2 — data race on non-thread-safe collections:**
- `playerTrashInventories` is a plain `HashMap` (line 33), read by the async cleanup `run()` and mutated on the command thread → data race.
- `globalTrashInventory` (line 34) is a plain field shared between the async timer and command thread.

**Fix:** Keep the async timer but have it only **collect item stacks** (data) into a list; apply `inv.clear()` on a region thread via `runNow(false, someLocation, ...)`. Convert `playerTrashInventories` to `ConcurrentHashMap` and make `globalTrashInventory` a `volatile`/atomic reference.

---

### 2.5 `MobAiReducerModule.java` — 🔴 CRITICAL

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/MobAiReducerModule.java`

**What it does:** On creature spawn (and chunk-entity-load), replaces/augments creature AI goals via NMS. A periodic task calls `mobAiReducer.purge()` to trim the weak `optimizedMobs` map.

**Issue 1 — spawn path runs NMS `optimize()` on the async scheduler (line 88):**
```java
SupportManager.getInstance().getFork().runNow(true, e.getLocation(),
        () -> this.mobAiReducer.optimize(e.getEntity(), false));
```
When config `async=true`, this routes to the **async scheduler** despite passing `e.getLocation()`. `optimize()` mutates the entity's NMS goal selector (`goalSelector.getAvailableGoals()`, `pgw.stop()`, `handle.collides=`, `setSilent(...)`) — entity mutation on the async thread.

**Issue 2 — async purge timer reads NMS entity liveness (line 141):**
```java
this.task = SupportManager.getInstance().getFork().runTimer(true,
        () -> this.mobAiReducer.purge(), 60, this.purge_interval, TimeUnit.SECONDS);
```
`purge()` does `optimizedMobs.keySet().removeIf(ent -> !ent.isAlive() || !ent.valid)` — reading NMS entity fields off the async thread is a data race.

**Issue 3 — chunk-load handler has the same async bug** (`MobAiReducer.java` onLoad, ~line 182): `runNow(true, new Location(...), () -> optimizeEntities(e.getEntities()))` runs AI mutation on the async scheduler. The `force_load` path in `MobAiReducerModule` (line 132) is the **correct pattern** (`async=false` + real location) — the template to copy.

**Fix:** Change line 88 to `runNow(false, e.getLocation(), ...)`. Change line 141 to `runTimer(false, ...)` (global scheduler; reading liveness for bookkeeping is the common compromise — strictly, schedule per-world via region scheduler). Fix the chunk-load handler the same way: `async=false` with the chunk's location (already computed).

---

### 2.6 `ExplosionOptimizerModule.java` — 🟡 MEDIUM

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/ExplosionOptimizerModule.java`

**What it does:** Optimizes explosions, anti-chain logic.

**Issue — async timers that read location-keyed maps (lines 252, 258, 274):**
```java
runLater(true, () -> this.recent_explosions.remove(locationKey), this.anti_chain_cooldown);
runLater(true, () -> this.protected_locations.remove(locationKey), this.anti_chain_cooldown);
antiChainCleanupTask = runTimer(true, () -> { ... }, ...);
```
These only mutate in-memory `Map`s keyed by location strings — **no direct Bukkit API**. Lower severity, but on Folia the async scheduler runs concurrently with region threads, so the maps need to be concurrent (`ConcurrentHashMap`) and the cleanup timer should be `async = true` is actually fine *if* the maps are thread-safe. **Verify `recent_explosions` / `protected_locations` are `ConcurrentHashMap`.** The event-handler block reads (`location.getWorld().getNearbyEntities(...)` at line 187) run on the event thread (region thread) — that part is fine.

**Fix:** Ensure the maps are `ConcurrentHashMap`. The async scheduling here is acceptable for pure map mutation.

---

### 2.7 `WorldCleanerModule.java` — 🔴 CRITICAL (worst module)

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/WorldCleanerModule.java`

**What it does:** Periodically removes old ground items, qualifying creatures, and projectiles; sends countdown alerts; manages the Abyss GUI inventory.

**Issue 1 — global timer that does entity removal/damage (line 306, `run()` lines 178–299):**
```java
this.task = support.getFork().runTimer(false, this, 1L, 1L, TimeUnit.SECONDS);
```
`async=false`, no location → on Folia this runs on the **global tick thread**, which must not touch any entity. But `run()` does all of this from the global thread:
- `Bukkit.getOnlinePlayers()` (line 180) — rule #4.
- `world.getEntities()` then `livingEntity.damage(Double.MAX_VALUE)` / `ent.remove()` (lines 190–223) — entity mutation/removal from the global thread, textbook rule #1 violation.
- `Bukkit.createInventory` + `inv.setItem`/`addItem` (lines 249–261) — Bukkit API on the global thread, rule #3.

**Issue 2 — Abyss auto-close runs on the global thread and mutates players (line 273):**
```java
SupportManager.getInstance().getFork().runLater(false, () -> {
    new HashSet<>(inv.getViewers()).forEach(HumanEntity::closeInventory);  // entity op, rule #1
    inv.clear();                                                          // Bukkit API, rule #3
}, this.items_abyss_close, TimeUnit.SECONDS);
```
`runLater(false, ...)` with no location → global thread; `HumanEntity::closeInventory` mutates players.

**Issue 3 — data race on `inventories` (line 46):** a plain `ArrayList`, mutated from the global-thread `run()` and `runLater`, and read from the region-thread `InventoryClickEvent` handler → needs a thread-safe collection.

**Fix:** This module cannot run as one global task. Replace the single global timer with per-world region-thread dispatch: for each allowed world, schedule `runNow(false, worldLocation, ...)` and do the `getEntities()`/`remove()`/GUI work inside it. The Abyss auto-close must also dispatch per-viewer region tasks (or capture a representative world location). Convert `inventories` to a thread-safe collection.

---

### 2.8 `EntityLimiterModule.java` — 🟡 MEDIUM

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/EntityLimiterModule.java`

**What it does:** Limits entity counts; removes overflow entities.

**Issue — global-region overflow timer that removes entities (line 110):**
```java
this.overflow_task = SupportManager.getInstance().getFork().runTimer(false, () -> { ... }, ...);
```
Inside, `entity.remove()` (line 158) is called. On the global thread this is illegal on Folia. The event handlers (`handleEvent`) run on the event thread — fine.

**Fix:** Wrap each `entity.remove()` in `runNow(false, entity.getLocation(), ...)`.

---

### 2.9 `LagShieldModule.java` — 🔴 CRITICAL

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/LagShieldModule.java`

**What it does:** Server lag protection. A periodic task reads TPS, toggles per-feature flags, and — when mob AI is toggled — sets AI on **every** `LivingEntity` across allowed worlds; also does dynamic view/simulation distance and game-rule tuning.

**Issue — global-region timer mutates all entities + cross-world state (line 235):**
```java
this.task = SupportManager.getInstance().getFork().runTimer(false, this, 1L, 1L, TimeUnit.MINUTES);
```
`async=false`, no location → Folia **global tick thread**, which must not touch any entity. But `run()` does, on that thread:
- `w.getLivingEntities()` then `nms.setEntityAi(le, false/true)` for **every LivingEntity in every allowed world** — entity mutation on the global thread. `SupportNms.setEntityAi` calls `mob.setNoAi/setAggressive/setSilent/collides` (NMS) — illegal off its region thread.
- `nms.setViewDistance(w, ...)`, `setSimulationDistance(w, ...)`, `w.setGameRule(RANDOM_TICK_SPEED, ...)` for every allowed world — cross-world world-state mutation on the global thread.

**Fix:** Split `run()`:
1. Flag computation (TPS thresholds) + world-level changes (view/simulation distance + gamerule) can stay on the global region scheduler.
2. The entity-AI loop must re-dispatch per entity to its owning region: `for (World w : allowed) for (LivingEntity le : w.getLivingEntities()) fork.runNow(false, le.getLocation(), () -> nms.setEntityAi(le, false));`. Even the `getLivingEntities()` enumeration must move onto a region thread — safest is one `runNow(false, w.getSpawnLocation(), ...)` per world that does both the enumeration and the AI loop.

---

### 2.10 `VehicleMotionReducerModule.java` — 🔴 CRITICAL

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/VehicleMotionReducerModule.java`

**What it does:** Optimizes (silences/removes collision for) boats and minecarts. On `force_load`, iterates all living entities in allowed worlds and applies the NMS optimizer.

**Issue — cross-region entity mutation (line 84, `load()` lines 81–91):**
```java
SupportManager.getInstance().getFork().runNow(false, new Location(w, 0, 100, 0), () -> {
    w.getLivingEntities().stream().filter(this::isEnabled)
            .forEach(ent -> this.vehicleMotionReducer.optimize(ent));
});
```
The task runs on the region thread for chunk `(0,100,0)`, but then iterates `w.getLivingEntities()` — entities that may be in **other regions** — and calls `optimize(ent)` (an NMS/entity mutation) on each. On Folia, mutating an entity from a region thread that does **not own it** is a hard rule #1 violation ("Asynchronous entity access"). The `EntityPlaceEvent` handler (`onEntityPlace`) is fine (fires on the region thread).

**Fix:** Per-entity scheduling: capture each vehicle's location and dispatch `runNow(false, vehicle.getLocation(), () -> optimize(vehicle))` so each entity op runs on its owning region thread. The `force_load` dispatch through the fork is the right idea — just pin each entity to its own real location.

---

### 2.11 `AbilityLimiterModule.java` — 🟢 LOW

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/AbilityLimiterModule.java`

**Issue — `runLater(false, () -> player.setCooldown(...), 50L)` (line 82)** runs on the global thread without a location and mutates a player. Should pass the player's location.

**Fix:** Wrap in `runNow(false, player.getLocation(), () -> player.setCooldown(...))`.

---

### 2.12 `InstantLeafDecayModule.java` — 🟢 SAFE

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/InstantLeafDecayModule.java`

**What it does:** Breaks leaf blocks instantly (propagates to neighbors) on block break / leaf decay.

**No Folia issues.** This module does **not use the fork at all** — no timers, no async. `onBlockBreak`/`onLeavesDecay` and the `breakLeaves` flood-fill run inside `BlockBreakEvent`/`LeavesDecayEvent`, which Folia fires on the owning region's thread. `block.breakNaturally()`, `setType(AIR)`, `getRelative(...)` are all on the correct thread. No changes required. (The synchronous flood-fill can spike tick time on a large tree, but that's a performance concern, not a Folia bug.)

---

### 2.13 `ConsoleFilterModule.java` — 🟢 LOW

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/modules/ConsoleFilterModule.java`

Purely log/filter — no entity access. Likely fine. Verify it doesn't schedule async tasks that touch the server.

---

## 3. Hooks — audit summary

> Agent-verified. Hooks are mostly API wrappers; the two below call global Bukkit APIs from non-region threads.

| File | Severity | Notes |
|------|----------|-------|
| `PacketEventsHook.java` | OK | Runs on PacketEvents network thread; only reads `event.getUser().getUUID()` and the `afk_players` `ConcurrentHashMap`. Depends on `AFKOptimizerModule` (the critical issue), but is itself safe. |
| `PlaceholderAPIHook.java` | 🟡 **MEDIUM** | See §3.1 — async PAPI path calls `Bukkit.getOnlinePlayers()` / `Bukkit.getWorlds()`. |
| `SparkHook.java` | 🟢 **LOW** | See §3.2 — global timer reads `Bukkit.getOnlinePlayers()` + dispatches a console command hourly. |
| `MythicMobsHook.java` / `ModelEngineHook.java` / `RoseStackerHook.java` / `UltimateStackerHook.java` / `WildStackerHook.java` / `StackMobHook.java` / `BetterModelHook.java` / `LevelledMobsHook.java` | OK | API helpers, no self-scheduling. |
| `LagFixer.java` / `ModuleManager.java` / `SupportManager.java` / `HookManager.java` | OK | Lifecycle/bootstrap only. |

### 3.1 `PlaceholderAPIHook.java` — 🟡 MEDIUM

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/hooks/PlaceholderAPIHook.java`

**What it does:** Exposes LagFixer stats (TPS, entity counts, memory, loaded chunks, AFK counts) as a PAPI expansion. The shared `response()` backs both `onPlaceholderRequest` (sync) **and** `onRequest` (called **asynchronously** by PAPI on a background thread).

**Issue — global/iterated Bukkit calls reachable from PAPI's async path:**
```java
return Integer.toString(Bukkit.getOnlinePlayers().size());          // async thread
for (World world : Bukkit.getWorlds()) {
    chunks += world.getLoadedChunks().length;                       // world query off-thread
}
```
`Bukkit.getOnlinePlayers()` and the `Bukkit.getWorlds()`/`world.getLoadedChunks()` iteration run off the region/main thread when PAPI invokes `onRequest` asynchronously → violates Folia rule #4.

**Fix:** Keep a lightweight cache updated by a global-region timer (online count, world count, loaded chunks → volatile fields) and have `response()` read the cached snapshot instead of calling `Bukkit.*` directly. The sync `onPlaceholderRequest(Player, ...)` path is fine as-is.

### 3.2 `SparkHook.java` — 🟢 LOW

**File:** `plugin/src/main/java/xyz/lychee/lagfixer/hooks/SparkHook.java`

**What it does:** Registers a Spark `ResourceMonitor` and a once-per-hour heuristic that opens the Spark profiler when >20 players are online.

**Issue — global timer reads `Bukkit.getOnlinePlayers()` + dispatches a console command:**
```java
this.task = SupportManager.getInstance().getFork().runTimer(false, () -> {
    if (... && Bukkit.getOnlinePlayers().size() > 20) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spark profiler open");
    }
}, 1L, 1L, TimeUnit.HOURS);
```
Runs on the global region scheduler. Reading `getOnlinePlayers()` (cross-world snapshot) and `dispatchCommand` from the global thread is discouraged on Folia. LOW severity — runs hourly, no entity mutation.

**Fix:** Maintain an online-player count updated by join/quit events and read that cached value; dispatch the command on the main thread.

---

## 4. Root-cause patterns (what to search for when fixing)

When converting, grep for these anti-patterns across the whole codebase:

| Anti-pattern | Why it breaks on Folia | Correct form |
|--------------|------------------------|--------------|
| `runTimer(true, ...)` that touches entities/blocks/players | Runs on async thread | `runTimer(false, ...)` + per-entity `runNow(false, entity.getLocation(), ...)` |
| `runNow(true, loc, ...)` that touches the entity at `loc` | Async thread touching entity | `runNow(false, loc, ...)` |
| `runNow(false, <fake/constant Location>, ...)` | Wrong region thread (or unloaded chunk) | `runNow(false, entity.getLocation(), ...)` — real location |
| `runTimer(false, this, ...)` whose `run()` iterates entities | Global thread touching entities | Iterate UUIDs/thread-safe list only; dispatch per-entity region tasks |
| `Bukkit.getOnlinePlayers()` from a timer | Global/async thread | Collect UUIDs on region thread, or iterate per-world via region scheduler |
| `entity.remove()` / `block.setType()` outside region thread | Illegal entity/block mutation | Wrap in `runNow(false, location, ...)` |

---

## 5. TODO — convert each module to Folia-safe

Priority order (CRITICAL first). Each item should be implemented, then tested on a Folia build.

### 🔴 CRITICAL

- [ ] **AFKOptimizerModule** — Convert the async `runTimer(true, ...)` (line 194) to a global-region timer (`runTimer(false, ...)`). Inside `run()`, do not read entity state directly; collect UUIDs, then per-player dispatch `runNow(false, player.getLocation(), ...)` and perform all `getLocation`/`kickPlayer`/`teleport`/`hidePlayer`/`getNearbyEntities`/`nms.hideEntity|showEntity` inside that region task. Replace `Bukkit.getOnlinePlayers()` (line 135) with same-world `player.getWorld().getPlayers()`. Make `AfkPlayer.hiddenEntities` thread-safe (`ConcurrentHashMap.newKeySet()`). **(The reported issue.)**
- [ ] **WorldCleanerModule** — The global `runTimer(false, ...)` (line 306) `run()` does entity removal/damage + `Bukkit.getOnlinePlayers()` + `Bukkit.createInventory` + `HumanEntity::closeInventory` on the global thread. Rework to per-world region dispatch: one `runNow(false, worldLocation, ...)` per allowed world that does the `getEntities()`/`remove()`/GUI work. Make `inventories` a thread-safe collection.
- [ ] **LagShieldModule** — Global timer (line 235) mutates every `LivingEntity` AI (lines 94–104) + cross-world gamerules/distance. Keep flag-computation + world-level changes on the global scheduler; re-dispatch the entity-AI loop per entity to its owning region (`runNow(false, le.getLocation(), () -> nms.setEntityAi(...))`), or one region task per world.
- [ ] **HopperOptimizerModule** — Change the three `runTimer(true, ...)` timers (lines 71/73/75) so the outer iteration runs on the global thread and each block/container access wraps in `runNow(false, block.getLocation(), ...)`. **`resetTransferCounters` is already async-safe** (clears a `ConcurrentHashMap`). Also: the `HopperOptimizer` NMS class is **missing from every NMS version** → the module never loads. Either implement it or remove the module.
- [ ] **MobAiReducerModule** — Spawn path `runNow(true, ...)` (line 88) → `runNow(false, e.getLocation(), ...)`. Purge `runTimer(true, ...)` (line 141) → `runTimer(false, ...)` (or per-world region tasks). Fix the identical async bug in the chunk-load handler (`MobAiReducer.java` onLoad ~line 182). The `force_load` path (line 132) is the correct template.
- [ ] **RedstoneLimiterModule** — Change `runTimer(true, ...)` (line 126) to `runTimer(false, ...)`; wrap `block.setType(AIR)` in `runNow(false, block.getLocation(), ...)`. Plus fix the **data race**: `TickCounter.addTick` (region thread) vs `complete` (timer thread) share a non-thread-safe `HashSet<Block>` + plain `int ticks` — synchronize both methods on the counter (or use `AtomicInteger` + synchronized/copied set).
- [ ] **EntityLimiterModule** — Wrap each `entity.remove()` (line 158) inside the overflow timer (line 110) with `runNow(false, entity.getLocation(), ...)`. Iterate chunks on the global thread only; dispatch the per-chunk entity work to its region.
- [ ] **VehicleMotionReducerModule** — Replace the fixed `new Location(w, 0, 100, 0)` (line 84) dispatch with **per-entity** `runNow(false, vehicle.getLocation(), () -> optimize(vehicle))` so each entity op runs on its owning region thread (cross-region entity mutation is a hard Folia violation).

### 🟡 MEDIUM

- [ ] **ExplosionOptimizerModule** — Verify `recent_explosions` and `protected_locations` are `ConcurrentHashMap` (the async timers at lines 252/258/274 are acceptable only if thread-safe). `handleExplosionEffects` damages/sets-velocity on nearby entities that may straddle regions — ideally dispatch each to its own region. Event-handler block access is fine (region thread).
- [ ] **TrashDisposalModule** — Async `runTimer(true, ...)` (line 89) calls `inv.clear()` on a Bukkit `Inventory` from the async thread. Have the timer only collect item stacks (data), then `inv.clear()` on a region thread. Convert `playerTrashInventories` to `ConcurrentHashMap` and `globalTrashInventory` to a `volatile`/atomic reference.
- [ ] **PlaceholderAPIHook** — Async PAPI `onRequest` path calls `Bukkit.getOnlinePlayers()`/`Bukkit.getWorlds()`/`world.getLoadedChunks()`. Cache these values via a global-region timer (volatile snapshot) and have `response()` read the cache.

### 🟢 LOW

- [ ] **AbilityLimiterModule** — Replace global `runLater(false, () -> player.setCooldown(...), 50L)` (line 82) with `runNow(false, player.getLocation(), ...)` (or set cooldown directly in the handler — already on the region thread).
- [ ] **SparkHook** — Global timer (line 36) reads `Bukkit.getOnlinePlayers()` + dispatches a console command hourly. Cache the online count via join/quit events; dispatch the command on the main thread.
- [ ] **ConsoleFilterModule** — Confirmed safe (file I/O + log filtering, no entity access). No change.

### 🟢 SAFE (no change required)

- [ ] **InstantLeafDecayModule** — Confirmed Folia-safe: pure synchronous region-thread event listener, no fork usage.

### 🧪 TESTING

- [ ] Build against a Folia server and load each module one at a time; watch for `UnsupportedOperationException` / `IllegalStateException` ("Accessing entity from wrong thread").
- [ ] Enable Folia's built-in thread-access checks (Paper's `-Dpaper.debug-thread-hits` / strict region-thread assertions) during QA.
- [ ] Regression-test on plain Spigot/Paper (non-Folia) to confirm the `SpigotSupport` fallback path still works.

---

## 6. Recommended refactor — a shared helper

To avoid repeating the "collect UUIDs → dispatch per-entity region tasks" pattern, add a helper to `AbstractFork` / a new `FoliaUtils` class:

```java
/** Thread-safe: call from any timer. Dispatches one region task per entity. */
public static void runPerEntity(Collection<? extends Entity> entities,
                                java.util.function.Consumer<Entity> action) {
    for (Entity e : entities) {
        if (e == null || !e.isValid()) continue;
        Location loc = e.getLocation();          // must capture on a thread that may read it
        SupportManager.getInstance().getFork()
                .runNow(false, loc, () -> {
                    if (e.isValid()) action.accept(e);
                });
    }
}
```

> Note: capturing `e.getLocation()` must itself happen on a region thread — so first collect the entity list on the global thread, then per-entity re-read the location inside its own `runNow(false, ...)`. The helper makes the intent explicit and keeps every module consistent.

---

*Bottom line: the scheduling abstraction (`AbstractFork` + `PaperSupport`) is already correct and Folia-ready. The bug is that **most modules call it wrong** — they either go `async = true` while touching entities, or run on the global thread without a location. Fixing each module means: global timer to enumerate, then `runNow(false, entity.getLocation(), ...)` for every entity/block mutation.*
