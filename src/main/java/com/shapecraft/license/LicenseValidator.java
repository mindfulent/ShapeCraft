package com.shapecraft.license;

import com.google.gson.*;
import com.shapecraft.ShapeCraft;
import net.minecraft.Util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class LicenseValidator {
    private final String apiBaseUrl;
    private volatile HttpClient httpClient;
    private final Gson gson = new GsonBuilder().create();

    public LicenseValidator(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    private HttpClient getHttpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .executor(Util.backgroundExecutor())
                            .build();
                }
            }
        }
        return httpClient;
    }

    public record TrialResponse(String licenseKey, int generationCredits) {}
    public record ActivateResponse(String licenseKey, String expiresAt) {}
    public record ValidateResponse(boolean valid, String state, String expiresAt, int generationsRemaining) {}

    public CompletableFuture<TrialResponse> provisionTrial(String serverId, String serverName, String modVersion) {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        body.addProperty("serverName", serverName);
        body.addProperty("minecraftVersion", "1.21.1");
        body.addProperty("modVersion", modVersion);

        return executePost("/shapecraft/trial", body, null).thenApply(json -> {
            JsonObject obj = json.getAsJsonObject();
            return new TrialResponse(
                    obj.get("licenseKey").getAsString(),
                    obj.get("generationCredits").getAsInt()
            );
        });
    }

    public CompletableFuture<ActivateResponse> activate(String code, String serverId) {
        JsonObject body = new JsonObject();
        body.addProperty("activationCode", code);
        body.addProperty("serverId", serverId);

        return executePost("/shapecraft/activate", body, null).thenApply(json -> {
            JsonObject obj = json.getAsJsonObject();
            return new ActivateResponse(
                    obj.get("licenseKey").getAsString(),
                    obj.has("expiresAt") && !obj.get("expiresAt").isJsonNull()
                            ? obj.get("expiresAt").getAsString() : null
            );
        });
    }

    public CompletableFuture<ValidateResponse> validate(String licenseKey, String serverId, String modVersion,
                                                         int totalPlayers) {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        body.addProperty("modVersion", modVersion);
        body.addProperty("totalPlayers", totalPlayers);

        return executePost("/shapecraft/validate", body, licenseKey).thenApply(json -> {
            JsonObject obj = json.getAsJsonObject();
            return new ValidateResponse(
                    obj.get("valid").getAsBoolean(),
                    obj.get("state").getAsString(),
                    obj.has("expiresAt") && !obj.get("expiresAt").isJsonNull()
                            ? obj.get("expiresAt").getAsString() : null,
                    obj.has("generationsRemaining") ? obj.get("generationsRemaining").getAsInt() : -1
            );
        });
    }

    private CompletableFuture<JsonElement> executePost(String path, JsonObject body, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));

        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }

        return getHttpClient().sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        ShapeCraft.LOGGER.error("License API error {}: {}", response.statusCode(), response.body());
                        throw new RuntimeException("License API error " + response.statusCode());
                    }
                    return JsonParser.parseString(response.body());
                });
    }
}
