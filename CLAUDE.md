# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ShapeCraft is a Minecraft mod (Fabric 1.21.1) that generates custom block models from natural language descriptions using Claude Sonnet 4.6 backed by a RAG index of ~2,100 vanilla block definitions. Status: **v0.4.17** — Phases 0–10 implemented (scaffold, networking, backend client, model injection, persistence, license, backend API routes, RAG corpus, content filter, texture tinting, multiplayer hardening, two-block-tall blocks, door mechanics). Backend routes and Discord cog live in theblockacademy and slashAI repos respectively.

Design docs: `docs/BRIEF.md` (design brief), `docs/PRD.md` (product requirements), `docs/TDD.md` (technical design)

## Build Commands

```bash
# Java 21 required — set JAVA_HOME before all Gradle commands
JAVA_HOME="/c/Users/slash/AppData/Roaming/PrismLauncher/java/java-runtime-delta" PATH="$JAVA_HOME/bin:$PATH" ./gradlew build
./gradlew runClient   # Test in Minecraft client
./gradlew runServer   # Test dedicated server
```

No tests or CI — validation is manual via `runClient`/`runServer`. Version is in `gradle.properties` (`mod_version`).

## Architecture

### Two-Phase Model Injection Pipeline

The core architecture splits model injection into two distinct paths:

**Phase 1 — Resource Reload (cold path):** During startup or full reload, `ShapeCraftModelPlugin.onInitializeModelLoader()` registers `BlockStateResolver` callbacks for all 64 pool blocks. Resolvers create `DynamicBlockModel` instances (UnbakedModel) for each state combination (FACING × HALF × OPEN). The engine calls `bake()` with the correct `spriteGetter` after atlas stitching, populating `BakedModelCache`.

**Phase 2 — Runtime Hot-Swap (warm path):** On generation complete, `ShapeCraftClientNetworking` handles `GenerationCompleteS2C` by calling `RuntimeModelBaker.bakeAndCache()` on the render thread. This bakes the JSON independently and updates `BakedModelCache` without a full reload. `levelRenderer.allChanged()` re-meshes affected chunks.

**Critical constraint:** Runtime baking must use the current atlas via `getSpriteGetter()` — sprites are only valid after stitching and cannot be stored from a previous bake.

### Three-Level Cache Hierarchy

- **`ModelCache`** (client) — Parsed JSON + metadata per slot. `ConcurrentHashMap`. Populated by network sync or generation complete. Read by `DynamicBlockModel.bake()`.
- **`BakedModelCache`** (client) — Pre-baked quads keyed by `(slotIndex, half, open, direction)`. Written atomically via `compute()` for thread safety with Sodium chunk workers. Read by `DynamicBakedModel.getQuads()`.
- **`BlockPoolManager`** (server) — Authoritative block data with NBT persistence. Synced to clients via `BlockSyncS2C`.

Cache is cleared at the start of every resource reload — stale quads baked against the old atlas layout render incorrectly.

### Generation Pipeline

1. Player runs `/shapecraft <description>` → `GenerateRequestC2S` to server
2. `GenerationManager.submit()` checks: active generation set (one per player), pool availability, license/daily cap
3. Async generation on 2-thread pool → `BackendClient` POSTs to `{backendUrl}/shapecraft/generate` (10s connect, 30s request timeout)
4. Backend returns: `model_json`, `display_name`, `upper_model_json`, `model_json_open`, `upper_model_json_open`, `block_type`, `texture_tints`, token counts
5. Slot assignment synchronized on server thread inside `server.execute()`
6. `GenerationCompleteS2C` sent to all clients → triggers Phase 2 hot-swap

License validation is split: local checks (expired/daily cap) pre-flight; trial/monthly caps enforced by backend (402 = trial exhausted, 403 = license expired).

### Multiplayer Join Sync

On client join: `HandshakeC2S` (protocol version check) → `HandshakeResponseS2C` (pool size) → `BlockSyncS2C` (all assigned slots) → client batch-bakes via `RuntimeModelBaker.bakeAndCacheBatch()` (single batch, not per-block, to avoid chunk re-mesh overhead).

### Door Mechanics

Doors use coordinate transformation, not rotation: closed JSON coordinates are swapped (X↔Z), face keys remapped (north↔west, south↔east), rotation origins adjusted. Then standard FACING rotation applies. This keeps the hinge corner `(0,*,0)` fixed. Handled in `DynamicBlockModel.transformDoorOpen()` and mirrored in `RuntimeModelBaker.bakeOpenVariants()`.

### Texture Tinting

