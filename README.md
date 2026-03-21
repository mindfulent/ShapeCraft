# ShapeCraft v0.4.6

Natural language block generation for Minecraft. Describe a custom block in plain English and ShapeCraft generates a valid block model, selects textures, and injects it as a placeable block — all in real time, no restart needed.

## How It Works

```
/shapecraft a weathered copper lantern with chain attachment
```

1. Your description is matched against ~2,100 vanilla block models via RAG retrieval (Voyage embeddings + pgvector)
2. Claude Sonnet 4.6 generates a valid block model JSON using the retrieved models as few-shot context
3. Textures are selected from 140+ vanilla block textures, with optional color tinting
4. The model is validated against 11 structural rules and injected via Fabric's Model Loading API
5. The block appears in your inventory — placeable, persistent, directional, and synced to all players

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/shapecraft <description>` | All players | Generate a block from a description (max 200 characters) |
| `/shapecraft info` | All players | Show pool status, license state, and remaining daily generations |
| `/shapecraft status` | Op (level 4) | Extended license info: state, monthly usage, server ID, last validation |
| `/shapecraft activate <code>` | Op (level 4) | Activate a paid license with a `SHAPE-XXXX-XXXX` code |
| `/shapecraft reload` | Op (level 4) | Reload configuration (not yet implemented) |

## Generation Pipeline

When a player runs `/shapecraft`, the following happens on the server:

```
Player command
  → Content filter (blocklist check)
  → License check (trial/active/grace, daily cap)
  → Concurrency guard (one generation per player at a time)
  → Pool capacity check (64 slots max)
  → HTTP POST to backend API (async, 30s timeout)
      → Voyage embedding of description
      → pgvector nearest-neighbor retrieval (3 similar vanilla models)
      → Claude Sonnet 4.6 generates block model JSON
  → 11-rule model validation
  → Slot assignment (synchronized)
  → World data persistence (NBT)
  → Broadcast to all connected players
  → Block item added to player inventory
```

Status updates appear in the player's action bar throughout: *Queued → Generating → Validating → Complete*.

## Block Pool

ShapeCraft pre-registers 64 blocks at startup (`shapecraft:custom_0` through `custom_63`). Minecraft's block registry is frozen after initialization, so these slots are allocated upfront and assigned dynamically as blocks are generated.

Each pool block:
- Has a `PoolBlockEntity` storing the display name, model JSON, and computed collision shape
- Supports directional placement (NORTH/SOUTH/EAST/WEST via `FACING` property)
- Computes a custom `VoxelShape` from the model's element bounding boxes for accurate collision
- Persists to world NBT and syncs to all players on join

The creative tab only shows blocks that have been assigned (unassigned slots are hidden).

## Model Validation

Generated models are validated against 11 rules before injection:

| Rule | Check |
|------|-------|
| V-001 | Valid JSON syntax |
| V-002 | Only known top-level keys (`parent`, `textures`, `elements`, etc.) |
| V-003 | Parent resolves to one of 50 known vanilla parents |
| V-004 | Element count ≤ 16 |
| V-005 | All coordinates in [-16, 32] range |
| V-006 | No degenerate (zero-volume) elements |
| V-007 | Face textures use variable references (`#name`) |
| V-008 | UV values in [0, 16] range |
| V-009 | Rotation angles are valid (-45, -22.5, 0, 22.5, 45 for elements; 0, 90, 180, 270 for faces) |
| V-010 | Texture paths resolve to known vanilla textures (warning only) |
| V-011 | Face directions are valid (north/south/east/west/up/down) |

## Model Injection

Models are injected client-side without a server restart:

1. `ShapeCraftModelPlugin` (Fabric `ModelLoadingPlugin`) registers a `BlockStateResolver` for each pool block
2. On resource reload, `DynamicBlockModel` (an `UnbakedModel`) parses the generated JSON
3. Texture variables are resolved through the model's texture map (recursive, depth limit 10)
4. `FaceBakery.bakeQuad()` builds quads for each element face with proper UV mapping and rotation
5. `DynamicBakedModel` stores the baked quads per direction for rendering
6. `BlockModelRotation` handles directional variants (Y-axis rotation per `FACING` state)

