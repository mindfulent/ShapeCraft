package com.shapecraft.client.model;

import com.google.gson.*;
import com.shapecraft.block.BlockHalf;
import com.shapecraft.client.ShapeCraftClient;
import com.shapecraft.validation.ParentResolver;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Vector3f;
import org.joml.Vector4f;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * UnbakedModel implementation that parses generated JSON and bakes it using FaceBakery.
 * bake() writes to BakedModelCache using the engine's spriteGetter (guaranteed correct after atlas stitching).
 * RuntimeModelBaker also writes to BakedModelCache for runtime hot-swap.
 */
public class DynamicBlockModel implements UnbakedModel {

    private static final Logger LOGGER = LoggerFactory.getLogger("ShapeCraft/Model");

    private final int slotIndex;
    private final Direction facing;
    private final BlockHalf half;
    private final boolean open;

    public DynamicBlockModel(int slotIndex, Direction facing, BlockHalf half, boolean open) {
        this.slotIndex = slotIndex;
        this.facing = facing;
        this.half = half;
        this.open = open;
    }

    @Override
    public Collection<ResourceLocation> getDependencies() {
        return Collections.emptyList();
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> resolver) {
        // No parent chain to resolve — we handle everything inline
    }

    @Override
    public BakedModel bake(ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter,
                           ModelState modelState) {
        // Use the engine's spriteGetter to bake quads — guaranteed correct after atlas stitching.
        // This handles resource reloads: stale quads were cleared in onInitializeModelLoader().
        ModelCache.ModelData data = ModelCache.get(slotIndex);
        if (data != null) {
            // Select model JSON based on half and open state.
            // For doors: closed model JSON used for both states — open applies X↔Z swap.
            // For non-doors: use open variant JSON if available, fall back to closed.
            String json;
            boolean isDoor = data.isDoor();
            if (open && !isDoor) {
                // Non-door open state: use open variant, falling back to closed
                json = (half == BlockHalf.UPPER
                        && data.upperModelJsonOpen() != null && !data.upperModelJsonOpen().isEmpty())
                        ? data.upperModelJsonOpen()
                        : (data.modelJsonOpen() != null && !data.modelJsonOpen().isEmpty())
                            ? data.modelJsonOpen()
                            : null;
                // Fall back to closed model if no open variant
                if (json == null || json.isEmpty()) {
                    json = (half == BlockHalf.UPPER && data.upperModelJson() != null && !data.upperModelJson().isEmpty())
                            ? data.upperModelJson()
                            : data.modelJson();
                }
            } else {
                // Closed state, OR door open state (reuse closed model)
                json = (half == BlockHalf.UPPER && data.upperModelJson() != null && !data.upperModelJson().isEmpty())
                        ? data.upperModelJson()
                        : data.modelJson();
            }
            if (json != null && !json.isEmpty()) {
                // For doors: normalize centered panels to block edge, then X↔Z swap for open state
                boolean thinAlongZ = true;
                if (isDoor) {
                    NormalizedDoor norm = normalizeDoorPanel(json);
                    json = norm.json();
                    thinAlongZ = norm.thinAlongZ();
                    if (open) {
                        json = transformDoorOpen(json);
                    }
                }
                BlockModelRotation rotation;
                if (isDoor) {
                    rotation = ShapeCraftModelPlugin.getDoorRotation(facing, open, thinAlongZ);
                } else {
                    rotation = ShapeCraftModelPlugin.getBlockRotation(facing);
                }
                BakedModelCache.FacingQuads fq = bakeQuads(json, slotIndex, rotation, spriteGetter);
                if (fq != null) {
                    BakedModelCache.mergeFacing(slotIndex, half, open, facing, fq);
                }
            }
        }
        return new DynamicBakedModel(slotIndex, facing, half, open);
    }

    /**
     * Result of normalizing a door panel: the translated JSON and which axis is thin.
     */
    public record NormalizedDoor(String json, boolean thinAlongZ) {}

