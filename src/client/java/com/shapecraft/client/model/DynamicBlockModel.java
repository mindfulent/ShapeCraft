package com.shapecraft.client.model;

import com.google.gson.*;
import com.shapecraft.client.ShapeCraftClient;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Function;

/**
 * UnbakedModel implementation that parses generated JSON and bakes it using FaceBakery.
 */
public class DynamicBlockModel implements UnbakedModel {

    private final String modelJson;
    private final int slotIndex;
    private final BlockModelRotation blockRotation;

    public DynamicBlockModel(String modelJson, int slotIndex, BlockModelRotation blockRotation) {
        this.modelJson = modelJson;
        this.slotIndex = slotIndex;
        this.blockRotation = blockRotation;
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
        // Use our stored rotation for FACING-based rotation
        ModelState effectiveState = blockRotation != null ? blockRotation : modelState;
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

            if (model.has("elements")) {
                JsonArray elements = model.getAsJsonArray("elements");
                for (JsonElement elemJson : elements) {
                    JsonObject elem = elemJson.getAsJsonObject();

                    Vector3f from = parseVector3f(elem.getAsJsonArray("from"));
                    Vector3f to = parseVector3f(elem.getAsJsonArray("to"));

                    // Element rotation
                    BlockElementRotation elemRotation = null;
                    if (elem.has("rotation")) {
                        elemRotation = parseElementRotation(elem.getAsJsonObject("rotation"));
                    }

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
                                // Auto-calculate UV from element coordinates
                                uv = new BlockFaceUV(null,
                                        faceJson.has("rotation") ? faceJson.get("rotation").getAsInt() : 0);
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
                                    effectiveState, elemRotation, shade);

                            // Place in correct list based on cullface
                            Direction cullface = face.cullForDirection();
                            if (cullface != null) {
                                faceQuads.get(cullface).add(quad);
                            } else {
                                unculledQuads.add(quad);
                            }
                        }
                    }
                }
            }

            boolean ambientOcclusion = !model.has("ambientocclusion") || model.get("ambientocclusion").getAsBoolean();

            return new DynamicBakedModel(faceQuads, unculledQuads, ambientOcclusion, particleSprite);

        } catch (Exception e) {
            ShapeCraftClient.LOGGER.error("[Model] Failed to bake model for slot {}: {}", slotIndex, e.getMessage());
            // Return null — Fabric will use missing model
            return null;
        }
    }

    private TextureAtlasSprite getSprite(String varName, Map<String, String> textureMap,
                                         Function<Material, TextureAtlasSprite> spriteGetter) {
        String path = textureMap.get(varName);
        if (path == null) return null;
        return resolveSprite(path, textureMap, spriteGetter);
    }

    private TextureAtlasSprite resolveSprite(String ref, Map<String, String> textureMap,
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