Currently triggers a full `reloadResourcePacks()` on generation (causes a brief loading screen).

## Texture Pipeline

**Tier 1 — Vanilla References:** Generated models reference any of 140+ vanilla block textures (stone, planks, wool, concrete, metals, etc.) via texture variables. No custom textures are created.

**Tier 2 — Color Tinting:** The backend can return `texture_tints` specifying color blends on vanilla textures:

```json
{ "#side": { "color": "#FF8800", "strength": 0.5 } }
```

`DynamicTextureManager` loads the vanilla base texture, applies a per-pixel color blend (`result = original * (1 - strength) + tint * strength`), and `SpriteLoaderMixin` injects the tinted texture into the block atlas during resource reload.

## License System

ShapeCraft uses a 5-state license FSM:

```
UNINITIALIZED ──auto──→ TRIAL ──activate──→ ACTIVE
                          │                    │
                          │ (depleted)         │ (validation fail)
                          ↓                    ↓
                       EXPIRED ←──7 days──── GRACE
```

| State | Behavior |
|-------|----------|
| TRIAL | 50 generations total, auto-provisioned on first server boot |
| ACTIVE | 250 generations/month, activated via Patreon code |
| GRACE | 7-day window after validation failure, generation still allowed |
| EXPIRED | No generation. Reactivate with `/shapecraft activate` |

**Rate limits:** 10 generations per player per day (UTC reset), enforced client-side by `DailyCapTracker`. Monthly cap enforced server-side.

**Validation:** License validated every 24 hours via backend API. Server polls every ~5 minutes (6,000 ticks) and validates if overdue.

## Networking

8 payload types handle all client-server communication:

| Payload | Direction | Purpose |
|---------|-----------|---------|
| `HandshakeC2S` | C→S | Send mod version + protocol version on join |
| `HandshakeResponseS2C` | S→C | Confirm compatibility, report pool size |
| `GenerateRequestC2S` | C→S | Submit block description |
| `GenerationStatusS2C` | S→C | Progress updates (queued/generating/validating) |
| `GenerationCompleteS2C` | S→C | Broadcast new block to all players |
| `GenerationErrorS2C` | S→C | Error message to requesting player |
| `BlockSyncRequestC2S` | C→S | Request full block resync |
| `BlockSyncS2C` | S→C | All assigned blocks (sent on join) |

Protocol version mismatch results in a clear error message with both server and client versions.

## Configuration

**`config/shapecraft/config.json`:**

| Field | Default | Description |
|-------|---------|-------------|
| `poolSize` | 64 | Number of block slots available |
| `maxGenerationsPerPlayerPerDay` | 10 | Per-player daily cap (UTC reset) |
| `maxPromptLength` | 200 | Max description length in characters |
| `backendUrl` | `https://theblockacademy.com` | Backend API endpoint |
| `contentFilterEnabled` | true | Enable blocklist content filter |
| `debug` | false | Verbose logging |

**`config/shapecraft/license.json`** is auto-generated and stores the license key, server ID, state, usage counters, and validation timestamps.

## Backend Architecture

The mod calls `POST /shapecraft/generate` on the backend (hosted in theblockacademy). The backend:

1. Validates the license and deducts a generation credit
2. Embeds the description via Voyage (`voyage-3-lite`, 1024 dims)
3. Retrieves 3 similar vanilla models from pgvector
4. Calls Claude Sonnet 4.6 with a cached system prompt (~2,500 tokens) + RAG examples
5. Parses and validates the response (retries once on malformed JSON)
6. Returns `model_json` (as a string), `display_name`, `texture_tints`, and token counts

Additional backend endpoints: `/shapecraft/trial` (auto-provision), `/shapecraft/validate` (license check), `/shapecraft/activate` (code redemption), `/shapecraft/health`.

## Build

```bash
# Requires Java 21
JAVA_HOME="/path/to/java-21" ./gradlew build

# Development
./gradlew runClient   # Test in Minecraft client
./gradlew runServer   # Test dedicated server
```

## Requirements

- Minecraft 1.21.1
- Fabric Loader ≥ 0.16.0
- Fabric API
- Java 21

## License

All rights reserved.
