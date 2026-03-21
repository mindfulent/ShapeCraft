# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ShapeCraft is a Minecraft mod (Fabric 1.21.1) that generates custom block models from natural language descriptions using Claude Sonnet 4.6 backed by a RAG index of ~2,100 vanilla block definitions. Status: **v0.4.6** — Phases 0–10 implemented (scaffold, networking, backend client, model injection, persistence, license, backend API routes, RAG corpus, content filter, texture tinting, multiplayer hardening). Runtime model hot-swap eliminates resource reload on generation. Parent-only models resolved via pre-computed registry. Backend routes and Discord cog live in theblockacademy and slashAI repos respectively.

Design docs: `docs/BRIEF.md` (design brief), `docs/PRD.md` (product requirements), `docs/TDD.md` (technical design)

## Architecture

### Implemented (Phases 0–10)

| Component | Key Classes |
|-----------|-------------|
| Block Pool (64 slots) | `ShapeCraft.java`, `PoolBlock`, `PoolBlockEntity`, `BlockPoolManager` |
| Networking (8 payloads) | `ShapeCraftNetworking`, `ShapeCraftClientNetworking`, `payloads/*` |
| Backend Client | `BackendClient`, `GenerationManager`, `GenerationRequest/Result` |
| Model Validator | `ModelValidator` (11 rules), `VanillaAssets`, `ParentResolver` |
| Model Injection | `ShapeCraftModelPlugin`, `DynamicBlockModel`, `DynamicBakedModel`, `ModelCache` |
| Persistence | `WorldDataManager` (SavedData), `PoolBlockEntity` NBT |
| License System | `LicenseManager`, `LicenseValidator`, `LicenseStore`, `DailyCapTracker` |
| Content Filter | `ContentFilter` (blocklist-based) |
| Texture Tinting | `DynamicTextureManager`, `SpriteLoaderMixin` (client mixin) |
| Config | `ShapeCraftConfig` |
| Commands | `ShapeCraftCommand` (`/shapecraft <desc>`, `info`, `status`, `activate`, `reload`) |
| Backend API | theblockacademy: `routes/shapecraft/*` (trial, validate, activate, generate, health) |
| RAG Corpus | theblockacademy: `scripts/prepare-shapecraft-corpus.ts`, pgvector + Voyage embeddings |
| Discord Cog | slashAI: `commands/shapecraft_commands.py` (9 owner-only commands) |

### Not Yet Implemented

- Preview GUI, block families, export
- Texture Pipeline Tier 3 (full compositing)

## Source Layout

```
src/main/java/com/shapecraft/          # Server + common code
├── ShapeCraft.java                    # ModInitializer entrypoint
├── ShapeCraftConstants.java           # MOD_ID, limits, URLs
├── block/                             # PoolBlock, PoolBlockEntity, BlockPoolManager
├── command/                           # ShapeCraftCommand (Brigadier)
├── config/                            # ShapeCraftConfig, LicenseStore, DailyCapTracker, ContentFilter
├── generation/                        # BackendClient, GenerationManager, Request/Result
├── license/                           # LicenseManager, LicenseState, LicenseValidator
├── network/                           # ShapeCraftNetworking
│   └── payloads/                      # 8 payload records (C2S + S2C)
├── persistence/                       # WorldDataManager (SavedData)
└── validation/                        # ModelValidator, VanillaAssets

src/client/java/com/shapecraft/client/ # Client-only code
├── ShapeCraftClient.java              # ClientModInitializer
├── mixin/                             # SpriteLoaderMixin (texture atlas injection)
├── model/                             # ModelLoadingPlugin, DynamicBlockModel, DynamicBakedModel, ModelCache
├── network/                           # ShapeCraftClientNetworking
└── render/                            # DynamicTextureManager (tinted textures)
```

## Vanilla Assets Corpus

`minecraft_assets/` contains the full vanilla Minecraft asset tree (~8,400 files) used as the RAG knowledge base:

- `models/block/` — 2,138 block model JSONs (the core corpus for retrieval and few-shot prompting)
- `models/item/` — Item model JSONs
- `blockstates/` — Blockstate variant definitions (maps block properties to model files)
- `textures/` — Vanilla texture PNGs (block, entity, item, etc.)
- `atlases/`, `font/`, `lang/`, `particles/`, `shaders/`, `texts/` — Supporting assets

### Block Model JSON Format

Models use a constrained DSL of axis-aligned boxes in a 0–16 coordinate space with texture UV mapping. Key patterns:
- **Parent inheritance** — most models extend a template (`block/cube_all`, `block/slab`, `block/cross`, etc.)
- **Elements** — array of boxes with `from`/`to` coordinates and per-face texture+UV definitions
- **Texture variables** — `#texture_name` references resolved via parent chain

## Build Commands

```bash
# Fabric mod (Java 21 required)
JAVA_HOME="/c/Users/slash/AppData/Roaming/PrismLauncher/java/java-runtime-delta" PATH="$JAVA_HOME/bin:$PATH" ./gradlew build
./gradlew runClient   # Test in Minecraft
./gradlew runServer   # Test dedicated server
```

## Commit Workflow

**Before every commit and push**, always:

1. **Update `CHANGELOG.md`** — Add an entry under the new version with a summary of changes
2. **Bump version** — Update the version in `README.md` title (`# ShapeCraft vX.Y.Z`) and add a new section in `CHANGELOG.md`
3. **Version scheme** — Semver: patch for fixes, minor for features, major for breaking changes. Pre-1.0, minor bumps are fine for most additions.

## JAR Deployment for Testing

When copying built JARs to `LocalServer/mods/` or the Prism Launcher TBA instance, **always delete older versions of the JAR first**. Stale JARs cause duplicate-mod crashes or load the wrong version.

```bash
# Delete old versions, then copy new
rm -f ../LocalServer/mods/shapecraft-*.jar
rm -f ../TBA/mods/shapecraft-*.jar
cp build/libs/shapecraft-X.Y.Z.jar ../LocalServer/mods/
cp build/libs/shapecraft-X.Y.Z.jar ../TBA/mods/
```

## Key Technical Details

- **Block pool:** 64 pre-registered blocks (`shapecraft:custom_0` through `custom_63`) with PoolBlockEntity storing model data
- **Model injection:** Uses Fabric's `ModelLoadingPlugin` + `BlockStateResolver` — no blockstates/ JSON files needed. `DynamicBlockModel` (UnbakedModel) delegates to `FaceBakery.bakeQuad()` for quad building
- **Directional placement:** `BlockModelRotation.X0_Y{0,90,180,270}` per FACING state, passed as ModelState to FaceBakery
- **Collision shapes:** Computed from model JSON elements → union of `Block.box()` via `Shapes.or()`
- **Resource reload:** MVP triggers full `reloadResourcePacks()` on generation complete — causes loading screen. Post-MVP should target model rebake without full reload
- **License:** 5-state FSM (UNINITIALIZED→TRIAL→ACTIVE, with GRACE/EXPIRED). Auto-provisions 50-gen trial. 10/day per-player cap, 250/month per-server
- **Backend URL:** Configurable in `config/shapecraft/config.json`, defaults to `https://theblockacademy.com`
