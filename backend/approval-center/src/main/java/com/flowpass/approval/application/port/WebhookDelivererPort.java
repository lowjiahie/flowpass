package com.flowpass.approval.application.port;

/**
 * Outbound port that sends a single webhook delivery to its resolved destination.
 *
 * <p>Deliberately narrow — given a delivery and its JSON payload it performs the HTTP POST. The adapter
 * uses the JDK {@code java.net.http.HttpClient}. A throw here means the caller treats it as a failed
 * attempt and schedules a retry; the dispatcher owns the retry policy, the deliverer only transports.</p>
 */
public interface WebhookDelivererPort {

    /**
     * POST {@code payload} to the delivery endpoint.
     *
     * @param delivery the delivery to send (its {@code endpoint} is the destination)
     * @param payload  the JSON body originating from the associated outbox event
     * @throws RuntimeException if the transport fails or the endpoint returns a non-2xx status
     */
    void deliver(WebhookDelivery delivery, String payload);
}
