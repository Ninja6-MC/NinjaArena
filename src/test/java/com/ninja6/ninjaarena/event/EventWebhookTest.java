package com.ninja6.ninjaarena.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class EventWebhookTest {

    @Test
    void disabledDeliveryDoesNotUseClientAndSuccessIsQuiet() {
        List<HttpRequest> requests = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        EventWebhook webhook = new EventWebhook(request -> {
            requests.add(request);
            return CompletableFuture.completedFuture(204);
        }, warnings::add);
        webhook.post(null, "disabled");
        assertTrue(requests.isEmpty());
        webhook.post(URI.create("https://example.test/secret"), "Arena starts now");
        assertEquals(1, requests.size());
        assertEquals("POST", requests.getFirst().method());
        assertEquals(Duration.ofSeconds(3), requests.getFirst().timeout().orElseThrow());
        assertEquals("application/json", requests.getFirst().headers().firstValue("Content-Type").orElseThrow());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void transportAndHttpFailuresWarnOnceWithoutDisclosingTheEndpoint() {
        URI url = URI.create("https://example.test/secret");
        List<String> warnings = new ArrayList<>();
        List<HttpRequest> requests = new ArrayList<>();
        CompletableFuture<Integer> failed = new CompletableFuture<>();
        EventWebhook webhook = new EventWebhook(request -> {
            requests.add(request);
            return failed;
        }, warnings::add);
        webhook.post(url, "starts soon");
        assertTrue(warnings.isEmpty());
        failed.completeExceptionally(new IllegalStateException(url.toString()));
        assertEquals(List.of("Arena event webhook delivery failed."), warnings);
        assertEquals(1, requests.size());
        warnings.clear();
        new EventWebhook(request -> CompletableFuture.completedFuture(429), warnings::add).post(url, "starts now");
        assertEquals(List.of("Arena event webhook delivery failed."), warnings);
    }
}
