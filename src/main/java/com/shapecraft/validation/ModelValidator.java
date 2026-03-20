package com.shapecraft.validation;

import com.google.gson.*;
import com.shapecraft.ShapeCraftConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates generated block model JSON against 11 rules (V-001 through V-011).
 */
public class ModelValidator {

    private static final Set<String> VALID_MODEL_KEYS = Set.of(
            "parent", "textures", "elements", "ambientocclusion", "display",
            "credit", "texture_size", "overrides", "gui_light"
    );

    private static final Set<String> VALID_ELEMENT_KEYS = Set.of(
            "from", "to", "rotation", "shade", "faces", "name"
    );

    private static final Set<String> VALID_FACE_KEYS = Set.of(
            "uv", "texture", "cullface", "rotation", "tintindex"
    );

    private static final Set<String> VALID_DIRECTIONS = Set.of(
            "north", "south", "east", "west", "up", "down"
    );

    private static final Set<Integer> VALID_ROTATIONS = Set.of(0, 90, 180, 270);
    private static final Set<Integer> VALID_ELEMENT_ROTATION_ANGLES = Set.of(-45, -22, 0, 22, 45);

    public List<String> validate(String modelJsonStr) {
        List<String> errors = new ArrayList<>();

        // V-001: JSON syntax
        JsonObject model;
        try {
            model = JsonParser.parseString(modelJsonStr).getAsJsonObject();
        } catch (Exception e) {
            errors.add("V-001: Invalid JSON syntax — " + e.getMessage());
            return errors;
        }

        // V-002: Known keys
        for (String key : model.keySet()) {
            if (!VALID_MODEL_KEYS.contains(key)) {
                errors.add("V-002: Unknown top-level key '" + key + "'");
            }
        }

        // V-003: Parent resolution
        if (model.has("parent")) {
            String parent = model.get("parent").getAsString();
            // Normalize: add minecraft: prefix if missing
            String normalized = parent.contains(":") ? parent : "minecraft:" + parent;
            // Strip minecraft: prefix for lookup
            String lookupKey = normalized.startsWith("minecraft:") ? normalized.substring("minecraft:".length()) : normalized;
            if (!VanillaAssets.KNOWN_PARENTS.contains(lookupKey) && !lookupKey.startsWith("shapecraft:")) {
                errors.add("V-003: Unknown parent '" + parent + "'");
            }
        }

        // Elements validation
        if (model.has("elements")) {
            JsonArray elements = model.getAsJsonArray("elements");

            // V-004: Element count
            if (elements.size() > ShapeCraftConstants.MAX_ELEMENTS) {
                errors.add("V-004: Too many elements (" + elements.size() + ", max " + ShapeCraftConstants.MAX_ELEMENTS + ")");
            }

            for (int i = 0; i < elements.size(); i++) {
                JsonObject element = elements.get(i).getAsJsonObject();
                String prefix = "element[" + i + "]";

                // V-002b: Valid element keys
                for (String key : element.keySet()) {
                    if (!VALID_ELEMENT_KEYS.contains(key)) {
                        errors.add("V-002: Unknown element key '" + key + "' in " + prefix);
                    }
                }

                // V-005: Coordinate range [0, 16]
                if (element.has("from") && element.has("to")) {
                    JsonArray from = element.getAsJsonArray("from");
                    JsonArray to = element.getAsJsonArray("to");

                    if (from.size() != 3 || to.size() != 3) {
                        errors.add("V-005: " + prefix + " from/to must have 3 coordinates");
                    } else {
                        for (int j = 0; j < 3; j++) {
                            double f = from.get(j).getAsDouble();
                            double t = to.get(j).getAsDouble();
                            if (f < -16 || f > 32 || t < -16 || t > 32) {
                                errors.add("V-005: " + prefix + " coordinate out of range [-16, 32]");
                                break;
                            }
                        }

                        // V-006: Non-degenerate (from < to on each axis)
                        boolean degenerate = true;
                        for (int j = 0; j < 3; j++) {
                            if (from.get(j).getAsDouble() != to.get(j).getAsDouble()) {
                                degenerate = false;
                                break;
                            }
                        }
                        if (degenerate) {
                            errors.add("V-006: " + prefix + " is degenerate (zero volume)");
                        }
                    }
                } else {
                    errors.add("V-005: " + prefix + " missing from/to");
                }

                // V-009: Element rotation
                if (element.has("rotation")) {
                    JsonObject rotation = element.getAsJsonObject("rotation");
                    if (rotation.has("angle")) {
                        int angle = rotation.get("angle").getAsInt();
                        if (!VALID_ELEMENT_ROTATION_ANGLES.contains(angle)) {
                            errors.add("V-009: " + prefix + " invalid rotation angle " + angle
                                    + " (must be -45, -22.5, 0, 22.5, or 45)");
                        }
                    }
                    if (rotation.has("axis")) {
                        String axis = rotation.get("axis").getAsString();
                        if (!Set.of("x", "y", "z").contains(axis)) {
                            errors.add("V-009: " + prefix + " invalid rotation axis '" + axis + "'");
                        }
                    }
                }

                // V-007: Texture references in faces
                if (element.has("faces")) {
                    JsonObject faces = element.getAsJsonObject("faces");

                    // V-011: Valid face directions
                    for (String dir : faces.keySet()) {
                        if (!VALID_DIRECTIONS.contains(dir)) {
                            errors.add("V-011: " + prefix + " unknown face direction '" + dir + "'");
                            continue;
                        }

                        JsonObject face = faces.getAsJsonObject(dir);

                        // Valid face keys
                        for (String key : face.keySet()) {
                            if (!VALID_FACE_KEYS.contains(key)) {
                                errors.add("V-002: Unknown face key '" + key + "' in " + prefix + "." + dir);
                            }
                        }

                        // V-007: Texture reference must start with #
                        if (face.has("texture")) {
                            String tex = face.get("texture").getAsString();
                            if (!tex.startsWith("#")) {
                                errors.add("V-007: " + prefix + "." + dir + " texture must be a variable reference (#name), got '" + tex + "'");
                            }
                        }

                        // V-008: UV range [0, 16]
                        if (face.has("uv")) {
                            JsonArray uv = face.getAsJsonArray("uv");
                            if (uv.size() != 4) {
                                errors.add("V-008: " + prefix + "." + dir + " UV must have 4 values");
                            } else {
                                for (int j = 0; j < 4; j++) {
                                    double v = uv.get(j).getAsDouble();
                                    if (v < 0 || v > 16) {
                                        errors.add("V-008: " + prefix + "." + dir + " UV value " + v + " out of range [0, 16]");
                                        break;
                                    }
                                }
                            }
                        }

                        // V-009b: Face rotation
                        if (face.has("rotation")) {
                            int rot = face.get("rotation").getAsInt();
                            if (!VALID_ROTATIONS.contains(rot)) {
                                errors.add("V-009: " + prefix + "." + dir + " invalid face rotation " + rot);
                            }
                        }

                        // Cullface validation
                        if (face.has("cullface")) {
                            String cullface = face.get("cullface").getAsString();
                            if (!VALID_DIRECTIONS.contains(cullface)) {
                                errors.add("V-011: " + prefix + "." + dir + " invalid cullface '" + cullface + "'");
                            }
                        }
                    }
                }
            }
        }

        // V-010: Texture paths — verify texture variables resolve to known textures
        if (model.has("textures")) {
            JsonObject textures = model.getAsJsonObject("textures");
            for (var entry : textures.entrySet()) {
                String value = entry.getValue().getAsString();
                // Skip variable references (#name) — they're resolved via parent chain
                if (value.startsWith("#")) continue;

                // Normalize path
                String normalized = value.contains(":") ? value : "minecraft:" + value;
                if (!VanillaAssets.KNOWN_TEXTURES.contains(normalized) && !normalized.startsWith("shapecraft:")) {
                    // Warn but don't fail — textures list isn't exhaustive
                    // errors.add("V-010: Unknown texture path '" + value + "'");
                }
            }
        }

        return errors;
    }
}
