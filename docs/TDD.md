# ShapeCraft — Technical Design Document

**Version:** 1.0
**Last Updated:** 2026-03-20
**Status:** Draft
**Companion Docs:** [Design Brief](BRIEF.md) · [Product Requirements](PRD.md)

---

## Table of Contents

1. [System Architecture](#system-architecture)
2. [Filesystem Layout](#filesystem-layout)
3. [Build Configuration](#build-configuration)
4. [Block Pool & Registry Strategy](#block-pool--registry-strategy)
5. [Model Loading Pipeline](#model-loading-pipeline)
6. [RAG Pipeline](#rag-pipeline)
7. [LLM Integration](#llm-integration)
8. [Texture Pipeline](#texture-pipeline)
9. [Validation System](#validation-system)
10. [Networking Protocol](#networking-protocol)
11. [Multiplayer Resource Sync](#multiplayer-resource-sync)
12. [License State Machine](#license-state-machine)
13. [Backend Routes](#backend-routes)
14. [Database Schema](#database-schema)
15. [Discord Cog (slashAI)](#discord-cog-slashai)
16. [Configuration](#configuration)
17. [Command System](#command-system)
18. [Performance Targets](#performance-targets)
19. [Error Handling](#error-handling)
20. [Implementation Phases](#implementation-phases)
21. [Open Design Decisions](#open-design-decisions)

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Minecraft Client                            │
│                                                                     │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐    │
│  │ /shapecraft   │──▶│ C2S Packet   │   │ ModelLoadingPlugin   │    │
│  │ command       │   │ GenerateReq  │   │ + BlockStateResolver │    │
│  └──────────────┘   └──────┬───────┘   └──────────▲───────────┘    │
│                             │                      │                │
│  ┌──────────────┐   ┌──────┼───────┐   ┌──────────┴───────────┐    │
│  │ Preview GUI  │◀──│ S2C Packet   │   │ ShapeCraftModelCache  │    │
│  │ (US-003)     │   │ GenComplete  │   │ (runtime model store) │    │
│  └──────────────┘   └──────┼───────┘   └──────────────────────┘    │
│                             │                                       │
└─────────────────────────────┼───────────────────────────────────────┘
                              │
┌─────────────────────────────┼───────────────────────────────────────┐
│                     Minecraft Server                                │
│                             │                                       │
│  ┌──────────────┐   ┌──────▼───────┐   ┌──────────────────────┐    │
│  │ LicenseManager│◀─│ GenerationMgr │──▶│ Block Pool           │    │
│  │ (trial/paid) │   │ (queue+async)│   │ (64 pre-registered)  │    │
│  └──────────────┘   └──────┬───────┘   └──────────────────────┘    │
│                             │                                       │
│  ┌──────────────┐   ┌──────▼───────┐   ┌──────────────────────┐    │
│  │ Persistence  │◀──│ Validator    │◀──│ Backend HTTP Client   │    │
│  │ (world data) │   │ (JSON check) │   │ (→ theblockacademy)  │    │
│  └──────────────┘   └─────────────┘   └──────────┬───────────┘    │
│                                                    │                │
└────────────────────────────────────────────────────┼────────────────┘
                                                     │
┌────────────────────────────────────────────────────┼────────────────┐
│                    theblockacademy Backend                           │
│                                                                     │
│  ┌──────────────┐   ┌──────▼───────┐   ┌──────────────────────┐    │
│  │ /shapecraft/* │──▶│ RAG Engine   │──▶│ Claude Sonnet 4.6    │    │
│  │ Express routes│   │ (embedding   │   │ (model JSON gen)     │    │
│  └──────────────┘   │  + retrieval) │   └──────────────────────┘    │
│                     └──────────────┘                                │
│  ┌──────────────┐   ┌──────────────┐                                │
│  │ License DB   │   │ Usage Tracking│                               │
│  │ (PostgreSQL) │   │ (per-server)  │                               │
│  └──────────────┘   └──────────────┘                                │
└─────────────────────────────────────────────────────────────────────┘
```

**Three-tier split:**

| Tier | Runs On | Responsibility |
|------|---------|----------------|
| Client mod | Player's machine | Command input, preview GUI, model injection via `ModelLoadingPlugin`, texture atlas stitching |
| Server mod | Dedicated server | Generation orchestration, block pool management, persistence, license enforcement, content filter |
| Backend | theblockacademy API | RAG retrieval, LLM generation, license validation, usage tracking |

The server mod is the source of truth. It receives generation requests, validates license/caps, calls the backend, validates the response, assigns a block pool slot, persists the result, and broadcasts model data to all connected clients. Clients never call the backend directly.

---

## Filesystem Layout

```
ShapeCraft/
├── build.gradle                    # Fabric Loom build (single version)
├── gradle.properties               # MC/Fabric/mod versions
├── settings.gradle                 # Project name
├── src/
│   ├── main/                       # Server + common code
│   │   ├── java/com/shapecraft/
│   │   │   ├── ShapeCraft.java              # ModInitializer entrypoint
│   │   │   ├── ShapeCraftConstants.java     # Mod ID, protocol version, limits
│   │   │   ├── block/
│   │   │   │   ├── PoolBlock.java           # Custom block class (pool member)
│   │   │   │   ├── PoolBlockEntity.java     # Stores generated model data per-instance
│   │   │   │   └── BlockPoolManager.java    # Manages pool allocation and persistence
│   │   │   ├── command/
│   │   │   │   └── ShapeCraftCommand.java   # /shapecraft command tree
│   │   │   ├── config/
│   │   │   │   └── ShapeCraftConfig.java    # Server config (GSON)
│   │   │   ├── generation/
│   │   │   │   ├── GenerationManager.java   # Async generation orchestration
│   │   │   │   ├── GenerationRequest.java   # Request record
│   │   │   │   ├── GenerationResult.java    # Result record (model + textures)
│   │   │   │   └── BackendClient.java       # HTTP client to theblockacademy
│   │   │   ├── license/
│   │   │   │   ├── LicenseManager.java      # License state machine
│   │   │   │   └── LicenseState.java        # Enum: TRIAL, ACTIVE, GRACE, EXPIRED
│   │   │   ├── network/
│   │   │   │   ├── ShapeCraftNetworking.java    # Payload registration + server receivers
│   │   │   │   └── payloads/                    # C2S and S2C payload records
│   │   │   │       ├── HandshakeC2S.java
│   │   │   │       ├── HandshakeResponseS2C.java
│   │   │   │       ├── GenerateRequestC2S.java
│   │   │   │       ├── GenerationStatusS2C.java
│   │   │   │       ├── GenerationCompleteS2C.java
│   │   │   │       ├── GenerationErrorS2C.java
│   │   │   │       ├── BlockSyncS2C.java
│   │   │   │       └── BlockSyncRequestC2S.java
│   │   │   ├── persistence/
│   │   │   │   └── WorldDataManager.java    # Save/load from world NBT
│   │   │   └── validation/
│   │   │       └── ModelValidator.java      # Validates generated JSON
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       ├── shapecraft.mixins.json       # Mixin config (if needed)
│   │       └── data/shapecraft/             # Data-driven content (recipes, tags)
│   │
│   └── client/                     # Client-only code
│       ├── java/com/shapecraft/client/
│       │   ├── ShapeCraftClient.java        # ClientModInitializer entrypoint
│       │   ├── model/
│       │   │   ├── ShapeCraftModelPlugin.java    # ModelLoadingPlugin registration
│       │   │   ├── DynamicBlockModel.java        # UnbakedModel for generated blocks
│       │   │   ├── DynamicBakedModel.java        # BakedModel with custom quads
│       │   │   └── ModelCache.java               # Client-side model data cache
│       │   ├── network/
│       │   │   └── ShapeCraftClientNetworking.java  # Client receivers
│       │   ├── render/
│       │   │   └── DynamicTextureManager.java   # NativeImage texture generation
│       │   └── screen/
│       │       └── PreviewScreen.java           # Block preview GUI (US-003)
│       └── resources/
│           └── assets/shapecraft/
│               ├── lang/en_us.json
│               └── textures/                    # Mod UI textures
│
├── docs/
│   ├── BRIEF.md                    # Design brief
│   ├── PRD.md                      # Product requirements
│   └── TDD.md                      # This document
├── minecraft_assets/               # Vanilla asset corpus (RAG source, not shipped)
│   ├── models/block/               # 2,138 block model JSONs
│   ├── blockstates/                # 1,062 blockstate definitions
│   └── textures/                   # Vanilla texture PNGs
├── CLAUDE.md
├── CHANGELOG.md
└── README.md
```

**Key principle:** `src/main/` contains server + common code (safe for dedicated servers). `src/client/` contains client-only code (rendering, UI, model loading). The `splitEnvironmentSourceSets()` Loom feature enforces this boundary at compile time — server code cannot reference client classes.

---

## Build Configuration

### gradle.properties

```properties
# Mod
mod_version=0.2.0
maven_group=com.shapecraft
archives_base_name=shapecraft

# Fabric
minecraft_version=1.21.1
loader_version=0.16.9
fabric_version=0.116.7+1.21.1

# Java
java_version=21
```

### build.gradle

```gradle
plugins {
    id "fabric-loom" version "1.9-SNAPSHOT"
}

version = project.mod_version
group = project.maven_group

repositories {
    mavenCentral()
    maven { url "https://maven.fabricmc.net/" }
}

loom {
    splitEnvironmentSourceSets()
    mods {
        shapecraft {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 21
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
```

**Mappings:** Mojang official mappings (`loom.officialMojangMappings()`), not Yarn. Mojang mappings are stable and future-proof — Yarn will not be updated past 1.21.11. All class/method names in this document use Mojang names.

**Java 21:** Required for Minecraft 1.21.1. Local dev uses Prism Launcher's bundled JDK:
```bash
JAVA_HOME="/c/Users/slash/AppData/Roaming/PrismLauncher/java/java-runtime-delta" \
PATH="$JAVA_HOME/bin:$PATH" ./gradlew build
```

---

## Block Pool & Registry Strategy

Minecraft freezes the block registry at startup. Blocks cannot be registered at runtime. ShapeCraft works around this by **pre-registering a pool of blank blocks** during mod initialization, then assigning visual identities dynamically.

### Pool Design

```java
// During ModInitializer.onInitialize():
for (int i = 0; i < config.poolSize; i++) {
    ResourceLocation id = ResourceLocation.fromNamespaceAndPath("shapecraft", "custom_" + i);
    Block block = new PoolBlock(BlockBehaviour.Properties.of()
        .strength(1.5f)              // Stone-like hardness
        .requiresCorrectToolForDrops()
        .noOcclusion());             // Custom shapes may not fill full cube
    Registry.register(BuiltInRegistries.BLOCK, id, block);
    Registry.register(BuiltInRegistries.ITEM, id,
        new BlockItem(block, new Item.Properties()));
}
```

| Property | Value | Rationale |
|----------|-------|-----------|
| Pool size | 64 (configurable) | PRD spec. Covers casual use without bloating the registry. |
| Block class | Single `PoolBlock extends Block` | All pool members share the same Java class. Visual identity comes from model data, not code. |
| BlockEntity | `PoolBlockEntity` | Stores the slot's generation metadata: description, model JSON hash, texture references. Used by `DynamicBakedModel` for rendering. |
| Default state | Invisible (missing model) | Unassigned slots have no model — they render as the purple/black missing texture. This is intentional: unassigned slots are never exposed to players. |
| `noOcclusion()` | Always | Generated models may be non-full-cube. Without this, adjacent faces get incorrectly culled. |

### Slot Lifecycle

```
UNASSIGNED ──(generation completes)──▶ ASSIGNED ──(pool full, recycle)──▶ UNASSIGNED
                                           │
                                    (world reload)
                                           │
                                      ASSIGNED (restored from NBT)
```

- **UNASSIGNED:** Slot has no model. Not visible to players. Not in creative tab.
- **ASSIGNED:** Slot has a generated model, display name, and texture data. Appears in creative tab. Placeable.
- **Recycling:** When the pool is full, the server can optionally reclaim the least-recently-used slot (not MVP — MVP just rejects generation when full).

### Collision Shape

`PoolBlock` overrides `getShape()` to return a shape computed from the generated model's element bounding boxes:

```java
@Override
public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
    BlockEntity be = level.getBlockEntity(pos);
    if (be instanceof PoolBlockEntity pbe && pbe.hasShape()) {
        return pbe.getCachedShape();
    }
    return Shapes.block(); // fallback: full cube
}
```

The shape is computed once from the model's `elements` array (union of all element boxes) and cached in the `BlockEntity`.

### Directional Placement (US-002)

`PoolBlock` implements `HorizontalDirectionalBlock` to get the `FACING` property. Blockstate rotation is handled by the `BlockStateResolver` returning rotated model variants for each facing direction:

```java
// In ShapeCraftModelPlugin (client-side ModelLoadingPlugin):
pluginContext.registerBlockStateResolver(poolBlock, context -> {
    ModelData modelData = modelCache.get(slotIndex);
    if (modelData == null) {
        // Unassigned slot — use missing model
        for (BlockState state : poolBlock.getStateDefinition().getPossibleStates()) {
            context.setModel(state, missingModel);
        }
        return;
    }
    UnbakedModel baseModel = new DynamicBlockModel(modelData);
    for (BlockState state : poolBlock.getStateDefinition().getPossibleStates()) {
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        int yRotation = switch (facing) {
            case NORTH -> 0;
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
        context.setModel(state, new RotatedModel(baseModel, yRotation));
    }
});
```

---

## Model Loading Pipeline

ShapeCraft uses Fabric's **Model Loading API v1** to inject generated block models without a resource reload. This is a client-side system.

### Pipeline Overview

```
Server generates model JSON
         │
    ┌────▼────┐
    │ S2C net  │  GenerationCompleteS2C payload (model JSON + texture data)
    └────┬────┘
         │
    ┌────▼──────────────┐
    │ ModelCache.put()   │  Stores deserialized model data by slot index
    └────┬──────────────┘
         │
    ┌────▼──────────────────┐
    │ Trigger resource       │  MinecraftClient.getInstance().reloadResources()
    │ reload                 │  or targeted model rebake (preferred, see below)
    └────┬──────────────────┘
         │
    ┌────▼──────────────────┐
    │ ModelLoadingPlugin     │  ShapeCraftModelPlugin intercepts model loading
    │ → BlockStateResolver  │  Returns DynamicBlockModel for assigned pool slots
    └────┬──────────────────┘
         │
    ┌────▼──────────────────┐
    │ DynamicBlockModel      │  Implements UnbakedModel
    │ .bake()               │  Produces DynamicBakedModel
    └────┬──────────────────┘
         │
    ┌────▼──────────────────┐
    │ DynamicBakedModel      │  Implements BakedModel + FabricBakedModel
    │ .emitBlockQuads()     │  Emits geometry from parsed element data
    └────┬──────────────────┘
         │
    ┌────▼──────────────────┐
    │ Rendered in-world     │
    └───────────────────────┘
```

### Key Classes

**`ShapeCraftModelPlugin`** — Registered via `ModelLoadingPlugin.register()` in `ClientModInitializer`. Registers a `BlockStateResolver` for each pool block. The resolver reads from `ModelCache` and returns either a `DynamicBlockModel` (assigned slot) or a missing-texture model (unassigned slot).

**`DynamicBlockModel`** — Implements `UnbakedModel`. Wraps the parsed model JSON (elements, textures, parent). The `bake()` method:
1. Resolves texture variables through parent chain
2. Looks up `TextureAtlasSprite` for each resolved texture path
3. Builds `BakedQuad` list from elements (one quad per face per element)
4. Returns a `DynamicBakedModel`

**`DynamicBakedModel`** — Implements `BakedModel` and `FabricBakedModel`. Stores pre-baked quads. `emitBlockQuads()` emits them via the Fabric Renderer API. For vanilla-texture blocks, `isVanillaAdapter()` returns `true` (faster rendering path). For tinted/generated textures, returns `false` and uses `MeshBuilder`/`QuadEmitter`.

### Avoiding Full Resource Reloads

`MinecraftClient.reloadResources()` shows a loading screen — unacceptable during gameplay. Strategies to minimize this:

1. **Batch reloads:** Collect multiple generations and reload once. During the reload, show a subtle HUD indicator instead of the loading screen.
2. **Targeted model rebake:** If Fabric exposes an API to rebake a specific block model without full reload, use it. As of 1.21.1, this is not officially supported, so we may need a mixin into `ModelManager` to rebake a single block.
3. **Pre-bake during generation:** Parse and bake the model into quads on a background thread while the reload screen shows. The reload then just swaps the pre-baked result in.

**MVP approach:** Use `reloadResources()` after each generation. Accept the brief loading screen. Optimize in a later phase.

### Texture Atlas Integration

Generated block textures must be stitched into Minecraft's block texture atlas to render correctly with block models. Two approaches:

**Approach A — Vanilla texture references (MVP):**
Generated models reference existing vanilla textures by path (e.g., `minecraft:block/oak_planks`). No atlas modification needed. The model just resolves sprites from the existing atlas.

**Approach B — Custom generated textures (post-MVP):**
For tinted/composited textures, create new atlas entries:

```java
// In ClientModInitializer, register sprite source:
// Use SpriteAtlasManager or TextureStitchEvent equivalent
// to add shapecraft:block/generated_* sprites to the block atlas
```

This requires hooking into the atlas stitching process — either via Fabric's `ClientSpriteRegistryCallback` (if still available in 1.21.1) or a mixin into `SpriteLoader`. The generated `NativeImage` pixels are provided to the atlas stitcher, which produces a `TextureAtlasSprite` usable in model baking.

---

## RAG Pipeline

The RAG system runs server-side in the theblockacademy backend. The mod server sends the player's description; the backend handles retrieval and LLM prompting.

### Corpus Preparation (Offline, One-Time)

The `minecraft_assets/models/block/` directory contains 2,138 vanilla block model JSONs. These are processed offline into a searchable index:

```
Raw model JSON                  Enriched entry
┌───────────────────┐           ┌─────────────────────────────────────┐
│ {                 │           │ {                                   │
│   "parent": "...",│  ──────▶  │   "id": "lantern",                 │
│   "elements": [...│           │   "description": "A hanging lantern│
│   "textures": {.. │           │     with chain attachment and       │
│ }                 │           │     glowing center panel",          │
│                   │           │   "parent": "template_lantern",     │
│                   │           │   "element_count": 5,               │
│                   │           │   "bounding_box": [5,0,5,11,16,11], │
│                   │           │   "shape_category": "hanging",      │
│                   │           │   "texture_refs": ["lantern"],      │
│                   │           │   "raw_json": "{ ... }",            │
│                   │           │   "embedding": [0.12, -0.34, ...]   │
│                   │           │ }                                   │
└───────────────────┘           └─────────────────────────────────────┘
```

**Enrichment fields:**

| Field | Source | Purpose |
|-------|--------|---------|
| `description` | LLM-generated (one-time batch) | Human-readable description for semantic search |
| `parent` | Parsed from JSON | Filter by template family |
| `element_count` | Parsed from JSON | Complexity metric for retrieval ranking |
| `bounding_box` | Computed from elements | Shape similarity matching |
| `shape_category` | Classified from structure | High-level shape bucket (cube, slab, cross, hanging, fence, etc.) |
| `texture_refs` | Parsed from JSON | Texture variable names used |
| `raw_json` | Original file | Few-shot example for LLM |
| `embedding` | Voyage embedding of description | Semantic similarity search |

### Retrieval Strategy

Given a player description like "a weathered copper lantern with chain attachment":

1. **Embed** the description using Voyage API (same provider as slashAI memory)
2. **Semantic search** — find top-10 most similar block descriptions by cosine similarity
3. **Structural filter** — if the description implies a shape category (e.g., "lantern" → hanging, "slab" → slab), boost entries from that category
4. **Select top-3** as few-shot examples for the LLM prompt

### Storage

The enriched index is stored in PostgreSQL (same `tba-db` used by other projects) with pgvector for embedding search:

```sql
CREATE TABLE shapecraft_block_corpus (
    id              SERIAL PRIMARY KEY,
    block_id        VARCHAR(128) UNIQUE NOT NULL,  -- e.g., "lantern"
    description     TEXT NOT NULL,
    parent          VARCHAR(128),
    element_count   INTEGER,
    bounding_box    JSONB,
    shape_category  VARCHAR(32),
    texture_refs    TEXT[],
    raw_json        TEXT NOT NULL,
    embedding       vector(1024)                   -- Voyage embedding dimension
);

CREATE INDEX idx_corpus_embedding ON shapecraft_block_corpus
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 50);
CREATE INDEX idx_corpus_category ON shapecraft_block_corpus(shape_category);
```

---

## LLM Integration

### Model Choice

Claude Sonnet 4.6 via Anthropic API. Rationale from PRD: ~$0.03/generation worst case, $0.02 with prompt caching. The output is structured JSON — a strength of Claude models.

### Prompt Structure

```
┌─────────────────────────────────────────────────────────────┐
│ SYSTEM PROMPT (cached across requests)                      │
│                                                             │
│ You are a Minecraft block model generator. You output valid │
│ block model JSON following the Minecraft model format.      │
│                                                             │
│ CONSTRAINTS:                                                │
│ - All coordinates in 0-16 range                             │
│ - Maximum 16 elements                                       │
│ - Must use valid parent if inheriting                       │
│ - Texture references must use #variable syntax              │
│ - Face UV values in 0-16 range                              │
│ - All faces must specify texture and cullface (if on edge)  │
│                                                             │
│ FORMAT REFERENCE:                                           │
│ [abbreviated block model format spec]                       │
│                                                             │
│ AVAILABLE VANILLA TEXTURES:                                 │
│ [list of ~150 commonly-used texture paths]                  │
│                                                             │
│ AVAILABLE PARENT MODELS:                                    │
│ [list of ~30 template parents with descriptions]            │
├─────────────────────────────────────────────────────────────┤
│ USER PROMPT (per-request)                                   │
│                                                             │
│ Generate a block model for: "{player description}"          │
│                                                             │
│ Here are similar existing blocks for reference:             │
│                                                             │
│ Example 1: {description}                                    │
│ ```json                                                     │
│ {raw_json}                                                  │
│ ```                                                         │
│                                                             │
│ Example 2: ...                                              │
│ Example 3: ...                                              │
│                                                             │
│ Generate ONLY the model JSON. Choose textures from the      │
│ vanilla set that best match the description.                │
├─────────────────────────────────────────────────────────────┤
│ RESPONSE FORMAT                                             │
│                                                             │
│ {                                                           │
│   "model": { ... block model JSON ... },                    │
│   "display_name": "Weathered Copper Lantern",               │
│   "texture_tints": {                                        │
│     "#lantern": {                                           │
│       "base": "minecraft:block/copper_block",               │
│       "tint_color": "#6B8C6B",                              │
│       "tint_strength": 0.3                                  │
│     }                                                       │
│   }                                                         │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘
```

### Token Budget

| Component | Tokens | Notes |
|-----------|--------|-------|
| System prompt (cached) | ~2,500 | Format spec + texture list + parent list |
| RAG examples (3) | ~1,200 | ~400 tokens per example |
| User description | ~50 | 200 char cap → ~50 tokens |
| **Total input** | **~3,750** | |
| Model JSON output | ~600 | Typical model with 4-8 elements |
| Metadata output | ~200 | Display name + texture tints |
| **Total output** | **~800** | |

**Prompt caching:** The system prompt is identical across requests. With Anthropic's prompt caching, the ~2,500 token system prompt is cached after the first request, reducing input cost by ~90% for that portion. Effective per-generation cost drops from ~$0.03 to ~$0.02.

---

## Texture Pipeline

Three tiers, implemented incrementally:

### Tier 1 — Vanilla Reference (MVP)

The LLM selects existing vanilla texture paths. The model JSON references them directly:
```json
{ "textures": { "all": "minecraft:block/copper_block" } }
```
No texture generation. No atlas modification. Works immediately with the existing texture atlas.

### Tier 2 — Tint and Composite

The LLM specifies tinting/blending operations in `texture_tints`. The client generates new textures using `NativeImage`:

```java
// Tinting: load base texture, apply color overlay
NativeImage base = loadVanillaTexture("minecraft:block/copper_block");
NativeImage tinted = new NativeImage(16, 16, false);
int tintColor = 0xFF6B8C6B; // ARGB
float strength = 0.3f;

for (int x = 0; x < 16; x++) {
    for (int y = 0; y < 16; y++) {
        int original = base.getPixelRGBA(x, y);  // ABGR format
        int blended = blendColors(original, tintColor, strength);
        tinted.setPixelRGBA(x, y, blended);
    }
}
```

The generated `NativeImage` is stitched into the block atlas as `shapecraft:block/generated_<hash>`. This requires hooking the atlas stitching process (see Model Loading Pipeline, Approach B).

### Tier 3 — Image Model Generation (Future)

Not in scope for initial release. Would use an image generation API to produce novel 16x16 textures from text descriptions. Requires careful style matching to avoid clashing with vanilla aesthetics.

### Texture Namespace

All ShapeCraft-generated textures live under `shapecraft:block/generated/`:
```
assets/shapecraft/textures/block/generated/
    a1b2c3d4.png    # Hash of tint params → deterministic filename
    e5f6g7h8.png
```

Deterministic filenames (hash of generation params) ensure identical tinting operations produce the same texture — no duplicates.

---

## Validation System

Every LLM-generated model JSON passes through validation before injection. Invalid output is **rejected** (not auto-corrected in MVP — auto-correction introduces subtle bugs that are harder to debug than retry).

### Validation Rules

| Rule | Check | Error |
|------|-------|-------|
| V-001 | JSON parses without syntax errors | "Invalid JSON syntax" |
| V-002 | Top-level keys are a subset of: `parent`, `elements`, `textures`, `display`, `ambientocclusion` | "Unknown key: {key}" |
| V-003 | If `parent` is specified, it resolves to a known vanilla model or a previously generated ShapeCraft model | "Unknown parent: {parent}" |
| V-004 | Element count ≤ 16 | "Too many elements: {count} (max 16)" |
| V-005 | All element `from`/`to` coordinates are in [0, 16] range | "Coordinate out of range: {value}" |
| V-006 | For each element, `from[i] < to[i]` for all axes | "Degenerate element: from >= to on axis {axis}" |
| V-007 | Each face references a texture variable that exists in the `textures` map or is inherited from parent | "Unresolved texture: {ref}" |
| V-008 | Face UV values (if specified) are in [0, 16] range | "UV out of range: {value}" |
| V-009 | Rotation angles are one of: 0, 22.5, 45 (and only around one axis per element) | "Invalid rotation angle: {value}" |
| V-010 | All texture paths resolve to existing vanilla textures or ShapeCraft-generated textures | "Texture not found: {path}" |
| V-011 | No duplicate face definitions per element | "Duplicate face: {face} in element {i}" |

### Retry Logic

If validation fails, the server retries the LLM call once with the validation errors appended to the prompt:

```
Your previous output had these errors:
- V-005: Coordinate out of range: from[1] = -2 (must be 0-16)
- V-007: Unresolved texture: #side_overlay (not in textures map)

Please fix these issues and regenerate.
```

Maximum 1 retry. If the retry also fails, the player gets an error message and the generation does not count against their daily cap (but does count against the monthly budget, since the API cost was incurred).

---

## Networking Protocol

All payloads use Fabric's `PayloadTypeRegistry` with `StreamCodec`-based serialization. Protocol version is checked on handshake; mismatches produce a clear error message.

### Protocol Version

`ShapeCraftConstants.PROTOCOL_VERSION = 1`

Incremented on any breaking payload change. Client and server must match.

### Payload Definitions

#### Handshake (on player join)

**`HandshakeC2S`** — Client → Server

| Field | Type | Validation |
|-------|------|------------|
| `protocolVersion` | `int` | Must match server |
| `modVersion` | `String` | Informational |

**`HandshakeResponseS2C`** — Server → Client

| Field | Type | Notes |
|-------|------|-------|
| `success` | `boolean` | `false` if version mismatch |
| `protocolVersion` | `int` | Server's version |
| `message` | `String` | Error message if failed |
| `poolSize` | `int` | Number of pool slots (needed for client model setup) |

#### Generation

**`GenerateRequestC2S`** — Client → Server

| Field | Type | Validation |
|-------|------|------------|
| `description` | `String` | Max 200 chars, non-empty, content-filtered server-side |

**`GenerationStatusS2C`** — Server → Client (progress updates)

| Field | Type | Notes |
|-------|------|-------|
| `status` | `enum` | `QUEUED`, `GENERATING`, `VALIDATING`, `INJECTING`, `COMPLETE`, `FAILED` |
| `message` | `String` | Human-readable status text |

**`GenerationCompleteS2C`** — Server → Client (broadcast to ALL clients)

| Field | Type | Notes |
|-------|------|-------|
| `slotIndex` | `int` | Pool block index (0–63) |
| `displayName` | `String` | Block display name |
| `modelJson` | `String` | Complete block model JSON |
| `textureTints` | `String` | JSON object of tint operations (may be empty) |
| `generatedTextures` | `Map<String, byte[]>` | Generated texture PNGs keyed by path (may be empty for Tier 1) |

**`GenerationErrorS2C`** — Server → Client (to requesting player only)

| Field | Type | Notes |
|-------|------|-------|
| `message` | `String` | Player-facing error message |

#### Sync (on player join, after handshake)

**`BlockSyncRequestC2S`** — Client → Server

| Field | Type | Notes |
|-------|------|-------|
| (empty payload) | | Client requests all current block assignments |

**`BlockSyncS2C`** — Server → Client (bulk sync)

| Field | Type | Notes |
|-------|------|-------|
| `blocks` | `List<BlockSyncEntry>` | All assigned slots: index, displayName, modelJson, textureTints, generatedTextures |

This is sent once on join. For large pools, it may need to be split into multiple packets (max packet size ~2 MiB).

### Payload Registration

```java
public class ShapeCraftNetworking {
    public static void registerPayloads() {
        // C2S
        PayloadTypeRegistry.playC2S().register(HandshakeC2S.TYPE, HandshakeC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(GenerateRequestC2S.TYPE, GenerateRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(BlockSyncRequestC2S.TYPE, BlockSyncRequestC2S.CODEC);

        // S2C
        PayloadTypeRegistry.playS2C().register(HandshakeResponseS2C.TYPE, HandshakeResponseS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GenerationStatusS2C.TYPE, GenerationStatusS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GenerationCompleteS2C.TYPE, GenerationCompleteS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GenerationErrorS2C.TYPE, GenerationErrorS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BlockSyncS2C.TYPE, BlockSyncS2C.CODEC);
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(HandshakeC2S.TYPE, (payload, context) -> {
            // Validate protocol version, respond with HandshakeResponseS2C
        });
        ServerPlayNetworking.registerGlobalReceiver(GenerateRequestC2S.TYPE, (payload, context) -> {
            // Content filter → license check → daily cap check → enqueue generation
        });
        ServerPlayNetworking.registerGlobalReceiver(BlockSyncRequestC2S.TYPE, (payload, context) -> {
            // Send all assigned block data via BlockSyncS2C
        });
    }
}
```

**Critical:** Payload handlers run on the **network thread**. Game state access must be wrapped in `context.player().getServer().execute(() -> { ... })` to run on the server thread.

---

## Multiplayer Resource Sync

Two mechanisms ensure all clients have matching block models:

### 1. Packet-Based Sync (Primary)

When a generation completes, the server broadcasts `GenerationCompleteS2C` to **all** connected clients. Each client:
1. Stores the model data in `ModelCache`
2. Triggers a resource reload to rebake the affected block model
3. The `BlockStateResolver` picks up the new data from `ModelCache`

When a new client joins, it sends `BlockSyncRequestC2S` and receives a `BlockSyncS2C` with all assigned blocks. This ensures late-joining players see all generated blocks.

### 2. Server Resource Pack (Fallback for non-modded clients)

For players who have the mod installed, packet sync is sufficient. For players **without** the mod (US-032: "If the server has ShapeCraft but I don't, I can still join"), generated blocks appear as their fallback placeholder texture.

If a server owner wants non-modded clients to see generated blocks, ShapeCraft can optionally generate a server resource pack:

1. Server maintains a `.zip` resource pack in `config/shapecraft/resourcepack/`
2. Each generation adds the model JSON + textures to the pack and regenerates the zip
3. The server's `server.properties` `resource-pack` field points to a hosted URL (external hosting — the mod does not embed an HTTP server)
4. The SHA-1 hash in `resource-pack-sha1` is updated programmatically

This is **not MVP**. MVP requires the mod on both sides.

---

## License State Machine

Follows the StreamCraft/SynthCraft pattern. Five states:

```
                    ┌──────────────┐
          ┌────────▶│ UNINITIALIZED │
          │         └──────┬───────┘
          │                │ (first boot → auto-provision trial via backend)
          │         ┌──────▼───────┐
          │         │    TRIAL     │ 50 generations, no signup
          │         └──────┬───────┘
          │                │ (/shapecraft activate SHAPE-XXXX-XXXX)
          │         ┌──────▼───────┐
          │         │    ACTIVE    │ 250 generations/month, $8/mo
          │         └──────┬───────┘
          │                │ (backend unreachable > 7 days)
          │         ┌──────▼───────┐
          │         │    GRACE     │ 7-day offline grace period
          │         └──────┬───────┘
          │                │ (grace period expires)
          │         ┌──────▼───────┐
          └─────────│   EXPIRED    │ generation disabled
                    └──────────────┘
```

### Persistence

License state saved to `config/shapecraft/license.json`:

```json
{
    "serverId": "sha256(ip:port:instanceUuid)",
    "instanceUuid": "random-uuid-generated-on-first-boot",
    "licenseKey": null,
    "state": "TRIAL",
    "trialRemaining": 50,
    "lastValidated": "2026-03-20T10:30:00Z",
    "expiresAt": null,
    "monthlyUsed": 0,
    "monthlyResetDate": null
}
```

### Validation Schedule

- **On boot:** Validate with backend. If unreachable, use cached state.
- **Every 24 hours:** Re-validate. Updates monthly budget, checks subscription status.
- **On generation request:** Quick local check (state + caps). No network call.

---

## Backend Routes

Hosted in the theblockacademy Express backend under `/shapecraft/*`. Follows the same pattern as SynthCraft routes.

### POST `/shapecraft/generate`

```
Request:
{
    "serverId": "sha256-hash",
    "licenseKey": "shape_xxxx" | null,
    "playerUuid": "uuid",
    "playerName": "string",
    "description": "weathered copper lantern",
    "promptHash": "sha256-of-description"  // For dedup
}

Response (200):
{
    "generationId": "uuid",
    "model": { ... block model JSON ... },
    "displayName": "Weathered Copper Lantern",
    "textureTints": { ... },
    "tokensUsed": { "input": 3800, "output": 750 }
}

Response (402):
{ "error": "trial_exhausted", "message": "Trial generations used. Subscribe at ..." }

Response (429):
{ "error": "daily_cap", "message": "Daily generation limit reached. Try again tomorrow." }
```

### POST `/shapecraft/trial`

Auto-provisions a trial license for a new server.

```
Request:
{ "serverId": "sha256-hash", "instanceUuid": "uuid" }

Response (200):
{ "trialCredit": 50, "dailyCap": 10 }

Response (409):
{ "error": "trial_exists", "message": "Trial already provisioned for this server." }
```

### POST `/shapecraft/activate`

Binds a Patreon activation code to a server.

```
Request:
{ "serverId": "sha256-hash", "activationCode": "SHAPE-XXXX-XXXX" }

Response (200):
{ "state": "ACTIVE", "monthlyBudget": 250, "expiresAt": "2026-04-20T..." }
```

### POST `/shapecraft/validate`

Periodic license validation (every 24h).

```
Request:
{ "serverId": "sha256-hash", "licenseKey": "shape_xxxx" | null }

Response (200):
{
    "state": "ACTIVE",
    "monthlyRemaining": 183,
    "monthlyResetDate": "2026-04-01T00:00:00Z",
    "trialRemaining": null
}
```

---

## Database Schema

All tables in the shared `tba-db` PostgreSQL database, prefixed with `shapecraft_`.

```sql
-- License tracking
CREATE TABLE shapecraft_licenses (
    id              SERIAL PRIMARY KEY,
    server_id       VARCHAR(64) UNIQUE NOT NULL,
    instance_uuid   UUID NOT NULL,
    license_key     VARCHAR(64),
    state           VARCHAR(20) NOT NULL DEFAULT 'TRIAL',
    trial_remaining INTEGER DEFAULT 50,
    monthly_used    INTEGER DEFAULT 0,
    monthly_reset   TIMESTAMPTZ,
    activated_at    TIMESTAMPTZ,
    last_validated  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,
    hidden          BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Generation history (for usage tracking and analytics)
CREATE TABLE shapecraft_generations (
    id              SERIAL PRIMARY KEY,
    generation_id   UUID UNIQUE NOT NULL,
    server_id       VARCHAR(64) NOT NULL REFERENCES shapecraft_licenses(server_id),
    player_uuid     VARCHAR(36) NOT NULL,
    player_name     VARCHAR(64),
    description     TEXT NOT NULL,
    model_json      JSONB,
    display_name    VARCHAR(128),
    texture_tints   JSONB,
    status          VARCHAR(20) NOT NULL,  -- pending, completed, failed, retried
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    cost_usd        NUMERIC(6,4),
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_gen_server ON shapecraft_generations(server_id);
CREATE INDEX idx_gen_player ON shapecraft_generations(player_uuid);
CREATE INDEX idx_gen_created ON shapecraft_generations(created_at);

-- RAG corpus (see RAG Pipeline section)
CREATE TABLE shapecraft_block_corpus (
    id              SERIAL PRIMARY KEY,
    block_id        VARCHAR(128) UNIQUE NOT NULL,
    description     TEXT NOT NULL,
    parent          VARCHAR(128),
    element_count   INTEGER,
    bounding_box    JSONB,
    shape_category  VARCHAR(32),
    texture_refs    TEXT[],
    raw_json        TEXT NOT NULL,
    embedding       vector(1024)
);

CREATE INDEX idx_corpus_embedding ON shapecraft_block_corpus
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 50);
CREATE INDEX idx_corpus_category ON shapecraft_block_corpus(shape_category);
```

---

## Discord Cog (slashAI)

A `ShapeCraftCommands` cog in slashAI for owner monitoring, following the StreamCraft/SynthCraft pattern. See PRD US-060 through US-063 for full requirements.

### Commands

| Command | Parameters | Response |
|---------|------------|----------|
| `/shapecraft licenses` | `state:` (optional filter) | List all licenses with state, remaining, last validated |
| `/shapecraft stats` | | Aggregate: total generations, monthly count, active servers, API cost, conversion rate |
| `/shapecraft servers` | | Per-server breakdown: name, generations, unique players, last active |
| `/shapecraft player` | `name:` | Per-player stats: blocks generated, recent prompts, last server |
| `/shapecraft active` | | Servers with activity in the last hour |
| `/shapecraft set-state` | `server_id:`, `state:` | Change license state (with confirmation) |
| `/shapecraft hide`/`unhide` | `server_id:` | Toggle visibility in listings |

All commands are owner-only and return ephemeral responses.

---

## Configuration

Server-side config at `config/shapecraft/config.json`, loaded on startup and reloadable via `/shapecraft reload`.

```json
{
    "poolSize": 64,
    "maxGenerationsPerPlayerPerDay": 10,
    "maxPromptLength": 200,
    "generationPermission": "everyone",
    "contentFilter": {
        "enabled": true,
        "blockedWords": ["offensive", "term", "list"]
    },
    "backendUrl": "https://api.theblockacademy.com",
    "debug": false
}
```

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `poolSize` | int | 64 | Number of pre-registered block slots. **Cannot be changed after first boot** (changing it would orphan placed blocks). |
| `maxGenerationsPerPlayerPerDay` | int | 10 | Per-player daily cap. 0 = unlimited. |
| `maxPromptLength` | int | 200 | Maximum description length in characters. |
| `generationPermission` | enum | `"everyone"` | `"everyone"`, `"ops"`, or `"allowlist"` |
| `contentFilter.enabled` | bool | true | Toggle server-side word filter |
| `contentFilter.blockedWords` | string[] | (default list) | Customizable blocklist |
| `backendUrl` | string | (production URL) | Backend API URL |
| `debug` | bool | false | Verbose logging |

---

## Command System

All commands under `/shapecraft`, registered via Brigadier.

### Player Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/shapecraft <description>` | generation permission | Generate a block from description |
| `/shapecraft family <description>` | generation permission | Generate block + slab + stairs + wall (US-004) |
| `/shapecraft info` | everyone | Show remaining generations and pool usage |
| `/shapecraft export` | everyone | Export all generated blocks as resource pack zip |

### Admin Commands (op-only)

| Command | Description |
|---------|-------------|
| `/shapecraft status` | License state, generations remaining, pool usage, active players |
| `/shapecraft activate <code>` | Activate paid license |
| `/shapecraft reload` | Reload config from disk |

---

## Performance Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| End-to-end generation latency | ≤ 15s | From command to block in inventory |
| Backend API response | ≤ 10s | LLM generation + RAG retrieval |
| Model validation | ≤ 50ms | JSON parsing + all validation rules |
| Client model injection | ≤ 2s | Resource reload after receiving model data |
| Block sync on join | ≤ 3s | Full pool sync (64 blocks) to new client |
| Server tick impact | < 1ms/tick | Generation runs async, never blocks server thread |
| Memory overhead (client) | ≤ 50 MB | Model cache + generated textures for 64 blocks |
| Memory overhead (server) | ≤ 20 MB | Block data + generation queue |

### Async Generation

Generation is inherently slow (LLM API call). It must **never block the server thread**. The flow:

1. Server receives `GenerateRequestC2S` on network thread
2. Validates caps/license on server thread (fast, <1ms)
3. Sends `GenerationStatusS2C(QUEUED)` to player
4. Submits HTTP call to backend on a **dedicated thread pool** (`Executors.newFixedThreadPool(2)`)
5. When backend responds, validates on the pool thread
6. Schedules result delivery on the server thread via `server.execute()`
7. Assigns pool slot, persists, broadcasts `GenerationCompleteS2C`

---

## Error Handling

### Error Categories

| Category | Example | Player Message | Logged |
|----------|---------|----------------|--------|
| Input validation | Description too long | "Description must be under 200 characters." | No |
| Content filter | Blocked word | "Your description contains blocked content. Please revise." | Yes (prompt text) |
| License/cap | Daily limit reached | "You've reached your daily generation limit (10/10). Try again tomorrow." | No |
| License/cap | Trial exhausted | "Trial generations used. Ask your server owner to subscribe." | Yes (server console) |
| Backend error | HTTP 500 | "Generation failed. Please try again." | Yes (full error) |
| Validation failure | Invalid LLM output | "Generation failed. Please try again with a different description." | Yes (validation errors) |
| Timeout | Backend > 30s | "Generation timed out. Please try again." | Yes |
| Pool full | All 64 slots used | "Block pool is full (64/64). No more blocks can be generated." | No |

### Invariants

- **A generation failure never crashes the game.** All backend/validation errors are caught and surfaced as chat messages.
- **A generation failure never produces a broken block.** If validation fails, the slot is not assigned.
- **Placed blocks are never affected by license state.** Expiration disables new generation only. Existing blocks continue to render and function.

---

## Implementation Phases

### Phase 0 — Project Scaffold
- Gradle project with `splitEnvironmentSourceSets()`
- `fabric.mod.json` with both entrypoints
- Empty `ModInitializer` and `ClientModInitializer`
- Pool block registration (64 blocks + items)
- Verify build, `runClient`, `runServer`

### Phase 1 — Networking Foundation
- Payload records and codecs for all packet types
- Handshake flow (version check on player join)
- Server-side receivers with stub handlers
- Client-side receivers with stub handlers
- `/shapecraft` command skeleton (Brigadier registration, permission checks)

### Phase 2 — Backend Integration
- Express routes in theblockacademy: `/shapecraft/generate`, `/shapecraft/trial`, `/shapecraft/validate`
- RAG corpus preparation script (enrich 2,138 models with descriptions + embeddings)
- `BackendClient` HTTP client in the mod
- End-to-end generation: command → backend → raw model JSON response (no injection yet)

### Phase 3 — Model Injection (MVP)
- `ModelLoadingPlugin` + `BlockStateResolver` for pool blocks
- `DynamicBlockModel` / `DynamicBakedModel` implementation
- `ModelCache` on client side
- `ModelValidator` on server side
- Resource reload trigger on generation complete
- Block added to creative tab with display name
- **Milestone: First generated block appears in-game**

### Phase 4 — Persistence & Sync
- `WorldDataManager` — save/load block assignments to world NBT
- Block sync on player join (`BlockSyncS2C`)
- Blocks survive world reload
- Directional placement (`HorizontalDirectionalBlock.FACING` + rotated models)
- Collision shapes from model elements

### Phase 5 — License System
- `LicenseManager` with 5-state FSM
- Trial auto-provisioning
- Activation command
- Daily cap enforcement
- `/shapecraft status` and `/shapecraft info` commands

### Phase 6 — Texture Pipeline (Tier 2)
- `NativeImage`-based tinting and compositing
- Atlas integration for generated textures
- `DynamicTextureManager` client-side
- Texture data included in sync payloads

### Phase 7 — Polish
- Preview GUI (US-003)
- Block family generation (US-004)
- Content filter
- Export as resource pack (US-030)
- `/shapecraft reload`
- Discord cog in slashAI

### Phase 8 — Multiplayer Hardening
- Server resource pack generation (for non-modded clients)
- Version mismatch messaging (US-032)
- Packet splitting for large sync payloads
- Stress testing with concurrent generation requests

---

## Open Design Decisions

### 1. Model Rebake Without Full Reload

`MinecraftClient.reloadResources()` shows a loading screen. Can we rebake a single block model without a full reload?

**Options:**
- A) Accept the loading screen (MVP)
- B) Mixin into `ModelManager` to rebake one block — fragile, version-specific
- C) Pre-bake quads on a background thread and swap them into `DynamicBakedModel` without reload — avoids the loading screen entirely but requires careful thread safety

Leaning: **A for MVP, then C.**

### 2. Pool Size Changes

If a server owner increases `poolSize` after blocks have been placed, new slots are fine. If they decrease it, placed blocks using removed slots would become invisible.

**Options:**
- A) Lock pool size after first boot (current design)
- B) Allow increases only, never decreases
- C) Allow any change, but warn and migrate

Leaning: **A (simplest, avoids data loss).**

### 3. Block Families (US-004) Implementation

Generating slab/stairs/wall variants from a base block requires:
- Mapping the base model geometry to slab proportions (bottom half only)
- Stairs require 3 separate element groups (bottom slab + top step)
- Wall requires post + side patterns

**Options:**
- A) LLM generates all variants from scratch (4 API calls, expensive)
- B) LLM generates base block; code derives variants programmatically (deterministic, cheap)
- C) Hybrid: LLM generates base + slab; code derives stairs/wall from those

Leaning: **B. Slab = bottom half of elements. Stairs = slab + shifted upper elements. Wall = center column from elements. Deterministic transforms are more reliable than asking the LLM four times.**

### 4. Generated Texture Format

For Tier 2 textures, should the server generate the texture PNG and send it, or should the server send tint parameters and the client generate the texture locally?

**Options:**
- A) Server generates PNG, sends bytes in packet — deterministic, all clients identical
- B) Client generates from parameters — smaller packets, but risk of floating-point rendering differences across platforms

Leaning: **A. Deterministic is worth the bandwidth. A 16x16 RGBA PNG is 1-2 KB compressed — negligible even for 64 blocks.**

### 5. Resource Pack Export Format

The `/shapecraft export` command (US-030) produces a zip. Should it be a resource pack or a data pack?

**Decision:** Resource pack. The export contains only visual assets (models, blockstates, textures). It does not register blocks — those only exist with ShapeCraft installed. The export allows other players to see the blocks' appearance, not place them.
