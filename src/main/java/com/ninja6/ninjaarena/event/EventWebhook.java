package com.ninja6.ninjaarena.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class EventWebhook {

    @FunctionalInterface
    public interface Client {
        CompletionStage<Integer> send(HttpRequest request);
    }

    private final Client client;
    private final Consumer<String> warning;

    public EventWebhook(Client client, Consumer<String> warning) {
        this.client = client;
        this.warning = warning;
    }

    public static EventWebhook create(Consumer<String> warning) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return new EventWebhook(request -> http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(HttpResponse::statusCode), warning);
    }

    public void post(URI url, String message) {
        if (url == null) {
            return;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("content", message);
            JsonObject allowed = new JsonObject();
            allowed.add("parse", new JsonArray());
            payload.add("allowed_mentions", allowed);
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            client.send(request).whenComplete((status, error) -> {
                if (error != null || status < 200 || status >= 300) {
                    warning.accept("Arena event webhook delivery failed.");
                }
            });
        } catch (RuntimeException ignored) {
            warning.accept("Arena event webhook delivery failed.");
        }
    }
}
