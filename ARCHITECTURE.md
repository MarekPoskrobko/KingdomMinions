# KingdomMinions architecture

## Problem and boundaries

KingdomMinions provides server-side workers controlled by a player's wand on Paper/Purpur. The server owns worker behavior and persistence; players do not install a client mod. It uses the public Paper API, regular Bukkit plugin metadata, and standard Minecraft entities and particles.

## Sources of truth

- `data.yml`: wand grants, worker identities and names, saved locations, inventories, settings, active jobs and delivery state.
- Item Persistent Data Container: grant token and wand owner.
- Entity Persistent Data Container: worker identity and owner.
- `events.jsonl`: append-only records of important grants, orders, deliveries and recovery actions.
- Minecraft world: current blocks, entities and containers.
- Translation files: UI text; each player's Minecraft locale determines the selected catalog.

## Domain model

A ruler is a player with one active wand grant. A grant is a replaceable token bound to the player's UUID. A worker is a server entity owned by that player. A selection contains two corners on a plane and a face direction. A job owns pending steps, assigned steps, individual worker queues, timers and a streamed excavation or leveling plan. Delivery has one saved chest or ground destination and optional one-shot overrides for individual workers.

## Main workflows

1. An administrator grants a wand to an online player.
2. The player selects corners; owner-only particles show the boundary and dimensions.
3. A validated menu action starts a job and records its state and audit event.
4. Workers receive separate steps, find safe reachable positions, perform timed work and collect drops.
5. A full backpack triggers delivery, followed by a return to the assigned work. Far chest trips can use teleportation. Owner and one-shot ground deliveries preserve the saved destination.
6. Pause keeps work and inventories. Cancellation discards the active job and routes remaining cargo toward the saved destination or owner. Dismissal returns stored items.
7. Clean server shutdown persists work. Worker discovery reconciles saved identities with loaded entities.

## Scheduling and safety

Leveling uses a layer cursor and bounded lookahead instead of constructing an entire mountain-sized queue. Workers may descend independently into available columns, while higher work reserved in the same column blocks lower shared steps. Connected tree logs become an individual worker's queue. Movement chooses a safe standing position and may use a short temporary support when needed; supports are tracked and cleaned after use.

Liquid sealing, excavation lining, and lighting are separate behaviors. Lighting and lining settings are captured when a job starts. Existing stairs and torches are protected from regular excavation. The plugin respects protection events in the paths that emit them, but compatibility with each protection plugin must be tested; this is not a land-claim system.

## Localization

Each UI message uses a catalog key and numbered parameters. Supported language files are indexed by `lang/languages.list`. Per-player locale selects a regional catalog, then the base language, then English. Server-side translation overrides are validated against English placeholders. Names supplied by players are never translated. Active wand descriptions and open menus refresh after locale changes.

## Persistence and recovery

Job queues, cursors, assigned work, delivery origins and inventories are saved together. Keep plugin data and world backups together. Do not discard `data.yml` during upgrades. Important changes should retain compatibility with existing records and be verified by restart tests.

## Validation boundaries

Unit tests protect core planning and localization rules. A separate disposable Purpur verification harness has exercised jobs, inventories, delivery, language menus and restart behavior during development. Its latest run for 1.0.27 passed 603 checks; these checks are not a substitute for real-client testing or compatibility with a server's private plugin pack. The public repository's ordinary build runs its unit tests, not that disposable server harness.

## Deliberate limits

This is a small single-plugin system with YAML persistence, not a distributed service. It has no XP ritual, no AI service, and no client-side renderer. Large destructive orders should be tried on a test area before use on valuable worlds. Open water is not filled by terrain leveling; enclosed water requires a continuous boundary at the target floor height within the selection.
