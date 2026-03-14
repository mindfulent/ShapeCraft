# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ShapeCraft is a Minecraft mod (Fabric 1.21.1) that generates custom block models from natural language descriptions using an LLM backed by a RAG index of vanilla block definitions. Status: **concept stage** — no mod code exists yet.

Design brief: `docs/BRIEF.md`

## Architecture (Planned)

Five components, per the brief:

1. **Vanilla RAG Index** — ~2,100 block model JSONs enriched with semantic descriptions, indexed by structural metadata and semantic embedding
2. **LLM Backend** — Takes user prompt + retrieved examples → valid block model JSON (cloud API first, local fallback later)
3. **Texture Pipeline** — Three tiers: vanilla recolor (MVP), procedural composite, image model generation
4. **Validator** — Ensures generated JSON is structurally valid (0–16 coordinate range, valid faces/textures/parent chains)
5. **Resource Pack Injector** — Runtime injection via Fabric resource pack API (no restart needed)

**MVP scope:** `/shapecraft <description>` → RAG retrieval → LLM generates model JSON inheriting from existing parent → vanilla texture selection → runtime resource pack injection → placeable block in creative tab.

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

No build system exists yet. When the mod is scaffolded, it will use:

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

## Key Technical Constraints

- **Block registry timing** — Minecraft registers blocks at startup. Visual injection works via runtime resource packs, but functional blocks (collision, hardness) may need a pre-registered pool of "blank" blocks assigned dynamically.
- **Multiplayer** — All clients need matching generated models. Requires sync protocol or server-side resource pack distribution.
- **Coordinate space** — All element coordinates must be in 0–16 range. Face references and texture paths must resolve. Parent chains must exist. The validator must enforce all of this.
