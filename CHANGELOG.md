# Changelog

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
