# ShapeCraft — Product Requirements Document

**Author:** Slash
**Date:** 2026-03-14
**Status:** Draft
**Reference:** [Design Brief](BRIEF.md)

---

## Vision

Players describe a block in plain English and get a placeable, textured custom block in seconds — no JSON editing, no resource pack authoring, no restart. ShapeCraft turns Minecraft's block model system from an expert tool into a creative playground.

## Target Users

**Primary:** Creative-mode builders who want unique blocks but don't know (or don't want to deal with) the model JSON format.

**Secondary:** Modpack authors and server operators who want to offer custom block creation as a server feature.

## Success Criteria

- A player with zero modding experience can generate a recognizable custom block in under 30 seconds from first command.
- Generated blocks are visually consistent with vanilla Minecraft's art style.
- Blocks persist, can be reused, and can be shared without requiring recipients to regenerate them.
- Trial-to-paid conversion rate ≥ 20% (consistent with StreamCraft/SynthCraft benchmarks).
- Zero-friction onboarding: server owners install the JAR and generation works — no signup, no config, no activation.

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Multiplayer only | Yes | Consistent with StreamCraft/SynthCraft/SceneCraft ecosystem. Simplifies licensing (always per-server). |
| Local LLM support | No | All generation goes through the backend. Simplifies licensing, ensures quality, enables usage tracking. |
| Block pool size | 64 (configurable) | Covers casual use. Server owners can increase via config if needed. |
| Model complexity cap | 16 elements max | Vanilla models rarely exceed 6-8. Cap prevents rendering performance issues while leaving room for creative geometry. |
| Directional placement | Yes (core feature) | Blocks rotate based on player facing direction, like vanilla. Fixed orientation breaks the builder experience for any asymmetric block. |
| Pricing | $8/month, 250 generations/month | Cost ceiling per server: $7.50 (250 × $0.03). Never loses money. Low enough to be an easy yes; lower than SynthCraft ($10) since no GPU compute. |
| Trial budget | 50 generations (one-time) | ~$1.50 API cost. Enough to generate 50 unique blocks — a genuinely useful set that hooks a server. Cannot be reset. |
| Per-player daily cap | 10 generations/day | Prevents one player from burning the server's monthly budget in a single session. Configurable by server owner. |
| Generation backend | Claude Sonnet 4.6 | ~$0.03/generation worst case ($3/$15 per MTok input/output). Prompt caching reduces steady-state cost to ~$0.02. |

---

## Epic: Block Creation

The core loop — describe a block, get a block.

### US-001: Describe and Receive a Block

> As a player, I want to describe a block in chat and receive it in my inventory so I can place it immediately without leaving the game.

**Acceptance Criteria:**

- I type `/shapecraft a weathered copper lantern` and receive a chat confirmation that generation has started.
- Within 15 seconds, a block item appears in my inventory.
- The block is placeable, has collision, and renders a model that visually matches my description.
- If generation fails, I get a clear error message — not a crash or silence.

### US-002: Directional Placement

> As a builder, I want generated blocks to rotate based on the direction I'm facing when I place them so I can orient asymmetric blocks the way I want.

**Acceptance Criteria:**

- When I place a generated block, it faces toward me — the same behavior as vanilla blocks like furnaces, chests, and glazed terracotta.
- I can place the same block in all four horizontal directions by rotating myself before placing.
- Symmetric blocks (cubes, pillars) look correct regardless of placement direction.

### US-003: Preview Before Committing

> As a player, I want to see a 3D preview of the generated block before it's added to my world so I can discard results I don't like without wasting a slot.

**Acceptance Criteria:**

- A GUI (opened via keybind or command variant) shows a rotating 3D preview of the generated block.
- I can accept (block goes to inventory) or discard (generation count is still deducted since the API cost was incurred, but no block slot is consumed).
- The preview shows the block with its textures applied, not just wireframe geometry.

### US-004: Generate a Block Family

> As a builder, I want to generate a matching set of block variants (slab, stairs, wall) from one description so I can build cohesively without generating each variant separately.

**Acceptance Criteria:**

- `/shapecraft family <description>` produces a base block plus slab, stairs, and wall variants.
- All variants share the same visual style and textures.
- Each variant behaves correctly (slabs are half-height, stairs are climbable, etc.).
- Family generation counts as 4 generations (one per variant) against both monthly and daily caps.

