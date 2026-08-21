package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Non-blocking online-brain health manager.
 * Lumi always boots locally first. This manager checks the configured online provider beside the
 * main runtime, records a precise connection state, and retries with bounded exponential backoff.
 */
final class AiConnectionManager {
    private static final long[] RETRY_MS = {5_000L, 15_000L, 30_000L, 60_000L, 300_000L};
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retryIndex = 0;
    private int generation = 0;
    private Runnable stateListener;

    AiConnectionManager(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    void start() {
        final int g = ++generation;
        handler.postDelayed(() -> runCheck(g), 650L);
    }

    /** Force an immediate fresh provider check after configuration changes or a user status request. */
    void refreshNow() {
        final int g = ++generation;
        retryIndex = 0;
        handler.removeCallbacksAndMessages(null);
        prefs.edit().putLong("ai_connection_next_retry_at", 0L).apply();
        handler.post(() -> runCheck(g));
    }

    /** Refresh only when the last check is old, avoiding noisy probes every foreground transition. */
    void refreshIfStale(long maxAgeMs) {
        long checked = prefs.getLong("ai_connection_checked_at", 0L);
        if (checked <= 0L || System.currentTimeMillis() - checked > Math.max(5_000L, maxAgeMs)) refreshNow();
    }

    void stop() {
        generation++;
        handler.removeCallbacksAndMessages(null);
    }

    void noteSuccess(String provider) {
        retryIndex = 0;
        writeState("CONNECTED", provider, "", 0L);
    }

    void noteFailure(String provider, String rawError) {
        Classified c = classify(rawError);
        writeState(c.state, provider, c.message, 0L);
        if (c.retryable) scheduleRetry();
    }

    private void runCheck(int g) {
        if (g != generation) return;
        final Provider p = selectedProvider();
        if (p == null) {
            retryIndex = 0;
            writeState("LOCAL_ONLY", "local", providerConfigurationSummary(prefs) + " Lumi is available locally.", 0L);
            return;
        }
        if (!networkAvailable()) {
            writeState("OFFLINE", p.name, "Phone has no usable internet connection. Lumi is staying local and will retry automatically.", 0L);
            scheduleRetry();
            return;
        }
        writeState("CHECKING", p.name, "Checking online AI connection…", 0L);
        new Thread(() -> {
            long started = System.currentTimeMillis();
            try {
                if ("openai".equals(p.name)) checkOpenAi(p.secret);
                else checkRemote(p.url);
                long latency = System.currentTimeMillis() - started;
                handler.post(() -> {
                    if (g != generation) return;
                    retryIndex = 0;
                    writeState("CONNECTED", p.name, "OpenAI authentication and network check passed. Online AI is ready.", latency);
                });
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - started;
                Classified c = classify(e);
                handler.post(() -> {
                    if (g != generation) return;
                    writeState(c.state, p.name, c.message, latency);
                    if (c.retryable) scheduleRetry();
                });
            }
        }, "LumiAiConnectionPreflight").start();
    }

    private void scheduleRetry() {
        int i = Math.min(retryIndex, RETRY_MS.length - 1);
        long delay = RETRY_MS[i];
        retryIndex = Math.min(retryIndex + 1, RETRY_MS.length - 1);
        prefs.edit().putLong("ai_connection_next_retry_at", System.currentTimeMillis() + delay).apply();
        final int g = generation;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> runCheck(g), delay);
    }

    private Provider selectedProvider() {
        String openAiKey = SecretStore.get(prefs, "openai_api_key").trim();
        String remoteUrl = prefs.getString("opensource_url", "").trim();
        String provider = prefs.getString("ai_provider", "open_source").trim().toLowerCase(Locale.US);

        if ("openai".equals(provider) && !openAiKey.isEmpty()) return new Provider("openai", "", openAiKey);
        if (("open_source".equals(provider) || "hybrid".equals(provider)) && !remoteUrl.isEmpty() && !remoteUrl.contains("192.168.1.100:11434"))
            return new Provider("remote-booster", remoteUrl, "");
        if (!openAiKey.isEmpty()) {
            if (!"openai".equals(provider)) prefs.edit().putString("ai_provider","openai").apply();
            return new Provider("openai", "", openAiKey);
        }
        if (!remoteUrl.isEmpty() && !remoteUrl.contains("192.168.1.100:11434")) {
            if (!"open_source".equals(provider) && !"hybrid".equals(provider))
                prefs.edit().putString("ai_provider","open_source").apply();
            return new Provider("remote-booster", remoteUrl, "");
        }
        return null;
    }

