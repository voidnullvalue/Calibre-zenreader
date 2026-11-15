package io.voidlab.calibreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.net.URISyntaxException;

/**
 * Regression tests for the address validation and the navigation allow-list.
 *
 * <p>The reader is the server's own web application, so every URL that the
 * calibre-zen reader and the library actually produce has to stay inside the
 * WebView. Anything that is treated as out of scope is answered with HTTP 403
 * (subresource) or pushed to the system browser (navigation), which is exactly
 * the "blank reader" failure this suite guards against.
 */
public class ServerConfigTest {

    private static final String BASE = "http://192.168.88.89:8090/app";

    // ------------------------------------------------------------- normalize

    @Test
    public void normalizeAddsSchemeAndStripsTrailingSlashes() throws Exception {
        assertEquals("http://192.168.88.89:8090", ServerConfig.normalize("192.168.88.89:8090"));
        assertEquals("http://192.168.88.89:8090", ServerConfig.normalize("192.168.88.89:8090/"));
        assertEquals("http://books.local", ServerConfig.normalize("books.local"));
        assertEquals("https://books.example.org",
                ServerConfig.normalize("  HTTPS://books.example.org/  "));
    }

    @Test
    public void normalizeKeepsTheReaderPath() throws Exception {
        assertEquals("http://192.168.88.89:8090/app",
                ServerConfig.normalize("http://192.168.88.89:8090/app/"));
        assertEquals("http://192.168.88.89:8090/app",
                ServerConfig.normalize("http://192.168.88.89:8090/app"));
        assertEquals("https://books.example.org/calibre",
                ServerConfig.normalize("https://books.example.org/calibre/"));
    }

    @Test
    public void normalizeDropsQueryAndFragment() {
        assertRejected("http://192.168.88.89:8090/app/?book=1", "query");
        assertRejected("http://192.168.88.89:8090/app/#/library", "fragment");
    }

    @Test
    public void normalizeRejectsUnusableAddresses() {
        assertRejected("", "empty");
        assertRejected("ftp://192.168.88.89:8090/", "only http");
        assertRejected("http://user:secret@192.168.88.89:8090/", "user");
        assertRejected("javascript:alert(1)", "no host");
    }

    // -------------------------------------------------------------- startUrl

    @Test
    public void startUrlKeepsTheEntryPointPath() {
        assertEquals("http://192.168.88.89:8090/app/", ServerConfig.startUrl(BASE));
        assertEquals("http://192.168.88.89:8090/app/", ServerConfig.startUrl(BASE + "/"));
        assertEquals("http://192.168.88.89:8090/",
                ServerConfig.startUrl("http://192.168.88.89:8090"));
        assertEquals("", ServerConfig.startUrl(""));
        assertEquals("", ServerConfig.startUrl(null));
    }

    @Test
    public void originDropsTheReaderPathButKeepsThePort() {
        assertEquals("http://192.168.88.89:8090", ServerConfig.origin(BASE));
        assertEquals("https://books.example.org", ServerConfig.origin("https://books.example.org/calibre"));
        assertEquals("", ServerConfig.origin(""));
    }

    // ------------------------------------------------------------- isInScope

    /** URLs observed on the wire while reading "A Scanner Darkly" (book 1). */
    @Test
    public void everyRealReaderUrlStaysInScope() {
        String[] urls = {
                "http://192.168.88.89:8090/",
                "http://192.168.88.89:8090/app/",
                "http://192.168.88.89:8090/app/?book=1",
                "http://192.168.88.89:8090/#book_id=1&fmt=MOBI&library_id=library&mode=read_book",
                // calibre's reader route carries an epubcfi position in the fragment
                "http://192.168.88.89:8090/#book_id=1&bookpos=epubcfi(/4/2/4/6[filepos2459]/166/2:66)"
                        + "&fmt=MOBI&library_id=library&mode=read_book",
                "http://192.168.88.89:8090/#book_id=1298&bookpos=epubcfi(/2/2/4/2@49.98:50)"
                        + "&fmt=MOBI&library_id=library&mode=read_book",
                "http://192.168.88.89:8090/app/_astro/reader.Cq3DuZq9.js",
                "http://192.168.88.89:8090/app/_astro/inter-latin-wght-normal.Dx4kXJAl.woff2",
                "http://192.168.88.89:8090/zen/fonts/inter/Inter-Regular.ttf",
                "http://192.168.88.89:8090/interface-data/books-init?library_id=library&num=0",
                "http://192.168.88.89:8090/interface-data/book-metadata/1?library_id=library",
                "http://192.168.88.89:8090/book-file/1/MOBI/554415/17946936260/index.html"
                        + "?library_id=library&1794676889072",
                "http://192.168.88.89:8090/book-file/1/MOBI/554415/17946936260/images/00001.jpg"
                        + "?library_id=library&1794676889059",
                "http://192.168.88.89:8090/get/cover/1/library?t=2026-11-14T16%3A00%3A26%2B00%3A00",
                "http://192.168.88.89:8090/get/thumb/1298/library?sz=288x432&t=2026-11-14T16%3A29%3A07%2B00%3A00",
                "http://192.168.88.89:8090/zen/api/can-edit?library_id=library",
        };
        for (String url : urls) {
            assertTrue("should stay in scope: " + url, ServerConfig.isInScope(url, BASE));
        }
    }

