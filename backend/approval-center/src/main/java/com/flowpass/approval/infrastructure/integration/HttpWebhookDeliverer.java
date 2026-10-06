package com.flowpass.approval.infrastructure.integration;

import com.flowpass.approval.application.port.WebhookDelivererPort;
import com.flowpass.approval.application.port.WebhookDelivery;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Adapter implementing the {@link WebhookDelivererPort} with the JDK {@code HttpClient}.
 *
 * <p>Zero extra dependencies: posts the event payload to the resolved endpoint, carrying the originating
 * event id as {@code X-Event-Id}. Non-2xx responses and transport failures throw, which the dispatcher
 * treats as a failed attempt and schedules a retry.</p>
 */
@Repository
public class HttpWebhookDeliverer implements WebhookDelivererPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;

    public HttpWebhookDeliverer() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public void deliver(WebhookDelivery delivery, String payload) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(delivery.endpoint()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Event-Id", delivery.eventId())
                .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "" : payload))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Unsuccessful webhook status " + response.statusCode());
            }
        } catch (IOException e) {
            throw new RuntimeException("Webhook transport failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Webhook transport interrupted", e);
        }
    }
}
