# Changelog

## v0.4.18 — 2026-03-22

First-class trapdoor support — trapdoors are now a distinct block type with proper horizontal-to-vertical open transform, collision shapes, and interaction handling.

### New `"trapdoor"` Block Type

Previously, trapdoors were routed through the door code path (`block_type: "door"`), which applied vertical-panel transforms (X↔Z swap) designed for doors. This was completely wrong for trapdoors — horizontal panels that flip to vertical when opened.

- Backend prompt now distinguishes `"trapdoor"` from `"door"` — trapdoors, hatches, floor grates, and cellar doors get their own geometry guidance (flat horizontal panel ~3 units thick at Y=0)
- `PoolBlockEntity`, `BlockPoolManager.BlockSlotData`, and `ModelCache.ModelData` all gain `isTrapdoor()` convenience methods

### Trapdoor Open Transform (Y↔Z Swap)

Unlike doors (X↔Z swap), trapdoors swap Y↔Z coordinates to flip from horizontal to vertical:
- Closed: `[0,0,0] → [16,3,16]` — thin along Y at bottom
- Open: `[0,0,0] → [16,16,3]` — thin along Z, then standard FACING rotation positions it on the correct edge
- Face remapping: up↔south, down↔north (east/west unchanged)
- `DynamicBlockModel.normalizeTrapdoorPanel()` — translates panel to Y=0
- `DynamicBlockModel.transformTrapdoorOpen()` — Y↔Z swap + face remap

### Collision Shapes

- Closed: `Block.box(0, 0, 0, 16, 3, 16)` — same for all facings (symmetric horizontal slab)
- Open: vertical slab per facing — NORTH=Z:0..3, SOUTH=Z:13..16, EAST=X:13..16, WEST=X:0..3

### Interaction

- Right-click toggles open/close with `WOODEN_TRAPDOOR_OPEN`/`CLOSE` sounds
- No partner-half sync (trapdoors are single blocks, unlike doors)

### Baking Pipeline

- `DynamicBlockModel.bake()` — trapdoor branch applies normalize + Y↔Z swap, uses standard `getBlockRotation()` (not door rotation)
- `RuntimeModelBaker` — trapdoor-aware `bakeHalf()`, new `bakeHalfTrapdoorOpen()` for runtime hot-swap
- `bakeOpenVariants()` — trapdoor branch normalizes, transforms, and bakes open state

## v0.4.17 — 2026-03-22

Fix open door collision — players can now walk through open doors.

### Root Cause
`PoolBlock` was missing `dynamicShape()` in its `BlockBehaviour.Properties`. Without this flag, Minecraft caches collision shapes per-BlockState at registration time using `EmptyBlockGetter` (no block entities exist). Our `getShape()` reads `PoolBlockEntity.isDoor()` to return thin door slabs, but during cache init there is no block entity — so it falls through to `Shapes.block()` (a full cube). This cached full-cube collision was used regardless of door open/closed state.

### Fix
- Added `.dynamicShape()` to `PoolBlock` properties in `ShapeCraft.java` — disables BlockState shape cache, matching vanilla's pattern for shulker boxes and moving pistons (any block whose shape depends on block entity data)
- Added `getCollisionShape()` override in `PoolBlock` — delegates to the existing 4-argument `getShape()` which handles door-aware thin slab shapes based on block entity data, facing direction, and open state

## v0.4.16 — 2026-03-22

Fix door texture/hitbox alignment for all facing directions and open/closed states.

### Root Cause
Claude generates door panels at arbitrary positions (often centered, e.g. `Z=7..9`) and with varying thin axes (some thin along Z, others along X). The previous hardcoded rotation couldn't handle this variation — panels ended up floating in the middle of the block or on the wrong edge.

### Automatic Door Panel Normalization
- `DynamicBlockModel.normalizeDoorPanel()` detects the model's thin axis (X or Z) and translates all elements so the panel is flush with the far edge (16-side) of that axis
- Returns a `NormalizedDoor` record carrying both the translated JSON and which axis is thin
- Applied before baking (closed) and before X↔Z swap (open) in both the runtime hot-swap and resource reload paths