Backend returns `texture_tints` map: `{ "#texture_var": { "color": "#RRGGBB", "strength": 0..1 } }`. `DynamicTextureManager` loads vanilla base texture, blends per-pixel: `result = base * (1 - strength) + tint * strength`. `SpriteLoaderMixin` (`@ModifyVariable` on `SpriteLoader.stitch()`) injects generated textures into the sprite list at atlas stitching time. Only one mixin in the entire mod.

## Source Layout

```
src/main/java/com/shapecraft/          # Server + common code
├── ShapeCraft.java                    # ModInitializer entrypoint
├── ShapeCraftConstants.java           # MOD_ID, limits, URLs, PROTOCOL_VERSION
├── block/                             # PoolBlock, PoolBlockEntity, BlockPoolManager
├── command/                           # ShapeCraftCommand (Brigadier)
├── config/                            # ShapeCraftConfig, LicenseStore, DailyCapTracker, ContentFilter
├── generation/                        # BackendClient, GenerationManager, Request/Result
├── license/                           # LicenseManager, LicenseState, LicenseValidator
├── network/                           # ShapeCraftNetworking
│   └── payloads/                      # C2S: Handshake, GenerateRequest, BlockSyncRequest
│                                      # S2C: HandshakeResponse, GenerationStatus, GenerationComplete, GenerationError, BlockSync
├── persistence/                       # WorldDataManager (SavedData)
└── validation/                        # ModelValidator (11 rules), VanillaAssets, ParentResolver

src/client/java/com/shapecraft/client/ # Client-only code
├── ShapeCraftClient.java              # ClientModInitializer
├── mixin/                             # SpriteLoaderMixin (only mixin — texture atlas injection)
├── model/                             # ShapeCraftModelPlugin, DynamicBlockModel, DynamicBakedModel, ModelCache, BakedModelCache, RuntimeModelBaker
├── network/                           # ShapeCraftClientNetworking
└── render/                            # DynamicTextureManager (tinted textures)
```

Uses Fabric Loom `splitEnvironmentSourceSets()` — `src/main` cannot reference `src/client` classes. Official Mojang mappings.

## Key Technical Details

- **Block pool:** 64 pre-registered blocks (`shapecraft:custom_0` through `custom_63`) with `PoolBlockEntity` storing model data. Creative tab dynamically filters to show only assigned slots.
- **Model injection:** `ModelLoadingPlugin` + `BlockStateResolver` — no blockstates/ JSON files needed. `DynamicBlockModel` delegates to `FaceBakery.bakeQuad()` for quad building.
- **Directional placement:** `BlockModelRotation.X0_Y{0,90,180,270}` per FACING state, passed as ModelState to FaceBakery
- **Collision shapes:** Computed from model JSON elements → union of `Block.box()` via `Shapes.or()`
- **License:** 5-state FSM (UNINITIALIZED→TRIAL→ACTIVE, with GRACE/EXPIRED). Auto-provisions 50-gen trial. 10/day per-player cap, 250/month per-server. Periodic validation every ~5 min (6,000 ticks).
- **Backend URL:** Configurable in `config/shapecraft/config.json`, defaults to `https://theblockacademy.com`
- **Protocol version:** `ShapeCraftConstants.PROTOCOL_VERSION` (currently 2) — mismatch shows clear error with both versions

## Vanilla Assets Corpus

`minecraft_assets/` contains the full vanilla Minecraft asset tree (~8,400 files) used as the RAG knowledge base. Core corpus is `models/block/` (2,138 block model JSONs). `parent_models.json` is a pre-computed registry of ~50 vanilla parent models used for validation.

Block model JSON format: constrained DSL of axis-aligned boxes in a 0–16 coordinate space. Parent inheritance (`block/cube_all`, `block/slab`, etc.), elements array with `from`/`to` coordinates and per-face texture+UV, texture variables (`#name`) resolved via parent chain.

## Commit Workflow

**Before every commit and push**, always:

1. **Update `CHANGELOG.md`** — Add an entry under the new version with a summary of changes
2. **Bump version** — Update `gradle.properties` (`mod_version`), `README.md` title (`# ShapeCraft vX.Y.Z`), and `CHANGELOG.md`
3. **Version scheme** — Semver: patch for fixes, minor for features, major for breaking changes. Pre-1.0, minor bumps are fine for most additions.

## JAR Deployment for Testing

Always delete older versions of the JAR first — stale JARs cause duplicate-mod crashes or load the wrong version.

```bash
rm -f ../LocalServer/mods/shapecraft-*.jar
rm -f ../TBA/mods/shapecraft-*.jar
cp build/libs/shapecraft-X.Y.Z.jar ../LocalServer/mods/
cp build/libs/shapecraft-X.Y.Z.jar ../TBA/mods/
```
