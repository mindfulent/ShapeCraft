package com.shapecraft.client.model;

import com.google.gson.*;
import com.shapecraft.client.ShapeCraftClient;
import com.shapecraft.validation.ParentResolver;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Vector3f;

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

    public DynamicBlockModel(int slotIndex, Direction facing) {
        this.slotIndex = slotIndex;
        this.facing = facing;
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
        if (data != null && data.modelJson() != null && !data.modelJson().isEmpty()) {
            BlockModelRotation rotation = ShapeCraftModelPlugin.getBlockRotation(facing);
            BakedModelCache.FacingQuads fq = bakeQuads(data.modelJson(), slotIndex, rotation, spriteGetter);
            if (fq != null) {
                BakedModelCache.mergeFacing(slotIndex, facing, fq);
            }
        }
        return new DynamicBakedModel(slotIndex, facing);
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

            // Debug logging for cross/rotated model diagnosis
            LOGGER.info("[Model] Slot {} | parent={} usedParent={} | elementCount={} | rotation={}",
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

                    LOGGER.info("[Model] Slot {} elem[{}] from=[{},{},{}] to=[{},{},{}] elemRot={}",
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
                                    LOGGER.info("[Model] Slot {} elem[0].{} computeDefaultUV=[{},{},{},{}]",
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
                                    LOGGER.info("[Model] Slot {} quad vertex[{}] pos=[{},{},{}]",
                                            slotIndex, vi, vx, vy, vz);
                                }
                            }

                            // Place in correct list based on cullface
                            Direction cullface = face.cullForDirection();
                            if (cullface != null) {
                                faceQuads.get(cullface).add(quad);
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
            return null; // Unresolved variable
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
     * Compute default UV coordinates from element bounds for a given face direction.
     * Matches vanilla FaceBakery's auto-UV calculation.
     */
    private static float[] computeDefaultUV(Vector3f from, Vector3f to, Direction dir) {
        return switch (dir) {
            case DOWN  -> new float[]{from.x(), 16 - to.z(), to.x(), 16 - from.z()};
            case UP    -> new float[]{from.x(), from.z(), to.x(), to.z()};
            case NORTH -> new float[]{16 - to.x(), 16 - to.y(), 16 - from.x(), 16 - from.y()};
            case SOUTH -> new float[]{from.x(), 16 - to.y(), to.x(), 16 - from.y()};
            case WEST  -> new float[]{from.z(), 16 - to.y(), to.z(), 16 - from.y()};
            case EAST  -> new float[]{16 - to.z(), 16 - to.y(), 16 - from.z(), 16 - from.y()};
        };
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
