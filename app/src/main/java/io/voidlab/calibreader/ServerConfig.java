package io.voidlab.calibreader;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Persisted, validated Calibre server address and the navigation allow-list
 * derived from it.
 *
 * <p>Only the configured host and port are ever loaded inside the WebView.
 * The address is normalised so that every scope check compares like with like.
 */
public final class ServerConfig {

    private static final String PREFS = "calibreader";
    private static final String KEY_BASE = "server_base_url";
    private static final String KEY_LAST = "last_page_url";

    private final SharedPreferences prefs;

    public ServerConfig(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Configured base address without a trailing slash, or "" when unset. */
    public String baseUrl() {
        return prefs.getString(KEY_BASE, "");
    }

    public void setBaseUrl(String normalised) {
        prefs.edit().putString(KEY_BASE, normalised).apply();
    }

    /** Last in-scope page, so relaunching returns the reader to the same place. */
    public String lastUrl() {
        return prefs.getString(KEY_LAST, "");
    }

    public void setLastUrl(String url) {
        prefs.edit().putString(KEY_LAST, url).apply();
    }

    public static boolean isHttps(String url) {
        return url != null && url.toLowerCase(Locale.US).startsWith("https://");
    }

    /**
     * Accepts what a person actually types ("192.168.1.5:8080", "host",
     * "https://books.example.org/calibre/") and returns a canonical
     * "scheme://host[:port][/path]" with no trailing slash, query or fragment.
     *
     * @throws URISyntaxException with a human readable reason when unusable.
     */
    public static String normalize(String raw) throws URISyntaxException {
        if (raw == null) {
            throw new URISyntaxException("", "address is empty");
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new URISyntaxException(value, "address is empty");
        }
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        URI uri = new URI(value);
        String scheme = uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new URISyntaxException(value, "only http and https are supported");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new URISyntaxException(value, "no host in the address");
        }
        if (uri.getUserInfo() != null) {
            throw new URISyntaxException(value, "user:password in the address is not supported");
        }
        if (uri.getQuery() != null) {
            throw new URISyntaxException(value, "query strings are not supported");
        }
        if (uri.getFragment() != null) {
            throw new URISyntaxException(value, "fragments are not supported");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        StringBuilder out = new StringBuilder();
        out.append(scheme).append("://").append(host);
        if (uri.getPort() != -1) {
            out.append(':').append(uri.getPort());
        }
        out.append(path);
        return out.toString();
    }

    /**
     * The page the reader starts on: the configured base with exactly one
     * trailing slash. A base that includes a path (the calibre-zen /app/ entry
     * point) therefore keeps that path instead of falling back to the root.
     */
    public static String startUrl(String base) {
        if (base == null || base.isEmpty()) {
            return "";
        }
        String value = base;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "/";
    }

    /**
     * The scheme, host and port of {@code base}, without any path. Calibre's
     * own endpoints (/opds, /manifest.json) live at the root even when the
     * reader is mounted under /app/.
     */
    public static String origin(String base) {
        if (base == null || base.isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(base);
            StringBuilder out = new StringBuilder();
            out.append(uri.getScheme() == null ? "http" : uri.getScheme())
                    .append("://").append(uri.getHost());
            if (uri.getPort() != -1) {
                out.append(':').append(uri.getPort());
            }
            return out.toString();
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /**
     * True when {@code url} points at the configured host and port.
     *
     * <p>The check only looks at scheme and authority, and does so without
     * {@link URI}. A full URI parse rejects characters that browsers happily
     * send on the wire (a raw space, {@code |}, {@code ^}, {@code {} } or
     * {@code \\} in a query, for example). Treating such a URL as foreign made
     * the wrapper answer its own server with HTTP 403 for a subresource, and
     * hand a same-origin navigation to the system browser, which looked like a
     * blank page in the reader. The allow-list is unchanged: scheme, host and
     * port must all match exactly, so {@code http} and {@code https} on the
     * same host and explicit port are different origins.
     */
    public static boolean isInScope(String url, String base) {
        if (url == null || base == null || base.isEmpty()) {
            return false;
        }
        Origin target = originOf(url);
        Origin own = originOf(base);
        if (target == null || own == null) {
            return false;
        }
        return target.scheme.equals(own.scheme)
                && target.port == own.port
                && target.host.equalsIgnoreCase(own.host);
    }

    /** Scheme, host and port of an absolute http(s) URL. */
    private static final class Origin {
        final String scheme;
        final String host;
        final int port;

        Origin(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Parses {@code scheme://[userinfo@]host[:port]} without validating the
     * path, query or fragment, so that characters {@link URI} rejects cannot
     * hide a URL that is really on the configured origin. Returns null for
     * anything that is not an absolute http(s) URL with a host, and for
     * authorities whose port is not a plain number (those stay out of scope).
     */
    private static Origin originOf(String raw) {
        int sep = raw.indexOf("://");
        if (sep <= 0) {
            return null;
        }
        String scheme = raw.substring(0, sep).toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        int defaultPort = scheme.equals("https") ? 443 : 80;
        int start = sep + 3;
        int end = raw.length();
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // Browsers end the authority at '/', '?' and '#'; Chromium also
            // treats a backslash in a special URL as a path separator, so it
            // must end the authority here too or "http://evil\@host/" would
            // be read as a request to the configured host.
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                end = i;
                break;
            }
        }
        String authority = raw.substring(start, end);
        // Anything before the last '@' is userinfo, never the host.
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.isEmpty()) {
            return null;
        }
        String host;
        int port = defaultPort;
        if (authority.charAt(0) == '[') {
            int close = authority.indexOf(']');
            if (close < 1) {
                return null;
            }
            host = authority.substring(0, close + 1);
            String rest = authority.substring(close + 1);
            if (!rest.isEmpty()) {
                if (rest.charAt(0) != ':' || rest.length() == 1) {
                    return null;
                }
                Integer parsed = parsePort(rest.substring(1));
                if (parsed == null) {
                    return null;
                }
                port = parsed;
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                Integer parsed = parsePort(authority.substring(colon + 1));
                if (parsed == null) {
                    return null;
                }
                port = parsed;
            } else {
                host = authority;
            }
        }
        // Whitespace cannot be part of a real authority; refuse it as well.
        if (host.isEmpty()) {
            return null;
        }
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) {
                return null;
            }
        }
        return new Origin(scheme, host, port);
    }

    private static Integer parsePort(String digits) {
        if (digits.isEmpty() || digits.length() > 5) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return null;
            }
        }
        int port = Integer.parseInt(digits);
        return port > 65535 ? null : port;
    }
}
