\# ShapeCraft — Natural Language Block Generation for Minecraft



\*\*Status:\*\* Concept / Internal Build Spec

\*\*Author:\*\* Slash

\*\*Date:\*\* 2026-03-14



\---



\## One-Liner



A Minecraft mod that lets players describe custom blocks in natural language and generates valid block models, textures, and blockstate definitions in real time using an LLM backed by a RAG index of all vanilla block definitions.



\## Problem



Minecraft's block model system is powerful but inaccessible. Creating a custom block today requires hand-authoring JSON model files, understanding the 0–16 coordinate space, managing texture UV mapping, wiring up blockstate variants, and keeping everything consistent with the resource pack format. Even experienced modders treat this as tedious grunt work. For players, it's completely out of reach.



The vanilla JSON format is actually \*well-suited\* for generation — it's a constrained DSL of axis-aligned boxes with texture references — but no tooling exists that bridges natural language intent to valid model output.



\## Insight



All \~2,100 vanilla block models follow predictable patterns: parent inheritance chains, a small set of reusable template shapes (cube, slab, stairs, cross, crop, etc.), and consistent texture reference conventions. This is a small, highly structured output space — not arbitrary 3D geometry. An LLM with the right examples can reliably produce valid output, and retrieval over the vanilla corpus provides both few-shot examples and compositional building blocks.



\## How It Works



\### Player Experience



1\. Player opens an in-game UI or types a chat command: `/generate a weathered copper lantern with chain attachment`

2\. The mod sends the prompt to the generation backend

3\. Within a few seconds, the block appears in their inventory — fully textured, placeable, with appropriate blockstate variants

4\. Blocks persist across sessions and can be shared as data packs



\### System Architecture (High Level)



```

┌─────────────┐     ┌──────────────────┐     ┌─────────────┐

│  In-Game UI  │────▶│  Generation API   │────▶│  LLM (Cloud │

│  /command    │     │  (local or remote)│     │  or Local)  │

└─────────────┘     └────────┬─────────┘     └─────────────┘

&#x20;                            │                       │

&#x20;                   ┌────────▼─────────┐    ┌───────▼────────┐

&#x20;                   │  Vanilla RAG     │    │  Texture Gen /  │

&#x20;                   │  Index (\~2,100   │    │  Palette Mapper │

&#x20;                   │  models + semantic│    └────────────────┘

&#x20;                   │  descriptions)   │

&#x20;                   └──────────────────┘

&#x20;                            │

&#x20;                   ┌────────▼─────────┐

&#x20;                   │  Validator \&      │

&#x20;                   │  Resource Pack    │

&#x20;                   │  Injector         │

&#x20;                   └──────────────────┘

```



\*\*Five components:\*\*



\- \*\*Vanilla RAG Index\*\* — All \~2,100 block model JSONs, enriched with semantic descriptions (generated once offline: "a half-height horizontal slab," "a cross-shaped plant on a stick," etc.). Indexed by both structural metadata (element count, bounding box, parent chain) and semantic embedding. Used for retrieval of similar shapes and few-shot prompting.



\- \*\*LLM Backend\*\* — Takes the user prompt + retrieved examples and outputs a valid block model JSON (or set of JSONs for multi-state blocks). Could be cloud-hosted or a capable local model. The constrained output format means even smaller models may perform well here.



\- \*\*Texture Pipeline\*\* — Three tiers:

&#x20; - \*\*Fast:\*\* Select and tint existing vanilla textures (recolor stone to get "weathered copper")

&#x20; - \*\*Medium:\*\* Composite/blend existing textures procedurally (overlay chain pattern on lantern base)

&#x20; - \*\*Full:\*\* Generate novel 16×16 tiles via image model (slow, highest fidelity)



\- \*\*Validator\*\* — Ensures output JSON is structurally valid (coordinates in 0–16 range, valid face references, texture paths resolve, parent chain exists). Rejects or auto-corrects malformed output before injection.



\- \*\*Resource Pack Injector\*\* — Injects generated models, blockstates, and textures as a runtime resource pack using Fabric's resource pack API (or equivalent). No game restart needed.



\## Key Technical Decisions to Make



| Decision | Options | Leaning |

|---|---|---|

| Mod loader | Fabric vs. Forge/NeoForge | Fabric — better runtime resource pack support |

| LLM hosting | Cloud API vs. local (ollama, etc.) | Start cloud, design for local fallback |

| Texture approach | Vanilla-only vs. generation | Start with vanilla palette mapping, add generation later |

| Block behavior | Visual-only vs. functional | Visual-only MVP, predefined behavior templates in v2 |

| Persistence | Per-world vs. exportable data pack | Both — auto-save to world, export command for sharing |

| Block families | Single block vs. full sets | Single block MVP, "generate family" as a power feature |



\## MVP Scope



The smallest thing that proves the concept:



1\. Chat command input (`/shapecraft <description>`)

2\. RAG retrieval over vanilla models (pre-built index, embedded descriptions)

3\. LLM generates a single block model JSON inheriting from an existing parent

4\. Texture selected from vanilla set (no generation)

5\. Model injected via runtime resource pack

6\. Block added to a creative tab, placeable and persistent in-world



\*\*Explicitly out of scope for MVP:\*\* texture generation, block behaviors/interactions, multi-state blocks, block families, GUI, multiplayer sync.



\## Risks \& Open Questions



\- \*\*Model reliability\*\* — How often does the LLM produce invalid JSON or nonsensical geometry? Mitigation: strong validation layer + retry logic + constrained decoding if using local model.

\- \*\*Latency\*\* — Cloud LLM round-trip may feel slow for an in-game action. Mitigation: async generation with a "block is generating..." placeholder, local model option.

\- \*\*Texture quality\*\* — Vanilla palette mapping may feel limiting. Mitigation: even basic recoloring goes surprisingly far; generation tier is a clear upgrade path.

\- \*\*Registry timing\*\* — Minecraft expects blocks registered at startup. Runtime injection via resource packs handles the \*visual\* side, but functional block registration (with collision, hardness, etc.) may need tricks like a pre-registered pool of "blank" blocks that get assigned dynamically.

\- \*\*Multiplayer\*\* — All clients need the same generated models. Needs a sync protocol or server-side resource pack distribution.



\## What Success Looks Like



A player types a description, and within a few seconds they're holding a block that looks like what they asked for. It doesn't need to be perfect — it needs to be \*recognizable\* and \*delightful\*. The magic is in the immediacy: describe → place → build.



If the output is good enough that players use it as a starting point and tweak the JSON by hand, that's a win too — it becomes a creative tool, not just a generator.

