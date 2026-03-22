package com.shapecraft.generation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class BackendClient {

    private final HttpClient httpClient;
    private String backendUrl;
    private String authToken;

    public BackendClient(String backendUrl) {
        this.backendUrl = backendUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setAuthToken(String token) {
        this.authToken = token;
    }

    public void setBackendUrl(String url) {
        this.backendUrl = url;
    }

    public CompletableFuture<GenerationResult> generate(GenerationRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("description", request.description());
        body.addProperty("player_uuid", request.playerUuid().toString());
        body.addProperty("player_name", request.playerName());
        body.addProperty("request_id", request.requestId());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(backendUrl + "/shapecraft/generate"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (authToken != null ? authToken : ""))
                .timeout(Duration.ofSeconds(ShapeCraftConstants.GENERATION_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        String errorMsg = "Backend returned " + response.statusCode();
                        try {
                            JsonObject errBody = JsonParser.parseString(response.body()).getAsJsonObject();
                            if (errBody.has("error")) {
                                errorMsg = errBody.get("error").getAsString();
                            }
                        } catch (Exception ignored) {}
                        throw new BackendHttpException(response.statusCode(), errorMsg);
                    }

                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    return new GenerationResult(
                            json.has("generation_id") ? json.get("generation_id").getAsString() : "",
                            json.get("model_json").getAsString(),
                            json.has("upper_model_json") ? json.get("upper_model_json").getAsString() : "",
                            json.has("model_json_open") ? json.get("model_json_open").getAsString() : "",
                            json.has("upper_model_json_open") ? json.get("upper_model_json_open").getAsString() : "",
                            json.has("block_type") ? json.get("block_type").getAsString() : "",
                            json.get("display_name").getAsString(),
                            json.has("texture_tints") ? json.get("texture_tints").getAsString() : "",
                            json.has("input_tokens") ? json.get("input_tokens").getAsInt() : 0,
                            json.has("output_tokens") ? json.get("output_tokens").getAsInt() : 0
                    );
                });
    }

    public CompletableFuture<JsonObject> healthCheck() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(backendUrl + "/shapecraft/"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> JsonParser.parseString(response.body()).getAsJsonObject());
    }
}