    /**
     * java.net.URI rejects these, but a browser sends them and they are on the
     * configured origin. They used to be answered with 403 / pushed to the
     * system browser, which broke the page that asked for them.
     */
    @Test
    public void urlsWithCharactersUriRejectsStayInScope() {
        String[] urls = {
                "http://192.168.88.89:8090/app/?q=a b",
                "http://192.168.88.89:8090/app/?q=a|b",
                "http://192.168.88.89:8090/app/?q=a^b",
                "http://192.168.88.89:8090/app/?q=a{b}",
                "http://192.168.88.89:8090/app/?q=a\"b",
                "http://192.168.88.89:8090/app/?q=a\\b",
                "http://192.168.88.89:8090/app/#/search/été",
                "http://192.168.88.89:8090/book-file/1/MOBI/1/x y.html?library_id=library",
        };
        for (String url : urls) {
            assertTrue("should stay in scope: " + url, ServerConfig.isInScope(url, BASE));
        }
    }

    @Test
    public void otherOriginsStayOutOfScope() {
        String[] urls = {
                "http://192.168.88.89:8091/app/",
                "https://192.168.88.89:8091/app/",
                "http://192.168.88.90:8090/app/",
                "http://evil.example:8090/",
                "http://192.168.88.89:8090@evil.example/",
                "http://evil.example\\@192.168.88.89:8090/",
                "http://evil.example\\192.168.88.89:8090/app/",
                "http://192.168.88.89.evil.example:8090/app/",
                "http://192.168.88.89:8090evil.example/app/",
                "http://192.168.88.89:/app/",
                "http://192.168.88.89:99999/app/",
                "http://192.168.88.89:80/app/",
                "file:///sdcard/book.html",
                "content://com.android.providers.downloads.documents/1",
                "javascript:alert(document.cookie)",
                "data:text/html,<h1>hi</h1>",
                "about:blank",
                "blob:http://192.168.88.89:8090/5a1ae25f-72b6-4027-97fb-bc1eb0e4a036",
                "",
                null,
        };
        for (String url : urls) {
            assertFalse("must stay out of scope: " + url, ServerConfig.isInScope(url, BASE));
        }
    }

    @Test
    public void userInfoCannotBeUsedToReachAnotherOrigin() {
        // The authority before the last '@' is userinfo, not the host.
        assertTrue(ServerConfig.isInScope("http://user:pass@192.168.88.89:8090/app/", BASE));
        assertFalse(ServerConfig.isInScope("http://192.168.88.89:8090@evil.example/app/", BASE));
    }

    @Test
    public void scopeNeedsAConfiguredBase() {
        assertFalse(ServerConfig.isInScope("http://192.168.88.89:8090/app/", ""));
        assertFalse(ServerConfig.isInScope("http://192.168.88.89:8090/app/", null));
        assertTrue(ServerConfig.isInScope("http://192.168.88.89:8090/app/", "HTTP://192.168.88.89:8090/app"));
    }

    @Test
    public void defaultPortsMatchTheirScheme() {
        assertTrue(ServerConfig.isInScope("http://books.local/x", "http://books.local"));
        assertTrue(ServerConfig.isInScope("http://books.local:80/x", "http://books.local"));
        assertTrue(ServerConfig.isInScope("https://books.local:443/x", "https://books.local"));
        assertFalse(ServerConfig.isInScope("https://books.local:80/x", "https://books.local"));
        assertFalse(ServerConfig.isInScope("http://books.local:443/x", "http://books.local"));
    }

    @Test
    public void schemeMustMatchOnTheSameExplicitPort() {
        // http:// and https:// on the same host and explicit port are different
        // origins, in both directions. Treating them as the same let an http
        // URL pass the allow-list for a configured https base (and vice versa).
        assertFalse(ServerConfig.isInScope("https://books.local:8090/x", "http://books.local:8090"));
        assertFalse(ServerConfig.isInScope("http://books.local:8090/x", "https://books.local:8090"));
        // Matching scheme still keeps the legitimate target paths in scope.
        assertTrue(ServerConfig.isInScope("https://books.local:8090/x", "https://books.local:8090"));
        assertTrue(ServerConfig.isInScope("http://books.local:8090/x", "http://books.local:8090"));
        assertTrue(ServerConfig.isInScope("http://books.local:8090/app/reader", "http://books.local:8090"));
    }

    @Test
    public void ipv6HostsCompareWithBrackets() {
        assertTrue(ServerConfig.isInScope("http://[::1]:8090/app/", "http://[::1]:8090/app"));
        assertFalse(ServerConfig.isInScope("http://[::2]:8090/app/", "http://[::1]:8090/app"));
    }

    private static void assertRejected(String raw, String expectedReasonPart) {
        try {
            String normalised = ServerConfig.normalize(raw);
            fail("expected rejection of '" + raw + "', got " + normalised);
        } catch (URISyntaxException e) {
            if (e.getReason() == null || !e.getReason().contains(expectedReasonPart)) {
                fail("unexpected reason for '" + raw + "': " + e.getReason());
            }
        }
    }
}
