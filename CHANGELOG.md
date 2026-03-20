# Changelog

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
