# ShapeCraft v0.3.0

Natural language block generation for Minecraft.

Describe a custom block in plain English and ShapeCraft generates a valid block model, selects textures, and injects it as a placeable block — all in real time, no restart needed.

## How It Works

```
/shapecraft a weathered copper lantern with chain attachment
```

1. Your description is matched against ~2,100 vanilla block models via RAG retrieval
2. Claude Sonnet 4.6 generates a valid block model JSON using retrieved examples as few-shot context
3. A texture is selected from the vanilla palette (recoloring as needed)
4. The model is validated (11 rules) and injected via Fabric's Model Loading API
5. The block appears in your inventory — placeable, persistent, and directional

## Status

**Backend integration complete.** Phases 0–10 implemented: block pool (64 slots), networking (8 payloads), backend client, model injection (ModelLoadingPlugin + FaceBakery), persistence (SavedData), license system (5-state FSM with daily caps), backend API routes with RAG retrieval, content filter, texture tinting pipeline, multiplayer hardening, and Discord admin cog.

## Architecture

| Component | Status |
|-----------|--------|
| Block Pool | 64 pre-registered blocks with BlockEntity + VoxelShape collision |
| Networking | 8 payload types, handshake on join, block sync |
| Backend Client | HTTP client with async CompletableFuture, 30s timeout |
| Model Validator | 11 validation rules (JSON syntax, coordinates, textures, rotations) |
| Model Injection | ModelLoadingPlugin + BlockStateResolver + DynamicBlockModel (FaceBakery) |
| Persistence | SavedData (world NBT), auto-sync on join |
| License System | 5-state FSM (trial/active/grace/expired), daily cap, monthly quota |
| Content Filter | Blocklist-based client + server-side filtering |
| Texture Pipeline | Vanilla references (Tier 1) + tinting via SpriteLoader mixin (Tier 2) |

## Build

```bash
# Requires Java 21
JAVA_HOME="/path/to/java-21" ./gradlew build
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