---

## Epic: Block Management

Organizing, finding, and reusing generated blocks.

### US-010: Browse My Generated Blocks

> As a player, I want all my generated blocks in one place so I can find and reuse them without remembering exact names or commands.

**Acceptance Criteria:**

- A "ShapeCraft" creative tab lists every block I've generated in the current world.
- Each entry shows a display name derived from my original description.
- The tab updates immediately when I generate a new block.

### US-011: Block Persistence

> As a player, I want my generated blocks to survive world reloads so I don't lose my creations when I close the game.

**Acceptance Criteria:**

- Blocks I've placed in the world remain after save/quit/reload.
- The ShapeCraft creative tab still lists all previously generated blocks after reload.
- Block appearance (model + texture) is identical before and after reload.

### US-012: Block Limit Transparency

> As a player, I want to know how many blocks I can still generate so I'm not surprised by a hard cap.

**Acceptance Criteria:**

- `/shapecraft info` shows how many block slots remain out of the total available.
- When I'm within 5 of the limit, generation confirmations include a remaining-count warning.
- When the limit is reached, the error message is clear and directs the server owner to subscribe on Patreon to unlock more generations.

---

## Epic: Visual Quality

Blocks should look like they belong in Minecraft.

### US-020: Vanilla-Consistent Appearance

> As a player, I want generated blocks to look like they could be vanilla Minecraft blocks so they don't clash with the rest of the world.

**Acceptance Criteria:**

- Generated blocks use textures drawn from (or visually consistent with) the vanilla texture set.
- Generated blocks have clean surfaces with no overlapping faces, texture bleeding, or flickering.
- A block described as "oak planks but darker" looks recognizably like a wood plank variant, not abstract geometry.

### US-021: Unique Textures Through Tinting and Compositing

> As a player, I want generated blocks to have their own color identity rather than always reusing exact vanilla textures so my builds feel custom.

**Acceptance Criteria:**

- A block described as "red sandstone bricks" uses a tinted/recolored texture, not identical vanilla sandstone.
- The tinting looks natural — no neon artifacts or posterization.
- Composite textures (e.g., "mossy iron grate") blend two vanilla textures convincingly.

---

## Epic: Sharing and Portability

Getting blocks out of one world and into others.

### US-030: Export as Resource Pack

> As a player, I want to export my generated blocks as a standard resource pack so I can share them with friends or use them in other worlds without ShapeCraft.

**Acceptance Criteria:**

- `/shapecraft export` produces a zip file I can drop into any Minecraft instance's resource pack folder.
- The exported pack works in vanilla Minecraft — no mod dependency.
- Export includes all models, blockstates, and textures for every generated block in the world.

### US-031: Multiplayer Block Sharing

> As a player on a multiplayer server, I want to see custom blocks that other players have generated so we can all build with the same palette.

**Acceptance Criteria:**

- When I join a server running ShapeCraft, I automatically receive all generated blocks without manual downloads.
- Blocks generated by any player on the server appear in everyone's ShapeCraft creative tab.
- If I decline the server resource pack, I see placeholder blocks instead of invisible/broken geometry.

### US-032: Client-Server Version Matching

> As a player joining a server, I want clear feedback if my ShapeCraft version doesn't match the server's so I can troubleshoot without guessing.

**Acceptance Criteria:**

- If my mod version matches the server's, everything works normally.
- If versions mismatch, I see a chat message on join explaining the issue and which version to update to.
- If the server has ShapeCraft but I don't, I can still join and play — generated blocks render as their fallback placeholder.

### US-033: Multiplayer-Only Restriction

> As a player, I want to understand that ShapeCraft requires a multiplayer server so I don't expect it to work in single-player.

**Acceptance Criteria:**

- ShapeCraft functionality is disabled in single-player and LAN worlds.
- If I try to use the generation command in single-player, I see a clear chat message: "ShapeCraft requires a dedicated server with the mod installed."
- I can still join a server and play normally without the mod — generated blocks show as placeholders.

---

## Epic: Licensing & Activation

*Follows the same per-server licensing model established by StreamCraft and SynthCraft.*