### Axis-Aware Rotation Lookup
- `ShapeCraftModelPlugin.getDoorRotation(facing, open, thinAlongZ)` uses direct lookup tables instead of formula-based offsets
- 4 tables cover every combination of (thin-Z vs thin-X) × (closed vs open-after-swap)
- Correctly uses Minecraft's clockwise-from-above Y-rotation convention: Y90 = `(x,z)→(16-z,x)`, Y270 = `(x,z)→(z,16-x)`

### Debug Command (`/shapecraft debug`)
- `/shapecraft debug` — shows hitbox offsets + info about the block you're looking at (FACING, OPEN, slot, blockType)
- `/shapecraft debug hclosed <0|90|180|270>` — hitbox closed offset (immediate)
- `/shapecraft debug hopen <0|90|180|270>` — hitbox open offset (immediate)
- Texture debug commands removed (rotation is now automatic)
- All debug commands require op level 4

### Other Changes
- Reverted `getShape()` lazy backfill from v0.4.15 (no longer needed)
- `DoorDebugState` simplified to hitbox offsets only + `rotateFacing()` helper
- `DoorDebugSyncS2C` packet registered but no-op (kept for protocol compatibility)

## v0.4.15 — 2026-03-21

Increase generation request timeout from 30s to 60s. The backend may retry the Claude API call on malformed JSON, which can exceed 30s total.

Fix door interaction regression from v0.4.14 — pre-existing doors (placed before `BlockType` NBT field existed) couldn't be opened because `useWithoutItem()` checked the block entity which had empty `blockType`. Reverted `useWithoutItem()` to use `BlockPoolManager` (always available server-side).

Fix door outline/collision misalignment — pre-existing door block entities lacked `BlockType` in NBT, so `getShape()` couldn't detect them as doors on the client (where `BlockPoolManager` is empty). Added lazy backfill: on the server, `getShape()` copies `blockType` from `BlockPoolManager` into the block entity and triggers `sendBlockUpdated`, syncing to clients via the existing block entity packet.

## v0.4.14 — 2026-03-21

Fix door hit area not following open/close state on the client — clicking an opened door at its visual position now registers correctly.

### Root Cause
`PoolBlock.getShape()` checked `BlockPoolManager.getSlot(slotIndex).isDoor()` for door-specific collision shapes, but `BlockPoolManager` is server-side only. On the client, the lookup returned null, falling through to the static cached shape computed from the closed model JSON.

### Block Entity Door Awareness
- `PoolBlockEntity` gains `blockType` field with `isDoor()` convenience method, persisted in NBT (`"BlockType"`) and synced to clients via existing `getUpdateTag()`/`getUpdatePacket()`
- `PoolBlock.getShape()` now checks `poolBe.isDoor()` instead of `BlockPoolManager` — works on both client and server
- `PoolBlock.useWithoutItem()` also uses block entity check instead of `BlockPoolManager`
- `PoolBlock.setPlacedBy()` copies `blockType` from pool data to block entity for both lower and upper halves

## v0.4.13 — 2026-03-21

Fix door rotation: correct FACING convention mismatch, restore X↔Z hinge transform for multi-element doors, add closed door collision shapes.

