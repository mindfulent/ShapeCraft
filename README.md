# ShapeCraft v0.1.3

Natural language block generation for Minecraft.

Describe a custom block in plain English and ShapeCraft generates a valid block model, selects textures, and injects it as a placeable block — all in real time, no restart needed.

## How It Works

```
/shapecraft a weathered copper lantern with chain attachment
```

1. Your description is matched against ~2,100 vanilla block models via RAG retrieval
2. An LLM generates a valid block model JSON using retrieved examples as few-shot context
3. A texture is selected from the vanilla palette (recoloring as needed)
4. The model is validated and injected as a runtime resource pack
5. The block appears in your inventory — placeable and persistent

## Status

**Concept stage.** The design brief and vanilla asset corpus are in place. No mod code yet.

See [`docs/BRIEF.md`](docs/BRIEF.md) for the full design document.

## Planned Architecture

| Component | Role |
|-----------|------|
| Vanilla RAG Index | ~2,100 block models with semantic descriptions, indexed for retrieval |
| LLM Backend | Cloud API (local fallback) generates valid block model JSON |
| Texture Pipeline | Vanilla recolor → procedural composite → image generation (3 tiers) |
| Validator | Ensures output JSON is structurally valid (0–16 coords, valid refs) |
| Resource Pack Injector | Runtime injection via Fabric resource pack API |

## Requirements (when implemented)

- Minecraft 1.21.1
- Fabric Loader
- Java 21

## License

All rights reserved.