ShapeCraft has per-generation infrastructure costs (Claude API calls). The licensing model gates generation behind a trial/paid system while keeping the experience frictionless for new users.

### US-040: Zero-Setup Trial

> As a server owner installing ShapeCraft for the first time, I want the mod to just work out of the box so my players can try it without any signup or payment.

**Acceptance Criteria:**

- I install the mod JAR, start the server, and generation works immediately — no registration, no activation code, no payment info.
- Trial provides 50 generations (invisible to players; only the server owner sees remaining count via `/shapecraft status`).
- Server console shows only: "ShapeCraft ready" — no indication this is a trial.
- The experience is identical to a paid license during the trial period.
- The same 10 per-player daily cap applies during the trial.

### US-041: Trial Expiration

> As a server owner whose trial has ended, I want a clear explanation of what happened and how to continue so I can make an informed decision about subscribing.

**Acceptance Criteria:**

- When trial generations are exhausted, block generation is disabled server-wide.
- Previously generated blocks continue to work — they're still placeable, visible, and persistent. Only new generation is gated.
- Players who try to generate see a clear message explaining the trial has ended with a link to subscribe.
- Server console shows the same message with subscription details.
- Trial cannot be reset by reinstalling the mod (one trial per server identity).

### US-042: Subscription Activation

> As a server owner, I want to activate ShapeCraft with a code from my Patreon subscription so my server gets a monthly generation budget.

**Acceptance Criteria:**

- I subscribe on Patreon ($8/month) and receive an activation code (e.g., `SHAPE-XXXX-XXXX`).
- I run `/shapecraft activate SHAPE-XXXX-XXXX` on my server.
- Server confirms activation immediately — generation is unlocked with 250 generations per month.
- Monthly budget resets on the subscription renewal date.
- One code activates one server. The code cannot be reused on a different server.

### US-043: Subscription Lapse Handling

> As a server owner, I want a grace period if my payment lapses so my players aren't immediately cut off.

**Acceptance Criteria:**

- If my Patreon subscription lapses, generation continues working for 7 days (grace period).
- After 7 days, generation disables with a clear re-subscribe message.
- If the backend is unreachable (my server has no internet temporarily), generation continues for up to 7 days on cached license state.
- Previously generated blocks are never affected — only new generation is gated.

---

## Epic: Server Administration

Server owners need control over generation and visibility into usage.

### US-050: Server Configuration

> As a server owner, I want to control ShapeCraft's behavior on my server so I can manage resource usage and player experience.

**Acceptance Criteria:**

- Config file allows setting: max generations per player per day (default: 10), block pool size (default: 64), content filter toggle, and generation permissions (everyone, ops only, or allowlist).
- Config changes take effect on reload (`/shapecraft reload`) — no server restart required.
- Players without generation permission can still place and use previously generated blocks.

### US-051: In-Game Status and Admin Commands

> As a server admin, I want in-game commands for monitoring and controlling ShapeCraft so I can manage it while playing.

**Acceptance Criteria:**

- `/shapecraft status` — Shows license state, generations remaining this period (e.g., "183/250 remaining" for paid, "12/50 remaining" for trial), block pool usage (e.g., "42/64 slots used"), and active player count.
- `/shapecraft activate <code>` — Activates a paid license.
- `/shapecraft reload` — Reloads config from disk.
- All admin commands require op permissions.

### US-052: Content Moderation

> As a server owner, I want to filter player prompts so offensive or disruptive descriptions are rejected before reaching the AI backend.

**Acceptance Criteria:**

- A configurable word filter runs server-side on all generation prompts.
- Filtered prompts are rejected with a clear message: "Your description contains blocked content. Please revise."
- Default filter blocks common offensive terms; server owners can customize the blocklist.
- Filter can be disabled entirely if the server owner prefers.

---

## Epic: Owner Dashboard (slashAI)

*Discord slash commands for the mod owner to monitor licensing, usage, and server health across all ShapeCraft installations. Implemented as a new cog in slashAI, following the same pattern as StreamCraftCommands.*

### US-060: License Overview

> As the mod owner, I want to see all ShapeCraft licenses at a glance from Discord so I can monitor adoption without checking the database directly.

**Acceptance Criteria:**

