/*
 * ============================================================================
 * Name        : HttpServerReachability.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Data impl of ServerReachability: a short-timeout HTTP GET probe
 *               (consolidates the old DashboardFragment.pingUrl). Treats 2xx/3xx
 *               as reachable; any failure is "not reachable".
 * ============================================================================
 */
package org.iiab.controller.dashboard.data;

import org.iiab.controller.dashboard.domain.ServerReachability;

import java.net.HttpURLConnection;
import java.net.URL;

public class HttpServerReachability implements ServerReachability {

    private static final int TIMEOUT_MS = 1500;

    @Override
    public boolean isReachable(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
