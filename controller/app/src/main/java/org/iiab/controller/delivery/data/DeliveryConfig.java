package org.iiab.controller.delivery.data;

/**
 * Endpoint + shared token for the Cloudflare Worker. Blank until the Worker is deployed
 * (infra) — while blank the backbone keeps queuing but does not send, so nothing is lost.
 * TODO: wire these to a build/secure config once the Worker exists.
 */
public final class DeliveryConfig {

    private DeliveryConfig() {
    }

    public static final String ENDPOINT_URL = "";
    public static final String SHARED_TOKEN = "";

    public static boolean isConfigured() {
        return ENDPOINT_URL != null && !ENDPOINT_URL.isEmpty();
    }
}