### Three Root Causes Fixed
1. **FACING convention mismatch**: `PoolBlock` uses `.getOpposite()` but vanilla `DoorBlock` does not — our FACING is 180° off vanilla's, so every rotation lookup was wrong
2. **Wrong closed rotation**: mapped EAST→Y0 (vanilla's table), but needed to apply vanilla's rotation for `facing.getOpposite()` — correct closed = standard + 90°
3. **Center rotation breaks multi-element doors**: `BlockModelRotation` pivots around block center (0.5,0.5,0.5), displacing interior decorative elements; X↔Z coordinate swap transforms each element individually, preserving hinge corner (0,*,0)

### Corrected Door Rotation
- `getDoorRotation()` now uses standard block rotation + 90° for closed, + 180° for open
- Closed: WEST→Y0, NORTH→Y90, EAST→Y180, SOUTH→Y270
- Open: X↔Z swap handles hinge rotation, closed rotation handles facing

### Restored X↔Z Hinge Transform
- `DynamicBlockModel.transformDoorOpen()` restored — swaps X↔Z coordinates and remaps face keys (north↔west, south↔east) for correct multi-element door opening
- Door open state: transform closed JSON, then bake with closed rotation (not open rotation)
- `RuntimeModelBaker.bakeHalfDoorOpen()` applies same transform for runtime hot-swap

### Collision Shapes for Both States
- Added closed door shapes: WEST→X=0..3, NORTH→Z=0..3, EAST→X=13..16, SOUTH→Z=13..16
- Fixed open door shapes: WEST→Z=0..3, NORTH→X=13..16, EAST→Z=13..16, SOUTH→X=0..3
- `getShape()` now returns door-specific shapes for both closed and open states

## v0.4.12 — 2026-03-21

Fix door mechanics: client-side open rotation replaces unreliable Claude-generated open variants.

### Door Panel Geometry Guidance
- Prompt now instructs Claude to generate doors as flat panels ~3 units thick × 16 tall × 16 wide (`[0,0,0] to [3,16,16]`), preventing thin-pole closed doors
- Removed `model_open` and `upper_model_open` from the prompt output format — Claude no longer generates separate open geometry

### Client-Side Open Rotation
- New `ShapeCraftModelPlugin.getBlockRotation(Direction, boolean)` overload adds +90° Y for open doors (NORTH→Y90, EAST→Y180, SOUTH→Y270, WEST→Y0), matching vanilla door behavior
- `DynamicBlockModel.bake()` detects door blocks and reuses closed model JSON with open-aware rotation instead of selecting separate open variant JSON
- `RuntimeModelBaker.bakeOpenVariants()` reuses closed model JSON with open rotation for doors; non-door blocks retain existing open variant baking
- `RuntimeModelBaker.bakeHalf()` accepts `isDoor` flag to choose correct rotation strategy

### Collision Shape Fix
- Updated `PoolBlock` open door collision shapes to match rotated geometry: NORTH→south face, SOUTH→north face (were previously swapped)

## v0.4.10 — 2026-03-21

Interactive door mechanics for generated blocks. Doors now open and close on right-click with sound effects, synced partner halves, and distinct open/closed model variants.

### OPEN Block State Property
- Added `BooleanProperty OPEN` to `PoolBlock`, expanding block states to 16 per slot (FACING × HALF × OPEN)
- Non-door blocks ignore the property (OPEN always false)

### Door Interaction
- `PoolBlock.useWithoutItem()` checks `blockType == "door"` from pool data; toggles OPEN on right-click
- Both halves (LOWER/UPPER) toggle together via partner block lookup
- Plays `WOODEN_DOOR_OPEN`/`WOODEN_DOOR_CLOSE` sound effects
- Door open collision shape: thin 3/16-wide slab rotated by FACING direction

### Open Model Variants
- Backend prompt includes new "Interactive Blocks" section instructing Claude to generate open model variants for doors, gates, shutters, etc.
- Backend `generate.ts` parses `model_open`, `upper_model_open`, `block_type` from Claude's response and returns `model_json_open`, `upper_model_json_open`, `block_type` in the API response
- New fields flow through the full pipeline: `GenerationResult` → `BlockSlotData` → `WorldDataManager` (NBT persistence) → `GenerationCompleteS2C`/`BlockSyncS2C` (network sync) → `ModelCache.ModelData`
- `BakedModelCache` keyed by `(slotIndex, half, open)` — open and closed states render different geometry
- `DynamicBlockModel` selects open variant JSON when `open=true`, falls back to closed model if no open variant exists
- `RuntimeModelBaker` bakes both open and closed variants when open model JSON is present
- `ShapeCraftModelPlugin` iterates all 16 states per slot (FACING × HALF × OPEN)

## v0.4.9 — 2026-03-21

Fix cullface rotation for directional blocks and add backend tall block support.

### Cullface Rotation Fix
- `DynamicBlockModel.bakeQuads()` now rotates the cullface direction through the same transformation matrix that FaceBakery applies to vertex positions
- New `rotateCullface()` helper transforms the direction vector by `BlockModelRotation.getRotation().getMatrix()`, ensuring quads land in the correct face bucket after rotation
- Fixes texture/face visibility issues when placing generated blocks in non-NORTH facing directions — previously, rotated quads were stored under their pre-rotation cullface direction, so the renderer couldn't find them

### Backend Tall Block Support
- `prompt.ts`: Added tall block instructions to the system prompt — Claude now returns `upper_model` for objects that naturally span two blocks (doors, arches, tall lamps, wardrobes, etc.)
- `generate.ts`: Parses optional `upper_model` from Claude response, stringifies it, and includes `upper_model_json` in the API response (defaults to `""` when absent)

## v0.4.8 — 2026-03-21

Two-block-tall generated blocks — doors, tall lamps, pillars, arches, and other objects that naturally span 2 blocks vertically now generate as proper two-block structures instead of being squished into a single block.

### BlockHalf Enum + HALF Property
- New `BlockHalf` enum (`LOWER`/`UPPER`) implementing `StringRepresentable`
- `PoolBlock` gains `HALF` property — block states now enumerate FACING × HALF (8 variants per slot)
- Single blocks default to `HALF=LOWER`, preserving backward compatibility

### Tall Block Placement & Breaking
- `PoolBlock.getStateForPlacement()` checks space above for tall blocks — prevents placement if obstructed or at world height limit
- `PoolBlock.setPlacedBy()` places upper half with `HALF=UPPER` and the upper model JSON
- Breaking either half removes the partner (verified by same slot index) — only lower half drops the item

### Data Pipeline
- `BlockSlotData` gains `upperModelJson` field with `isTall()` helper
- `GenerationResult` carries `upperModelJson` from backend response
- `BackendClient` parses optional `upper_model_json` from API response
- `GenerationManager` validates both halves and passes `upperModelJson` through to slot assignment and network broadcast

### Network Protocol (v2)
- `GenerationCompleteS2C` and `BlockSyncS2C.BlockSyncEntry` include `upperModelJson` field
- Protocol version bumped from 1 to 2 — old clients get clear version mismatch error

### Persistence
- `WorldDataManager` saves/loads `UpperModelJson` NBT tag — defaults to `""` for old saves (zero migration)

### Client Model Pipeline
- `ModelCache.ModelData` gains `upperModelJson` field
- `BakedModelCache` key expanded to `CacheKey(slotIndex, half)` — LOWER and UPPER halves cached independently
- `DynamicBlockModel` selects model JSON based on half: UPPER uses `upperModelJson`, LOWER uses `modelJson`
- `DynamicBakedModel` passes `half` through to cache lookup
- `ShapeCraftModelPlugin` resolves both FACING and HALF in BlockStateResolver
- `RuntimeModelBaker` bakes both halves for tall blocks in single and batch paths

## v0.4.7 — 2026-03-21

Fix texture orientation on generated blocks — faces showed different cropped regions of the texture because position-based UV mapping sampled different texture areas depending on element placement within the 0–16 coordinate space.

### Origin-Based Auto-UV
- `DynamicBlockModel.computeDefaultUV()` — replaced vanilla position-based UV calculation with origin-based `[0, 0, faceWidth, faceHeight]` mapping. Every face now samples the texture from (0,0) scaled to its actual dimensions, giving consistent visual appearance across all elements.

### Backend Prompt Update
- `prompt.ts` (theblockacademy repo) — replaced vague UV guideline with explicit origin-based UV instructions and worked example, so Claude generates UVs matching the client's auto-calculation.

## v0.4.6 — 2026-03-21

Fix invisible textures on generated blocks — faces rendered correct outlines/shapes but were invisible due to missing render layer and unmerged parent textures.

### Render Layer Registration
- `ShapeCraftClient` — register all 64 pool blocks with `RenderType.cutout()` via `BlockRenderLayerMap`. Without this, blocks defaulted to SOLID render layer which discards transparent textures entirely.

### Parent Texture Inheritance Fix
- `DynamicBlockModel.bakeQuads()` — always merge parent textures via `putIfAbsent` even when the model has inline elements. Previously parent textures were only merged when elements came from the parent, leaving `#variable` references unresolved → null sprites → invisible faces.

### Diagnostics
- `DynamicBlockModel.resolveSprite()` — log warning when a `#variable` texture reference can't be resolved, making texture issues immediately diagnosable
- Downgraded v0.4.4 per-element/per-vertex diagnostic logging from `info` to `debug` to reduce log spam

## v0.4.5 — 2026-03-21

Fix block outline shape not syncing to clients — outlines showed full cube on the client side because PoolBlockEntity lacked vanilla block entity sync methods.

### Client-Side Block Entity Sync
- `PoolBlockEntity` — added `getUpdatePacket()` and `getUpdateTag()` overrides to enable vanilla block entity client sync (sends SlotIndex, DisplayName, ModelJson via `saveWithoutMetadata()`)
- `PoolBlock.setPlacedBy()` — added `sendBlockUpdated()` call after setting model data, triggering the client-bound block entity packet

This covers all sync scenarios: block placement (including multiplayer broadcast), chunk load, and player join.

## v0.4.4 — 2026-03-21

Fix block selection outline showing full cube instead of model-accurate bounds on first placement.

### Block Outline Shape Fix
- `PoolBlock` — added `slotIndex` field so each pool block knows its slot; `setPlacedBy()` copies model data from `BlockPoolManager` to the `PoolBlockEntity` at placement time
- `PoolBlockEntity.setModelJson()` — now calls `computeShape()` immediately instead of just nullifying cached shape, ensuring VoxelShape is ready for outline rendering
- `ShapeCraft` registration loop passes slot index `i` to `PoolBlock` constructor

Previously, placed blocks had no model data in their entity until world reload (NBT round-trip), so `getShape()` fell back to a full 1×1×1 cube.

## v0.4.3 — 2026-03-21

Fix ModelValidator rejecting `comment` keys in elements; add diagnostic logging for cross-model rendering offset.

### Bug Fixes
- `ModelValidator` — added `"comment"` to `VALID_ELEMENT_KEYS` set so Claude-generated documentation fields no longer trigger V-002 errors
- Backend prompt — added guideline #9 discouraging extra keys in elements (defense-in-depth)

### Debug Diagnostics
- `DynamicBlockModel.bakeQuads()` — temporary diagnostic logging for cross/rotated model rendering offset investigation:
  - Logs parent resolution, element count, and rotation per slot
  - Logs per-element from/to coordinates and element rotation details
  - Logs first element's auto-computed UV values when `computeDefaultUV` is invoked
  - Logs first quad (UP face) vertex positions to verify block-local coordinate range

## v0.4.2 — 2026-03-21

Fix invisible blocks when Claude generates parent-only models (no inline elements).

### Parent Model Resolution
- `ParentResolver` — lazy-loaded singleton registry of 50 pre-computed parent models with resolved elements and texture maps
- `parent_models.json` — bundled resource (~50KB) with flattened geometry for all `KNOWN_PARENTS`
- `DynamicBlockModel.bakeQuads()` — falls back to `ParentResolver` when model has `parent` but no `elements`; merges parent texture map (child overrides via `putIfAbsent`)
- `PoolBlockEntity.computeShape()` — same parent resolution fallback for collision shapes
- Ambient occlusion inherited from resolved parent when child doesn't set it
- Backend prompt updated to require inline `elements` (defense-in-depth; client-side resolution handles legacy/unexpected parent-only models)

## v0.4.1 — 2026-03-20

Fix block textures being replaced with distorted cobblestone/water after generation.

### Self-Validating Baked Model Cache
- `BakedModelCache` tracks a reload generation counter — incremented on every resource reload (atlas rebuild)
- `BakedSlotData` stores the generation at bake time; `getQuads()` returns null for stale entries (generation mismatch)
- `DynamicBakedModel.getQuads()` lazy-rebakes from `ModelCache` using the current atlas when cache is empty or stale
- This makes the system resilient to atlas rebuilds regardless of when/why they happen (F3+T, server resource packs, mod-triggered reloads)
- `ShapeCraftModelPlugin.onInitializeModelLoader()` increments reload generation on each resource reload
- `DynamicBlockModel.bake()` remains a thin delegate (no cache write) — `RuntimeModelBaker` and lazy-rebake are the only writers
- Removed `RuntimeModelBaker.rebakeAll()` and resource reload listener from `ShapeCraftClient` (superseded by lazy rebaking)

## v0.4.0 — 2026-03-20

Eliminate resource reload on block generation — no more loading screen.

### Runtime Model Hot-Swap
- `BakedModelCache` — shared mutable store of baked quad data keyed by slot index, thread-safe via ConcurrentHashMap
- `RuntimeModelBaker` — bakes model JSON into BakedModelCache at runtime using existing block atlas sprites, no reload needed
- `DynamicBakedModel` refactored to delegate to BakedModelCache by (slotIndex, facing) — picks up new quads on next `getQuads()` call
- `DynamicBlockModel.bakeQuads()` extracted as public static method for reuse by both reload path and runtime hot-swap
- `ShapeCraftModelPlugin` always creates delegate models (empty slots render invisible instead of stone fallback)
- `ShapeCraftClientNetworking` uses `RuntimeModelBaker.bakeAndCache()` + `levelRenderer.allChanged()` instead of `reloadResourcePacks()`
- Block sync on join uses `bakeAndCacheBatch()` for single chunk re-mesh across all synced blocks
- F3+T and resource pack changes still work via normal `ShapeCraftModelPlugin` → `bake()` path

## v0.3.0 — 2026-03-20

Backend integration — Phases 6–10 implemented. Full end-to-end generation pipeline.

### Phase 6 — Backend API Routes (theblockacademy)
- Database migration (`041_shapecraft.sql`) — 4 tables: licenses, activation_codes, generations, block_corpus (with pgvector)
- Auth middleware (`shapecraftAuth.ts`) — Bearer token validation with `shape_` prefix
- Trial provisioning (`/shapecraft/trial`) — auto-provisions 50-generation trial per server
- License activation (`/shapecraft/activate`) — `SHAPE-XXXX-XXXX` code format, 30-day licenses
- License validation (`/shapecraft/validate`) — state checking, monthly counter reset
- Generation endpoint (`/shapecraft/generate`) — Claude Sonnet 4.6 with RAG retrieval via Voyage embeddings + pgvector, content filter, atomic trial deduction, retry on malformed JSON
- Claude system prompt (`prompt.ts`) — ~2,500 tokens with block model format spec, texture catalog, quality guidelines, prompt caching support
- Health check endpoint (`/shapecraft/health`)
- License expirer updated to sweep `shapecraft_licenses` table
- Feature flag: `SHAPECRAFT_ENABLED` env var

### Phase 7 — RAG Corpus Prep Script (theblockacademy)
- `prepare-shapecraft-corpus.ts` — reads ~2,138 vanilla block models, filters variants, classifies shapes
- Batch Claude calls for semantic descriptions (~20 models/batch)
- Batch Voyage embeddings (128 texts/batch, `voyage-3-lite`, 1024 dims)
- Upserts into `shapecraft_block_corpus` with ON CONFLICT handling

### Phase 8 — Content Filter
- Server-side blocklist in `generate.ts` — rejects inappropriate descriptions before LLM call
- Client-side `ContentFilter.java` — blocklist check in `GenerationManager.submit()` before backend call
- Configurable via `contentFilterEnabled` in `config.json`

### Phase 9 — Texture Pipeline Tier 2
- `DynamicTextureManager` — loads vanilla base textures, applies color blend tinting, deterministic naming via MD5
- `SpriteLoaderMixin` — injects generated textures into block atlas during resource reload
- Tint format: `{ "#texture_var": { "color": "#FF8800", "strength": 0.5 } }`
- Client mixin config (`shapecraft-client.mixins.json`)

### Phase 10 — Multiplayer Hardening
- `synchronized` on `BlockPoolManager.assignSlot()` to prevent race conditions
- Enhanced handshake mismatch messaging — includes server/client mod versions and update instructions
- IP consistency check middleware on generate endpoint

### Discord Cog (slashAI)
- `shapecraft_commands.py` — 9 owner-only slash commands: licenses, stats, servers, player, active, set-state, hide, unhide, label
- Registered in `discord_bot.py` setup_hook

## v0.2.0 — 2026-03-20

Mod scaffold — Phases 0–5 implemented. First compilable, runnable mod.

### Phase 0 — Project Scaffold
- Fabric Loom 1.9 build with `splitEnvironmentSourceSets()`, Mojang mappings, Java 21
- 64 pre-registered pool blocks (`shapecraft:custom_0` through `shapecraft:custom_63`)
- PoolBlock (HorizontalDirectionalBlock + EntityBlock) with directional placement
- PoolBlockEntity with NBT persistence (slotIndex, displayName, modelJson)
- BlockPoolManager with slot assignment and availability tracking
- ShapeCraft creative tab showing only assigned blocks
- Gradle wrapper, fabric.mod.json, lang file

### Phase 1 — Networking Foundation
- 8 payload types: HandshakeC2S/ResponseS2C, GenerateRequestC2S, GenerationStatus/Complete/ErrorS2C, BlockSyncS2C/RequestC2S
- Server-side receivers with handshake validation and auto-sync on join
- Client-side receivers with action bar status messages
- `/shapecraft` command tree: `<description>`, `info`, `status` (op), `activate` (op), `reload` (op)

### Phase 2 — Backend Integration (mod-side)
- BackendClient with async HTTP (java.net.http, CompletableFuture, 30s timeout)
- GenerationManager with 2-thread pool, concurrent generation guard, full pipeline (license check → backend call → validation → slot assignment → broadcast)
- ModelValidator with 11 validation rules (V-001 through V-011): JSON syntax, known keys, parent resolution, element count ≤ 16, coordinate range, non-degenerate elements, texture refs, UV range, rotation angles, face directions
- VanillaAssets with ~50 known parents and ~140 known texture paths

### Phase 3 — Model Injection
- ShapeCraftModelPlugin (ModelLoadingPlugin) with BlockStateResolver per pool block
- DynamicBlockModel (UnbakedModel) — parses generated JSON, bakes via FaceBakery with element rotation, UV, texture variable resolution
- DynamicBakedModel (BakedModel) — stores pre-baked quads per Direction
- ModelCache — client-side ConcurrentHashMap keyed by slot index
- Resource reload triggered on GenerationComplete and BlockSync
- Per-FACING BlockModelRotation for directional block variants

### Phase 4 — Persistence & Sync
- WorldDataManager (SavedData) — serializes BlockPoolManager to world NBT
- Auto-load on server start, mark dirty on slot assignment
- Block sync to joining players (all assigned blocks)
- PoolBlockEntity VoxelShape computation from model JSON elements (union of bounding boxes)

### Phase 5 — License System
- LicenseState enum: UNINITIALIZED, TRIAL, ACTIVE, GRACE, EXPIRED
- LicenseManager — 5-state FSM with auto-trial provisioning, periodic validation (24h), grace period (7 days)
- LicenseValidator — async HTTP to /shapecraft/trial, /activate, /validate
- LicenseStore — config/shapecraft/license.json persistence
- DailyCapTracker — per-player daily generation limits, midnight UTC reset
- ShapeCraftConfig — config/shapecraft/config.json with poolSize, dailyCap, backendUrl, contentFilter, debug
- License + daily cap enforcement in GenerationManager
- `/shapecraft info` shows remaining generations; `/shapecraft status` shows full license state

## v0.1.4 — 2026-03-20

- Added Technical Design Document (`docs/TDD.md`) — covers system architecture, block pool strategy, model loading pipeline (ModelLoadingPlugin + BlockStateResolver), RAG pipeline, LLM integration, texture pipeline, validation system, networking protocol, license state machine, backend routes, database schema, and 8-phase implementation plan

## v0.1.3 — 2026-03-14

- Added Texture Asset Policy section to PRD — clarifies that ShapeCraft never redistributes vanilla textures

## v0.1.2 — 2026-03-14

- Fixed markdown rendering in design brief (`docs/BRIEF.md`) — removed backslash escapes and HTML entities

## v0.1.1 — 2026-03-14

- Added Product Requirements Document (`docs/PRD.md`)

## v0.1.0 — 2026-03-14

Initial project setup.

- Added design brief (`docs/BRIEF.md`)
- Added vanilla Minecraft asset corpus (~2,100 block models, blockstates, textures)
- Added project documentation (README, CLAUDE.md, CHANGELOG)