    private boolean networkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void checkOpenAi(String key) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.openai.com/v1/models").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(6_000);
            c.setReadTimeout(9_000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("User-Agent", "Lumi-AI-Connection-Manager/1");
            int code = c.getResponseCode();
            drain(c, code);
            if (code == 401 || code == 403) throw new ProviderHttpException(code, "OpenAI authentication was rejected.");
            if (code == 429) throw new ProviderHttpException(code, "OpenAI is rate limiting this connection.");
            if (code >= 500) throw new ProviderHttpException(code, "OpenAI service returned HTTP " + code + ".");
            if (code < 200 || code >= 300) throw new ProviderHttpException(code, "OpenAI returned HTTP " + code + ".");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void checkRemote(String endpoint) throws Exception {
        URL u = new URL(endpoint);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(6_000);
            c.setReadTimeout(9_000);
            c.setRequestProperty("User-Agent", "Lumi-AI-Connection-Manager/1");
            String token = SecretStore.get(prefs, "opensource_api_key").trim();
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            drain(c, code);
            // 404/405 still proves the host/TLS path is reachable; the chat endpoint is normally POST-only.
            if (code == 401 || code == 403) throw new ProviderHttpException(code, "Remote AI authentication was rejected.");
            if (code == 429) throw new ProviderHttpException(code, "Remote AI is rate limiting this connection.");
            if (code >= 500) throw new ProviderHttpException(code, "Remote AI service returned HTTP " + code + ".");
            if (code >= 400 && code != 404 && code != 405) throw new ProviderHttpException(code, "Remote AI endpoint returned HTTP " + code + ".");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void drain(HttpURLConnection c, int code) {
        try (InputStream in = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream()) {
            if (in == null) return;
            byte[] b = new byte[512];
            while (in.read(b) > 0) { /* drain */ }
        } catch (Exception ignored) {}
    }

    private Classified classify(Throwable t) {
        if (t instanceof SocketTimeoutException) return new Classified("TIMEOUT", "Online AI timed out. Lumi is staying local and will retry automatically.", true);
        if (t instanceof UnknownHostException) return new Classified("DNS_ERROR", "The AI host name could not be reached. Lumi is staying local and will retry automatically.", true);
        if (t instanceof ProviderHttpException) {
            int code = ((ProviderHttpException) t).code;
            if (code == 401 || code == 403) return new Classified("AUTH_REQUIRED", "AI authentication needs attention. Lumi is staying local; reconnect the provider in Integration Center.", false);
            if (code == 429) return new Classified("RATE_LIMITED", "The online AI is temporarily rate limited. Lumi is staying local and will retry automatically.", true);
            if (code >= 500) return new Classified("SERVICE_ERROR", "The online AI service is temporarily unavailable. Lumi is staying local and will retry automatically.", true);
            return new Classified("ENDPOINT_ERROR", safeMessage(t), false);
        }
        String m = safeMessage(t).toLowerCase(Locale.US);
        if (m.contains("ssl") || m.contains("certificate") || m.contains("handshake"))
            return new Classified("TLS_ERROR", "Secure connection to the AI provider failed. Check the endpoint certificate or network.", false);
        if (m.contains("refused") || m.contains("failed to connect") || m.contains("network is unreachable"))
            return new Classified("OFFLINE", "The AI provider could not be reached. Lumi is staying local and will retry automatically.", true);
        if (m.contains("401") || m.contains("403") || m.contains("authentication") || m.contains("unauthorized"))
            return new Classified("AUTH_REQUIRED", "AI authentication needs attention. Lumi is staying local; reconnect the provider in Integration Center.", false);
        return new Classified("CONNECTION_ERROR", "Online AI connection failed: " + safeMessage(t) + ". Lumi is staying local and will retry.", true);
    }

    private Classified classify(String raw) {
        return classify(new Exception(raw == null ? "Unknown connection error" : raw));
    }

    private static String safeMessage(Throwable t) {
        String m = t == null ? "Unknown connection error" : t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t == null ? "Unknown connection error" : t.getClass().getSimpleName();
        m = m.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/=]+", "Bearer [redacted]");
        if (m.length() > 240) m = m.substring(0, 240);
        return m.trim();
    }

    private void writeState(String state, String provider, String detail, long latencyMs) {
        prefs.edit()
                .putString("ai_connection_state", state)
                .putString("ai_connection_provider", provider == null ? "" : provider)
                .putString("ai_connection_detail", detail == null ? "" : detail)
                .putLong("ai_connection_checked_at", System.currentTimeMillis())
                .putLong("ai_connection_latency_ms", latencyMs)
                .apply();
        Runnable listener = stateListener;
        if (listener != null) handler.post(listener);
    }



    static String providerConfigurationSummary(SharedPreferences prefs) {
        String key = SecretStore.get(prefs, "openai_api_key").trim();
        String url = prefs.getString("opensource_url", "").trim();
        String selected = prefs.getString("ai_provider", "").trim();
        boolean oldPrototypeUrl = url.contains("192.168.1.100:11434");
        if (oldPrototypeUrl) {
            prefs.edit().remove("opensource_url").remove("opensource_model").apply();
            url = "";
        }
        if (!key.isEmpty()) return "OpenAI credential is stored securely on this device.";
        if (!url.isEmpty()) return "A remote AI endpoint is configured.";
        if (!selected.isEmpty()) return "Provider preference is " + selected + ", but no usable credential or endpoint is stored.";
        return "No online AI credential or endpoint is stored on this device.";
    }

    static String spokenSummary(SharedPreferences prefs) {
        String state = prefs.getString("ai_connection_state", "UNKNOWN");
        String provider = prefs.getString("ai_connection_provider", "local");
        String detail = prefs.getString("ai_connection_detail", "I haven't checked the online AI connection yet.");
        if ("CONNECTED".equals(state)) return "My stronger AI connection is online through " + provider + ". " + detail;
        if ("CHECKING".equals(state)) return "I'm checking my stronger AI connection now. " + detail;
        if ("LOCAL_ONLY".equals(state)) return detail == null || detail.trim().isEmpty()
                ? "I'm running locally right now because no online AI provider is configured."
                : detail;
        if ("AUTH_REQUIRED".equals(state)) return "My stronger AI is configured, but authentication needs attention. " + detail;
        if ("UNKNOWN".equals(state)) return "I don't have a fresh AI connection result yet. I'm checking it now.";
        return "My stronger AI connection is currently " + state.toLowerCase(Locale.US).replace('_',' ') + ". " + detail;
    }

    static String summary(SharedPreferences prefs) {
        String state = prefs.getString("ai_connection_state", "UNKNOWN");
        String provider = prefs.getString("ai_connection_provider", "local");
        String detail = prefs.getString("ai_connection_detail", "Not checked yet.");
        long latency = prefs.getLong("ai_connection_latency_ms", 0L);
        long retryAt = prefs.getLong("ai_connection_next_retry_at", 0L);
        String retry = retryAt > System.currentTimeMillis() ? "\nRetry queued automatically." : "";
        return state + " • " + provider + (latency > 0 ? " • " + latency + " ms" : "") + "\n" + detail + retry;
    }

    private static final class Provider {
        final String name, url, secret;
        Provider(String name, String url, String secret) { this.name = name; this.url = url; this.secret = secret; }
    }
    private static final class Classified {
        final String state, message; final boolean retryable;
        Classified(String state, String message, boolean retryable) { this.state = state; this.message = message; this.retryable = retryable; }
    }
    private static final class ProviderHttpException extends Exception {
        final int code;
        ProviderHttpException(int code, String message) { super(message); this.code = code; }
    }
}