    /**
     * Normalize a door model so the thin axis is flush with the far edge of the block (16-side).
     * Claude often generates centered panels (e.g., Z=7..9); this snaps them to the edge
     * so that BlockModelRotation can place them on the correct block face for each FACING.
     */
    public static NormalizedDoor normalizeDoorPanel(String modelJson) {
        try {
            JsonObject model = JsonParser.parseString(modelJson).getAsJsonObject();
            if (!model.has("elements")) return new NormalizedDoor(modelJson, true);

            JsonArray elements = model.getAsJsonArray("elements");
            if (elements.isEmpty()) return new NormalizedDoor(modelJson, true);

            // Find bounding box across all elements (X and Z only)
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (JsonElement elemJson : elements) {
                JsonObject elem = elemJson.getAsJsonObject();
                JsonArray from = elem.getAsJsonArray("from");
                JsonArray to = elem.getAsJsonArray("to");
                minX = Math.min(minX, from.get(0).getAsFloat());
                maxX = Math.max(maxX, to.get(0).getAsFloat());
                minZ = Math.min(minZ, from.get(2).getAsFloat());
                maxZ = Math.max(maxZ, to.get(2).getAsFloat());
            }

            float extentX = maxX - minX;
            float extentZ = maxZ - minZ;

            // Determine thin axis (ignore Y — doors are always full height)
            boolean thinZ = extentZ <= extentX;
            // Default to thin-Z if equal (square panel)

            float offset;
            int axis; // 0=X, 2=Z
            if (thinZ) {
                offset = 16.0f - maxZ;
                axis = 2;
            } else {
                offset = 16.0f - maxX;
                axis = 0;
            }

            if (Math.abs(offset) < 0.01f) return new NormalizedDoor(modelJson, thinZ);

            // Apply translation to all elements
            JsonArray newElements = new JsonArray();
            for (JsonElement elemJson : elements) {
                JsonObject elem = elemJson.getAsJsonObject().deepCopy();
                JsonArray from = elem.getAsJsonArray("from");
                JsonArray to = elem.getAsJsonArray("to");
                from.set(axis, new JsonPrimitive(from.get(axis).getAsFloat() + offset));
                to.set(axis, new JsonPrimitive(to.get(axis).getAsFloat() + offset));

                if (elem.has("rotation")) {
                    JsonObject rot = elem.getAsJsonObject("rotation");
                    if (rot.has("origin")) {
                        JsonArray origin = rot.getAsJsonArray("origin");
                        origin.set(axis, new JsonPrimitive(origin.get(axis).getAsFloat() + offset));
                    }
                }

                newElements.add(elem);
            }
            model.add("elements", newElements);

            LOGGER.debug("[Model] Normalized door panel: offset={} along {} axis (thinZ={})",
                    String.format("%.1f", offset), thinZ ? "Z" : "X", thinZ);

            return new NormalizedDoor(model.toString(), thinZ);
        } catch (Exception e) {
            LOGGER.warn("[Model] Failed to normalize door panel: {}", e.getMessage());
            return new NormalizedDoor(modelJson, true);
        }
    }

    /**
     * Transform door model JSON for open state by swapping X↔Z coordinates and remapping faces.
     * This keeps the hinge corner at (0,*,0) fixed while rotating the panel 90° around it.
     * Works correctly for multi-element doors (iron bars, window panes, decorative panels)
     * because it transforms each element's coordinates individually.
     */
    public static String transformDoorOpen(String modelJson) {
        try {
            JsonObject model = JsonParser.parseString(modelJson).getAsJsonObject();
            if (!model.has("elements")) return modelJson;

            JsonArray elements = model.getAsJsonArray("elements");
            JsonArray newElements = new JsonArray();

            for (JsonElement elemJson : elements) {
                JsonObject elem = elemJson.getAsJsonObject().deepCopy();

                // Swap X↔Z in from/to
                JsonArray from = elem.getAsJsonArray("from");
                JsonArray to = elem.getAsJsonArray("to");
                float fromX = from.get(0).getAsFloat(), fromZ = from.get(2).getAsFloat();
                float toX = to.get(0).getAsFloat(), toZ = to.get(2).getAsFloat();
                from.set(0, new JsonPrimitive(fromZ));
                from.set(2, new JsonPrimitive(fromX));
                to.set(0, new JsonPrimitive(toZ));
                to.set(2, new JsonPrimitive(toX));

                // Swap element rotation axis if present
                if (elem.has("rotation")) {
                    JsonObject rot = elem.getAsJsonObject("rotation");
                    if (rot.has("axis")) {
                        String axis = rot.get("axis").getAsString();
                        if ("x".equals(axis)) rot.addProperty("axis", "z");
                        else if ("z".equals(axis)) rot.addProperty("axis", "x");
                    }
                    if (rot.has("origin")) {
                        JsonArray origin = rot.getAsJsonArray("origin");
                        float ox = origin.get(0).getAsFloat(), oz = origin.get(2).getAsFloat();
                        origin.set(0, new JsonPrimitive(oz));
                        origin.set(2, new JsonPrimitive(ox));
                    }
                }

                // Remap face keys: north↔west, south↔east
                if (elem.has("faces")) {
                    JsonObject oldFaces = elem.getAsJsonObject("faces");
                    JsonObject newFaces = new JsonObject();
                    for (var entry : oldFaces.entrySet()) {
                        String key = entry.getKey();
                        String newKey = switch (key) {
                            case "north" -> "west";
                            case "west" -> "north";
                            case "south" -> "east";
                            case "east" -> "south";
                            default -> key; // up, down unchanged
                        };
                        newFaces.add(newKey, entry.getValue());
                    }
                    elem.add("faces", newFaces);
                }

                newElements.add(elem);
            }

            model.add("elements", newElements);
            return model.toString();
        } catch (Exception e) {
            LOGGER.warn("[Model] Failed to transform door open state: {}", e.getMessage());
            return modelJson;
        }
    }

