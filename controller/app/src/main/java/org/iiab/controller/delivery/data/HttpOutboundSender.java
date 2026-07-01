package org.iiab.controller.delivery.data;

import android.util.Log;

import org.iiab.controller.delivery.domain.OutboundEnvelope;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Posts a batch of envelopes as JSON to the Cloudflare Worker. Data-layer transport. */
public final class HttpOutboundSender {

    private static final String TAG = "IIAB-HttpOutboundSender";

    /** @return true only on a 2xx response; false (retry) otherwise or if not configured. */
    public boolean sendBatch(List<OutboundEnvelope> items) {
        if (!DeliveryConfig.isConfigured()) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (OutboundEnvelope e : items) {
                arr.put(e.toJsonObject());
            }
            String body = new JSONObject().put("items", arr).toString();

            conn = (HttpURLConnection) new URL(DeliveryConfig.ENDPOINT_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("x-k2go-token", DeliveryConfig.SHARED_TOKEN);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.e(TAG, "sendBatch failed", e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
