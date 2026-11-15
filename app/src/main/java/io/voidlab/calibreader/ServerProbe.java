package io.voidlab.calibreader;

import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain HTTP probes of the configured server, used by the settings "Test
 * connection" action and the diagnostics dialog. No credentials are sent and
 * nothing is modified on the server: every request is a GET of a public
 * calibre-zen endpoint under the configured base address.
 */
public final class ServerProbe {

    private static final int TIMEOUT_MS = 6000;
    private static final String USER_AGENT = "CalibReader-probe/0.1";

    private ServerProbe() {
    }

    public static final class Result {
        public final String url;
        public final int code;
        public final long millis;
        public final String server;
        public final String contentType;
        public final String error;

        Result(String url, int code, long millis, String server, String contentType, String error) {
            this.url = url;
            this.code = code;
            this.millis = millis;
            this.server = server;
            this.contentType = contentType;
            this.error = error;
        }

        public boolean ok() {
            return error == null && code > 0 && code < 400;
        }

        public String summary() {
            if (error != null) {
                return "FAIL  " + url + "\n      " + error;
            }
            return (ok() ? "OK    " : "HTTP  ") + code + "  " + url
                    + "\n      " + millis + " ms"
                    + (server == null ? "" : "  ·  server: " + server)
                    + (contentType == null ? "" : "  ·  " + contentType);
        }
    }

    /** Performs one GET, following no redirects. Never throws. */
    public static Result fetch(String url) {
        long start = SystemClock.elapsedRealtime();
        HttpURLConnection conn = null;
        try {
            URL parsed = new URL(url);
            String protocol = parsed.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                throw new IOException("unsupported scheme " + protocol);
            }
            conn = (HttpURLConnection) parsed.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            drain(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            long millis = SystemClock.elapsedRealtime() - start;
            return new Result(url, code, millis,
                    conn.getHeaderField("Server"),
                    conn.getHeaderField("Content-Type"),
                    null);
        } catch (Exception e) {
            long millis = SystemClock.elapsedRealtime() - start;
            String message = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return new Result(url, -1, millis, null, null, message);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void drain(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            byte[] buffer = new byte[2048];
            int total = 0;
            while (total < 8192) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                total += read;
            }
        } catch (IOException ignored) {
            // The status and headers are what matter here.
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing a response stream has no useful failure mode here.
            }
        }
    }

    /** Probes the endpoints that show what this server actually serves. */
    public static List<Result> diagnose(String base) {
        List<Result> results = new ArrayList<>();
        // The page the reader actually loads, path included (usually /app/).
        results.add(fetch(ServerConfig.startUrl(base)));
        // Calibre's own endpoints stay at the origin root, even when the
        // reader is mounted on a path such as /app/.
        String origin = ServerConfig.origin(base);
        results.add(fetch(origin + "/opds"));
        results.add(fetch(origin + "/manifest.json"));
        results.add(fetch(origin + "/"));
        return results;
    }

    public static String format(String base, List<Result> results) {
        StringBuilder out = new StringBuilder();
        out.append("Server: ").append(base).append('\n');
        out.append(ServerConfig.isHttps(base) ? "Scheme: https (encrypted)"
                : "Scheme: http (cleartext on the local network)").append("\n\n");
        for (Result result : results) {
            out.append(result.summary()).append("\n\n");
        }
        out.append("Only this host is loaded in the reader; every other host is "
                + "blocked. TLS errors are never bypassed.");
        return out.toString();
    }
}