    /**
     * Bakes model JSON into quads for a single facing variant.
     * Called from both the reload path (bake()) and runtime hot-swap (RuntimeModelBaker).
     */
    public static BakedModelCache.FacingQuads bakeQuads(String modelJson, int slotIndex,
                                                         BlockModelRotation rotation,
                                                         Function<Material, TextureAtlasSprite> spriteGetter) {
        try {
            JsonObject model = JsonParser.parseString(modelJson).getAsJsonObject();

            // Resolve texture variables
            Map<String, String> textureMap = new HashMap<>();
            if (model.has("textures")) {
                JsonObject textures = model.getAsJsonObject("textures");
                for (var entry : textures.entrySet()) {
                    textureMap.put(entry.getKey(), entry.getValue().getAsString());
                }
            }

            // Build quads from elements
            Map<Direction, List<BakedQuad>> faceQuads = new EnumMap<>(Direction.class);
            List<BakedQuad> unculledQuads = new ArrayList<>();
            for (Direction dir : Direction.values()) {
                faceQuads.put(dir, new ArrayList<>());
            }

            TextureAtlasSprite particleSprite = getSprite("particle", textureMap, spriteGetter);
            if (particleSprite == null) {
                // Fallback: use first texture found
                for (String texVar : textureMap.values()) {
                    particleSprite = resolveSprite(texVar, textureMap, spriteGetter);
                    if (particleSprite != null) break;
                }
            }
            if (particleSprite == null) {
                // Ultimate fallback: missing texture
                particleSprite = spriteGetter.apply(new Material(
                        InventoryMenu.BLOCK_ATLAS,
                        ResourceLocation.withDefaultNamespace("missingno")));
            }

            FaceBakery faceBakery = new FaceBakery();

            // Resolve elements: inline first, then parent fallback
            JsonArray elements;
            boolean usedParent = false;
            String parentName = model.has("parent") ? model.get("parent").getAsString() : null;
            if (model.has("elements")) {
                elements = model.getAsJsonArray("elements");
            } else if (parentName != null) {
                ParentResolver.ResolvedParent resolved = ParentResolver.resolve(parentName);
                if (resolved == null) {
                    ShapeCraftClient.LOGGER.warn("[Model] Unknown parent '{}' for slot {}",
                            parentName, slotIndex);
                    return null;
                }
                elements = resolved.elements();
                usedParent = true;
                // Merge parent textures (parent provides defaults, child overrides)
                for (var entry : resolved.textures().entrySet()) {
                    textureMap.putIfAbsent(entry.getKey(), entry.getValue());
                }
            } else {
                return null; // No elements and no parent
            }

            // Always merge parent textures — even when model has inline elements,
            // parent textures provide defaults for unresolved #variable references
            if (parentName != null && !usedParent) {
                ParentResolver.ResolvedParent resolved = ParentResolver.resolve(parentName);
                if (resolved != null) {
                    for (var entry : resolved.textures().entrySet()) {
                        textureMap.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
            }

            // Debug logging for cross/rotated model diagnosis
            LOGGER.debug("[Model] Slot {} | parent={} usedParent={} | elementCount={} | rotation={}",
                    slotIndex, parentName, usedParent, elements.size(), rotation);

            {
                int elemIdx = 0;
                for (JsonElement elemJson : elements) {
                    JsonObject elem = elemJson.getAsJsonObject();

                    Vector3f from = parseVector3f(elem.getAsJsonArray("from"));
                    Vector3f to = parseVector3f(elem.getAsJsonArray("to"));

                    // Element rotation
                    BlockElementRotation elemRotation = null;
                    if (elem.has("rotation")) {
                        elemRotation = parseElementRotation(elem.getAsJsonObject("rotation"));
                    }

                    LOGGER.debug("[Model] Slot {} elem[{}] from=[{},{},{}] to=[{},{},{}] elemRot={}",
                            slotIndex, elemIdx,
                            from.x(), from.y(), from.z(), to.x(), to.y(), to.z(),
                            elemRotation != null
                                    ? String.format("axis=%s angle=%.1f origin=[%.1f,%.1f,%.1f] rescale=%b",
                                        elemRotation.axis(), elemRotation.angle(),
                                        elemRotation.origin().x(), elemRotation.origin().y(), elemRotation.origin().z(),
                                        elemRotation.rescale())
                                    : "none");

                    boolean shade = !elem.has("shade") || elem.get("shade").getAsBoolean();

                    if (elem.has("faces")) {
                        JsonObject faces = elem.getAsJsonObject("faces");
                        for (var faceEntry : faces.entrySet()) {
                            Direction dir = Direction.byName(faceEntry.getKey());
                            if (dir == null) continue;

                            JsonObject faceJson = faceEntry.getValue().getAsJsonObject();

                            // Texture
                            String texRef = faceJson.has("texture")
                                    ? faceJson.get("texture").getAsString()
                                    : "#all";
                            TextureAtlasSprite sprite = resolveSprite(texRef, textureMap, spriteGetter);
                            if (sprite == null) sprite = particleSprite;

                            // UV
                            BlockFaceUV uv;
                            if (faceJson.has("uv")) {
                                JsonArray uvArr = faceJson.getAsJsonArray("uv");
                                uv = new BlockFaceUV(new float[]{
                                        uvArr.get(0).getAsFloat(),
                                        uvArr.get(1).getAsFloat(),
                                        uvArr.get(2).getAsFloat(),
                                        uvArr.get(3).getAsFloat()
                                }, faceJson.has("rotation") ? faceJson.get("rotation").getAsInt() : 0);
                            } else {
                                // Auto-calculate UV from element coordinates (vanilla doesn't accept null uvs)
                                float[] defaultUV = computeDefaultUV(from, to, dir);
                                uv = new BlockFaceUV(defaultUV,
                                        faceJson.has("rotation") ? faceJson.get("rotation").getAsInt() : 0);
                                if (elemIdx == 0) {
                                    LOGGER.debug("[Model] Slot {} elem[0].{} computeDefaultUV=[{},{},{},{}]",
                                            slotIndex, dir.getName(),
                                            defaultUV[0], defaultUV[1], defaultUV[2], defaultUV[3]);
                                }
                            }

                            // Tint index
                            int tintIndex = faceJson.has("tintindex")
                                    ? faceJson.get("tintindex").getAsInt() : -1;

                            BlockElementFace face = new BlockElementFace(
                                    faceJson.has("cullface") ? Direction.byName(faceJson.get("cullface").getAsString()) : null,
                                    tintIndex,
                                    texRef,
                                    uv);

                            // Apply Y rotation for directional variants via ModelState
                            BakedQuad quad = faceBakery.bakeQuad(from, to, face, sprite, dir,
                                    rotation, elemRotation, shade);

                            // Log first quad's vertex positions for diagnosis
                            if (elemIdx == 0 && dir == Direction.UP) {
                                int[] verts = quad.getVertices();
                                // Each vertex: 8 ints (x,y,z as float bits, then color, u, v, light, normal)
                                for (int vi = 0; vi < 4; vi++) {
                                    float vx = Float.intBitsToFloat(verts[vi * 8]);
                                    float vy = Float.intBitsToFloat(verts[vi * 8 + 1]);
                                    float vz = Float.intBitsToFloat(verts[vi * 8 + 2]);
                                    LOGGER.debug("[Model] Slot {} quad vertex[{}] pos=[{},{},{}]",
                                            slotIndex, vi, vx, vy, vz);
                                }
                            }

                            // Place in correct list based on cullface
                            // Rotate cullface to match geometry rotation — FaceBakery rotates
                            // vertex positions but cullForDirection() returns the pre-rotation
                            // direction from JSON, causing quads to land in wrong buckets
                            Direction cullface = face.cullForDirection();
                            if (cullface != null) {
                                Direction rotatedCullface = rotateCullface(cullface, rotation);
                                faceQuads.get(rotatedCullface).add(quad);
                            } else {
                                unculledQuads.add(quad);
                            }
                        }
                    }
                    elemIdx++;
                }
            }

            // Ambient occlusion: child setting takes priority, then resolved parent, then default true
            boolean ambientOcclusion;
            if (model.has("ambientocclusion")) {
                ambientOcclusion = model.get("ambientocclusion").getAsBoolean();
            } else if (model.has("parent")) {
                ParentResolver.ResolvedParent resolved = ParentResolver.resolve(
                        model.get("parent").getAsString());
                ambientOcclusion = resolved == null || resolved.ambientOcclusion();
            } else {
                ambientOcclusion = true;
            }

            return new BakedModelCache.FacingQuads(faceQuads, unculledQuads, ambientOcclusion, particleSprite);

        } catch (Exception e) {
            ShapeCraftClient.LOGGER.error("[Model] Failed to bake quads for slot {}: {}", slotIndex, e.getMessage());
            return null;
        }
    }

    private static TextureAtlasSprite getSprite(String varName, Map<String, String> textureMap,
                                                Function<Material, TextureAtlasSprite> spriteGetter) {
        String path = textureMap.get(varName);
        if (path == null) return null;
        return resolveSprite(path, textureMap, spriteGetter);
    }

    private static TextureAtlasSprite resolveSprite(String ref, Map<String, String> textureMap,
                                                    Function<Material, TextureAtlasSprite> spriteGetter) {
        // Resolve variable references (#name)
        String resolved = ref;
        int depth = 0;
        while (resolved.startsWith("#") && depth < 10) {
            String varName = resolved.substring(1);
            String mapped = textureMap.get(varName);
            if (mapped == null) break;
            resolved = mapped;
            depth++;
        }

        if (resolved.startsWith("#")) {
            LOGGER.warn("[Model] Unresolved texture variable '{}' — no mapping found in texture map", resolved);
            return null;
        }

        // Parse texture path
        ResourceLocation texPath;
        if (resolved.contains(":")) {
            texPath = ResourceLocation.parse(resolved);
        } else {
            texPath = ResourceLocation.withDefaultNamespace(resolved);
        }

        return spriteGetter.apply(new Material(InventoryMenu.BLOCK_ATLAS, texPath));
    }

    private static Vector3f parseVector3f(JsonArray arr) {
        return new Vector3f(
                arr.get(0).getAsFloat(),
                arr.get(1).getAsFloat(),
                arr.get(2).getAsFloat());
    }

    /**
     * Compute default UV coordinates from element dimensions for a given face direction.
     * Uses origin-based mapping [0, 0, faceWidth, faceHeight] so every face samples the
     * texture from the same starting point, giving consistent appearance across elements.
     */
    private static float[] computeDefaultUV(Vector3f from, Vector3f to, Direction dir) {
        float w, h;
        switch (dir) {
            case UP, DOWN -> { w = to.x() - from.x(); h = to.z() - from.z(); }
            case NORTH, SOUTH -> { w = to.x() - from.x(); h = to.y() - from.y(); }
            case WEST, EAST -> { w = to.z() - from.z(); h = to.y() - from.y(); }
            default -> { w = 16; h = 16; }
        }
        return new float[]{0, 0, w, h};
    }

    /**
     * Rotate a cullface direction by the same transformation FaceBakery applies to vertices.
     * Without this, rotated quads land in the wrong face bucket and the renderer can't find them.
     */
    private static Direction rotateCullface(Direction original, BlockModelRotation rotation) {
        if (rotation == BlockModelRotation.X0_Y0) return original;
        Vector4f vec = new Vector4f(
                original.getStepX(), original.getStepY(), original.getStepZ(), 0);
        rotation.getRotation().getMatrix().transform(vec);
        return Direction.getNearest(vec.x(), vec.y(), vec.z());
    }

    private static BlockElementRotation parseElementRotation(JsonObject json) {
        Vector3f origin = json.has("origin")
                ? parseVector3f(json.getAsJsonArray("origin"))
                : new Vector3f(8, 8, 8);
        Direction.Axis axis = Direction.Axis.byName(json.get("axis").getAsString());
        float angle = json.get("angle").getAsFloat();
        boolean rescale = json.has("rescale") && json.get("rescale").getAsBoolean();
        return new BlockElementRotation(origin, axis, angle, rescale);
    }

}
