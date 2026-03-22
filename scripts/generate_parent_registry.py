"""
Generate flattened parent model registry for ShapeCraft.

Reads vanilla Minecraft block model JSONs, walks parent chains to resolve
elements and textures, and outputs a single registry JSON file.
"""

import json
import os
import sys

ASSETS_DIR = os.path.join(os.path.dirname(__file__), "..", "minecraft_assets", "models")
OUTPUT_PATH = os.path.join(
    os.path.dirname(__file__), "..", "src", "main", "resources", "data", "shapecraft", "parent_models.json"
)

KNOWN_PARENTS = [
    "block/block",
    "block/cube",
    "block/cube_all",
    "block/cube_column",
    "block/cube_column_horizontal",
    "block/cube_bottom_top",
    "block/cube_top",
    "block/cube_directional",
    "block/orientable",
    "block/orientable_vertical",
    "block/slab",
    "block/slab_top",
    "block/stairs",
    "block/inner_stairs",
    "block/outer_stairs",
    "block/wall_post",
    "block/wall_side",
    "block/wall_side_tall",
    "block/fence_post",
    "block/fence_side",
    "block/fence_gate",
    "block/fence_gate_open",
    "block/fence_gate_wall",
    "block/fence_gate_wall_open",
    "block/cross",
    "block/tinted_cross",
    "block/crop",
    "block/flower_pot_cross",
    "block/pressure_plate_up",
    "block/pressure_plate_down",
    "block/button",
    "block/button_pressed",
    "block/door_bottom_left",
    "block/door_bottom_right",
    "block/door_top_left",
    "block/door_top_right",
    "block/trapdoor_bottom",
    "block/trapdoor_top",
    "block/trapdoor_open",
    "block/template_torch",
    "block/template_torch_wall",
    "block/lantern",
    "block/template_lantern",
    "block/hanging_lantern",
    "block/template_hanging_lantern",
    "block/carpet",
    "block/thin_block",
    "block/template_anvil",
    "block/leaves",
    "block/template_glazed_terracotta",
    "block/coral_fan",
    "block/template_single_face",
]

# Maps VanillaAssets parent names to their actual model file names.
# Some parent names in VanillaAssets.java don't match the actual JSON filenames
# (vanilla uses template_ prefix for generic versions).
ALIAS_MAP = {
    "block/wall_post": "block/template_wall_post",
    "block/wall_side": "block/template_wall_side",
    "block/wall_side_tall": "block/template_wall_side_tall",
    "block/fence_gate": "block/template_fence_gate",
    "block/fence_gate_open": "block/template_fence_gate_open",
    "block/fence_gate_wall": "block/template_fence_gate_wall",
    "block/fence_gate_wall_open": "block/template_fence_gate_wall_open",
    "block/trapdoor_bottom": "block/template_trapdoor_bottom",
    "block/trapdoor_top": "block/template_trapdoor_top",
    "block/trapdoor_open": "block/template_trapdoor_open",
    "block/hanging_lantern": "block/lantern_hanging",
}


def normalize_parent(parent_ref: str) -> str:
    """Strip 'minecraft:' prefix from parent references."""
    if parent_ref.startswith("minecraft:"):
        return parent_ref[len("minecraft:"):]
    return parent_ref


def model_path(model_id: str) -> str:
    """Convert a model ID like 'block/cube' to a filesystem path."""
    # model_id is like "block/cube" -> file is at ASSETS_DIR/block/cube.json
    return os.path.join(ASSETS_DIR, model_id + ".json")


def load_model(model_id: str) -> dict | None:
    """Load a model JSON file by its ID."""
    path = model_path(model_id)
    if not os.path.exists(path):
        return None
    with open(path, "r") as f:
        return json.load(f)


def walk_chain(model_id: str) -> list[dict]:
    """
    Walk the parent chain from model_id upward, returning list of model dicts
    from child (index 0) to root ancestor (last index).
    """
    chain = []
    visited = set()
    current = model_id
    while current:
        if current in visited:
            print(f"  WARNING: circular parent reference at {current}", file=sys.stderr)
            break
        visited.add(current)
        data = load_model(current)
        if data is None:
            print(f"  WARNING: could not load model {current}", file=sys.stderr)
            break
        chain.append(data)
        parent = data.get("parent")
        if parent:
            current = normalize_parent(parent)
        else:
            current = None
    return chain


def resolve(model_id: str) -> dict | None:
    """
    Resolve a model ID into its flattened representation:
    - elements from the first ancestor that has them
    - textures merged bottom-up (child overrides parent)
    - ambientocclusion set to false if any model in chain sets it false
    """
    # Use alias if the canonical name doesn't have a file
    actual_id = ALIAS_MAP.get(model_id, model_id)
    chain = walk_chain(actual_id)
    if not chain:
        return None

    # Find elements: first model in chain (child-first) that has them
    elements = None
    for model in chain:
        if "elements" in model:
            elements = model["elements"]
            break

    # Merge textures: child textures override parent textures
    # Walk from root (end of chain) to child (start), so child wins
    textures = {}
    for model in reversed(chain):
        if "textures" in model:
            textures.update(model["textures"])

    # Check ambientocclusion: false if any model sets it to false
    ambient_occlusion = True
    for model in chain:
        if "ambientocclusion" in model and model["ambientocclusion"] is False:
            ambient_occlusion = False
            break

    if elements is None:
        # Models like block/block and block/thin_block have no elements —
        # they are pure display/property models. Include them with empty elements
        # so consumers know the parent exists but defines no geometry.
        elements = []

    result = {
        "textures": textures,
        "elements": elements,
    }
    if not ambient_occlusion:
        result["ambientocclusion"] = False

    return result


def main():
    registry = {}
    missing = []

    for parent_id in KNOWN_PARENTS:
        result = resolve(parent_id)
        if result is None:
            missing.append(parent_id)
            print(f"  MISSING: {parent_id} (file not found)", file=sys.stderr)
            continue
        registry[parent_id] = result

    # Ensure output directory exists
    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)

    with open(OUTPUT_PATH, "w") as f:
        json.dump(registry, f, indent=2)

    print(f"\nGenerated {len(registry)} entries to {OUTPUT_PATH}")
    if missing:
        print(f"Missing files ({len(missing)}): {', '.join(missing)}")

    # Print summary stats
    for parent_id, data in sorted(registry.items()):
        n_elements = len(data.get("elements", []))
        n_textures = len(data.get("textures", {}))
        ao = " (AO=false)" if data.get("ambientocclusion") is False else ""
        print(f"  {parent_id}: {n_elements} elements, {n_textures} textures{ao}")


if __name__ == "__main__":
    main()