- `/shapecraft licenses` lists all licenses: server name/ID, state (trial/active/grace/expired), generations remaining (trial), last validated timestamp.
- Response is ephemeral (only visible to me).
- Supports filtering by state (e.g., `/shapecraft licenses state:trial`).

### US-061: Usage Statistics

> As the mod owner, I want aggregate usage stats so I can track growth, costs, and engagement.

**Acceptance Criteria:**

- `/shapecraft stats` shows: total generations (all time), generations this month, active servers (generated in last 7 days), total API cost this month, trial→paid conversion rate.
- `/shapecraft servers` shows per-server breakdown: server name/ID, total generations, unique players, last active.
- `/shapecraft player <name>` shows per-player stats: total blocks generated, recent prompts, last active server.

### US-062: Active Monitoring

> As the mod owner, I want to see real-time activity so I can spot issues or celebrate milestones.

**Acceptance Criteria:**

- `/shapecraft active` shows servers with generation activity in the last hour: server name, player count, recent generations.
- Owner-only, ephemeral responses.

### US-063: License Management

> As the mod owner, I want to manually adjust license states from Discord for support cases (e.g., extending a trial, transferring a license).

**Acceptance Criteria:**

- `/shapecraft set-state <server_id> <state>` changes a license state (trial/active/grace/expired).
- `/shapecraft hide <server_id>` / `/shapecraft unhide <server_id>` hides/shows a server in listings (for test or internal servers).
- All management commands are owner-only with confirmation prompts for destructive actions.

---

## Non-Functional Requirements

### Performance

- End-to-end generation: ≤15s.
- Resource pack reload after generation: ≤2s (no perceptible hitch during gameplay).
- Mod should not increase world load time by more than 1s.

### Reliability

- Invalid AI output must never crash the game.
- Failed generations always surface a clear player-facing message.
- Block data survives crash recovery — no orphaned or corrupted entries.

### Security

- API keys are never exposed in world saves, chat logs, or shared exports.
- Player descriptions are sent to the AI backend and nothing else — no telemetry, no analytics.
- Generated block data cannot be used to execute arbitrary file operations.
- Generation prompts are capped at 200 characters. Backend enforces a response token limit to prevent cost abuse from adversarial inputs.

### Compatibility

- Must coexist with popular performance and building mods (Sodium, Iris, Litematica).
- Generated blocks must render correctly with shader packs enabled.
- Platform and toolchain requirements are deferred to the Technical Design Document.

---

## Cost Model

Based on Claude Sonnet 4.6 pricing ($3/MTok input, $15/MTok output):

| Metric | Value |
|--------|-------|
| Input tokens per generation | ~4,000 (system prompt + RAG examples + description) |
| Output tokens per generation | ~1,000 (model JSON + blockstate JSON) |
| Cost per generation (worst case) | ~$0.03 |
| Cost per generation (with prompt caching) | ~$0.02 |
| Max monthly cost per paid server (250 × $0.03) | $7.50 |
| Subscription price | $8.00/month |
| Minimum margin per paid server | $0.50/month (worst case, all 250 used, no caching) |
| Typical margin per paid server | ~$4.00/month (avg 200 generations, with caching) |
| Trial cost per server (50 × $0.03) | $1.50 |
| Breakeven: trials per paying server | 5.3 trials per conversion (well above 20% target) |

## Texture Asset Policy

ShapeCraft never redistributes unmodified vanilla Minecraft texture files. The approach:

- **Model references only** — Generated model JSON references vanilla textures by path (e.g., `minecraft:block/oak_planks`). The game loads these natively. No Mojang assets are copied or bundled.
- **Generated textures are new assets** — Tinted and composited textures are written as new PNGs in the `shapecraft:` namespace. They are derivative works created by the mod, not copies of originals.
- **Export excludes vanilla files** — The `/shapecraft export` resource pack contains only ShapeCraft-generated assets: model JSONs, blockstate JSONs, and generated texture PNGs. Raw vanilla textures are never included.
- **Disclaimer on export** — Exported packs include a note: "Generated textures may be derived from Minecraft's vanilla art style. ShapeCraft is not affiliated with Mojang or Microsoft."

This matches established modding community practice. Resource pack creators routinely create derivative textures (tinted, composited, restyled variants). Mojang's enforcement targets wholesale redistribution of their assets and misrepresentation of affiliation — not derivative creative works in mods.
