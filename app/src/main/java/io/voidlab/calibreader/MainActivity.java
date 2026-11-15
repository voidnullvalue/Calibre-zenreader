package io.voidlab.calibreader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A deliberately thin, locked-down shell around the calibre-zen web app.
 *
 * <p>All application logic lives here on purpose: the reader is the server's
 * own web UI, the app only adds a native frame, an allow-list, downloads and
 * diagnostics. See README.md for the security model and known limits.
 */
public class MainActivity extends Activity {

    private static final String TAG = "CalibReader";
    private static final int REQ_POST_NOTIFICATIONS = 41;
    /** Optional launch extra so automation can point a fresh install at a server. */
    public static final String EXTRA_SERVER_URL = "server_url";

    private ServerConfig config;
    private ReaderAppearance appearance;
    private WebView webView;
    private ProgressBar progress;
    private View errorPanel;
    private TextView errorDetail;
    private ExecutorService io;
    private boolean sslBlocked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        config = new ServerConfig(this);
        appearance = new ReaderAppearance(this);
        io = Executors.newSingleThreadExecutor();

        webView = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        errorPanel = findViewById(R.id.error_panel);
        errorDetail = findViewById(R.id.error_detail);

        boolean debuggable =
                (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);

        configureWebView();
        wireErrorPanel();
        boolean launchExtra = applyLaunchServer(getIntent());

        boolean restored = false;
        if (savedInstanceState != null) {
            restored = webView.restoreState(savedInstanceState) != null;
        }
        if (!restored) {
            if (config.baseUrl().isEmpty() && !applyBuiltInServer()) {
                showSettingsDialog(BuildConfig.BUILT_IN_SERVER);
            } else {
                // An explicit launch extra opens that exact base, not a
                // remembered page from an earlier session.
                openStartPage(launchExtra);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (applyLaunchServer(intent)) {
            openStartPage(true);
        }
    }

    /** Honours the optional server_url extra; returns true when it was used. */
    private boolean applyLaunchServer(Intent intent) {
        if (intent == null) {
            return false;
        }
        String extra = intent.getStringExtra(EXTRA_SERVER_URL);
        if (extra == null || extra.trim().isEmpty()) {
            return false;
        }
        try {
            String normalised = ServerConfig.normalize(extra);
            if (!normalised.equals(config.baseUrl())) {
                config.setBaseUrl(normalised);
                Log.i(TAG, "server from launch extra: " + normalised);
            }
            // Always true: an explicit extra is a request to show that base
            // now, even when it is already the saved server.
            return true;
        } catch (URISyntaxException e) {
            Toast.makeText(this, getString(R.string.settings_invalid, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /**
     * Saves and uses the build-time default server on a first run. The default
     * is normalised and validated exactly like a typed address, and it keeps
     * any path component (http://host:8090/app/). Returns false when no usable
     * default was baked in.
     */
    private boolean applyBuiltInServer() {
        String builtIn = BuildConfig.BUILT_IN_SERVER;
        if (builtIn == null || builtIn.trim().isEmpty()) {
            return false;
        }
        try {
            String normalised = ServerConfig.normalize(builtIn);
            config.setBaseUrl(normalised);
            config.setLastUrl("");
            Log.i(TAG, "using built-in default server: "
                    + ServerConfig.startUrl(normalised));
            return true;
        } catch (URISyntaxException e) {
            Log.w(TAG, "built-in default server is invalid: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- webview

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        // calibre-zen is a JavaScript application; it needs script and storage.
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // Nothing may reach the filesystem or other apps' content.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        // Never let an https page pull cleartext http subresources.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " CalibReader/0.1");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }
        webView.setBackgroundColor(appearance.isInverted() ? Color.BLACK : Color.WHITE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new ReaderClient());
        webView.setWebChromeClient(new ReaderChrome());
        webView.setDownloadListener(new ReaderDownloads());
    }

    private final class ReaderClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return false; // the WebResourceRequest overload above handles it
            }
            return handleNavigation(Uri.parse(url));
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return intercept(url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return intercept(request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(0);
            sslBlocked = false;
            // Redirects are usually routed through shouldOverrideUrlLoading, but
            // this is the safety net that keeps the address bar honest.
            if (url != null && !isNavigable(url)) {
                view.stopLoading();
                openExternally(Uri.parse(url));
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            if (url != null && ServerConfig.isInScope(url, config.baseUrl())) {
                config.setLastUrl(url);
            }
            applyInversion();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // No self-signed or expired-certificate bypass, ever.
            handler.cancel();
            sslBlocked = true;
            Log.w(TAG, "TLS rejected for " + (error == null ? "unknown host" : error.getUrl()));
            showError(getString(R.string.error_ssl));
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && !sslBlocked) {
                String description = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? String.valueOf(error.getDescription()) : "";
                showError(getString(R.string.error_generic, description));
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return; // superseded by the WebResourceRequest overload
            }
            if (!sslBlocked) {
                showError(getString(R.string.error_generic,
                        description == null ? "network error" : description));
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (request.isForMainFrame()) {
                Log.w(TAG, "HTTP " + response.getStatusCode() + " for " + request.getUrl());
                showError(getString(R.string.error_generic,
                        "HTTP " + response.getStatusCode() + " " + response.getReasonPhrase()));
            }
        }
    }

    private final class ReaderChrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            setTitle(title == null || title.isEmpty() ? getString(R.string.app_name) : title);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            // target=_blank / window.open stay in this single WebView instead of
            // spawning an unmanaged popup that would escape the allow-list.
            WebView transportView = new WebView(MainActivity.this);
            transportView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    v.destroy();
                    if (isNavigable(url)) {
                        showReader();
                        webView.loadUrl(url);
                    } else {
                        openExternally(request.getUrl());
                    }
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(transportView);
            resultMsg.sendToTarget();
            return true;
        }
    }

    private final class ReaderDownloads implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimetype, long contentLength) {
            startDownload(url, userAgent, contentDisposition, mimetype);
        }
    }

    // ------------------------------------------------------------- navigation

    private boolean isNavigable(String url) {
        return ServerConfig.isInScope(url, config.baseUrl());
    }

    /** @return true when the WebView must not load the URL itself. */
    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        String url = uri.toString();
        if (scheme.equals("http") || scheme.equals("https")) {
            if (isNavigable(url)) {
                return false;
            }
            Log.i(TAG, "external link -> browser: " + uri.getHost());
            openExternally(uri);
            return true;
        }
        if (scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("sms")) {
            openExternally(uri);
            return true;
        }
        // file:, content:, intent:, javascript: and anything else stay out.
        Log.w(TAG, "blocked navigation with scheme '" + scheme + "'");
        toast(getString(R.string.toast_blocked_scheme));
        return true;
    }

    private WebResourceResponse intercept(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.US);
        boolean network = lower.startsWith("http://") || lower.startsWith("https://");
        if (network && !isNavigable(url)) {
            Log.w(TAG, "blocked out-of-scope request: " + Uri.parse(url).getHost());
            return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
                    java.util.Collections.<String, String>emptyMap(),
                    new ByteArrayInputStream(new byte[0]));
        }
        return null;
    }

    private void openExternally(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            toast(getString(R.string.toast_external));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.toast_no_handler));
        }
    }

    private void openStartPage(boolean ignoreLastPage) {
        String base = config.baseUrl();
        if (base.isEmpty()) {
            showError(getString(R.string.toast_need_server));
            showSettingsDialog(BuildConfig.BUILT_IN_SERVER);
            return;
        }
        String target = ServerConfig.startUrl(base);
        if (!ignoreLastPage) {
            String last = config.lastUrl();
            if (!last.isEmpty() && isNavigable(last)) {
                target = last;
            }
        }
        showReader();
        Log.i(TAG, "loading " + target);
        webView.loadUrl(target);
    }

    // ---------------------------------------------------------------- errors

    private void wireErrorPanel() {
        findViewById(R.id.error_retry).setOnClickListener(v -> {
            showReader();
            if (webView.getUrl() == null) {
                openStartPage(true);
            } else {
                webView.reload();
            }
        });
        findViewById(R.id.error_settings).setOnClickListener(v -> showSettingsDialog(null));
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            errorDetail.setText(message);
            errorPanel.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
        });
    }

    private void showReader() {
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------- downloads

    private void startDownload(String url, String userAgent, String contentDisposition,
                               String mimetype) {
        if (!isNavigable(url)) {
            Log.w(TAG, "download refused, out of scope: " + url);
            toast(getString(R.string.toast_download_blocked));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimetype);
            request.setTitle(fileName);
            request.setDescription(getString(R.string.download_description));
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            // The calibre server may require its session cookie for the file.
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long id = manager.enqueue(request);
            Log.i(TAG, "download queued id=" + id + " file=" + fileName);
            toast(getString(R.string.toast_download, fileName));
        } catch (Exception e) {
            Log.e(TAG, "download failed: " + e, e);
            toast(getString(R.string.toast_download_blocked));
        }
    }

    // -------------------------------------------------------------- appearance

    /** Re-applies the saved colour mode to the current page and background. */
    private void applyInversion() {
        boolean inverted = appearance.isInverted();
        webView.setBackgroundColor(inverted ? Color.BLACK : Color.WHITE);
        webView.evaluateJavascript(ReaderAppearance.injectionScript(inverted), null);
    }

    // ---------------------------------------------------------------- settings

    private void showSettingsDialog(String prefill) {
        View content = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        EditText input = content.findViewById(R.id.server_url);
        TextView status = content.findViewById(R.id.settings_status);
        String current = config.baseUrl();
        if (current != null && !current.isEmpty()) {
            input.setText(current);
        } else if (prefill != null && !prefill.isEmpty()) {
            input.setText(prefill);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(content)
                .setPositiveButton(R.string.settings_save, null)
                .setNegativeButton(R.string.settings_cancel, null)
                .setNeutralButton(R.string.settings_test, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                dialog.dismiss();
                if (config.baseUrl().isEmpty()) {
                    showError(getString(R.string.toast_need_server));
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String normalised = ServerConfig.normalize(input.getText().toString());
                    config.setBaseUrl(normalised);
                    config.setLastUrl("");
                    dialog.dismiss();
                    toast(getString(R.string.settings_saved));
                    showReader();
                    openStartPage(true);
                } catch (URISyntaxException e) {
                    input.setError(getString(R.string.settings_invalid, e.getMessage()));
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                final String candidate;
                try {
                    candidate = ServerConfig.normalize(input.getText().toString());
                } catch (URISyntaxException e) {
                    input.setError(getString(R.string.settings_invalid, e.getMessage()));
                    return;
                }
                status.setVisibility(View.VISIBLE);
                status.setText(R.string.settings_probe_running);
                io.execute(() -> {
                    // Probe the page the app will actually load, path included.
                    ServerProbe.Result result =
                            ServerProbe.fetch(ServerConfig.startUrl(candidate));
                    StringBuilder text = new StringBuilder(result.summary());
                    if (!ServerConfig.isHttps(candidate)) {
                        text.append("\n\n").append(getString(R.string.settings_cleartext_warning));
                    }
                    final String report = text.toString();
                    runOnUiThread(() -> {
                        if (!isFinishing()) {
                            status.setVisibility(View.VISIBLE);
                            status.setText(report);
                        }
                    });
                });
            });
        });
        dialog.show();
    }

    private void showDiagnostics() {
        String base = config.baseUrl();
        if (base.isEmpty()) {
            toast(getString(R.string.diag_no_server));
            return;
        }
        TextView body = new TextView(this);
        body.setPadding(32, 24, 32, 8);
        body.setTextIsSelectable(true);
        body.setText(getString(R.string.diag_running, base));
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.diag_title)
                .setView(scroller)
                .setPositiveButton(R.string.diag_close, null)
                .create();
        dialog.show();
        io.execute(() -> {
            List<ServerProbe.Result> results = ServerProbe.diagnose(base);
            String report = ServerProbe.format(base, results);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    body.setText(report);
                }
            });
        });
    }

    private void clearSiteData() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        webView.clearCache(true);
        webView.clearHistory();
        config.setLastUrl("");
        toast(getString(R.string.toast_data_cleared));
    }

    // ------------------------------------------------------------------- menus

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem invert = menu.findItem(R.id.action_invert_colors);
        if (invert != null) {
            invert.setChecked(appearance.isInverted());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_invert_colors) {
            boolean inverted = !appearance.isInverted();
            appearance.setInverted(inverted);
            item.setChecked(inverted);
            applyInversion();
            toast(getString(inverted ? R.string.toast_invert_on : R.string.toast_invert_off));
            return true;
        }
        if (id == R.id.action_reload) {
            showReader();
            webView.reload();
            return true;
        }
        if (id == R.id.action_settings) {
            showSettingsDialog(null);
            return true;
        }
        if (id == R.id.action_diagnostics) {
            showDiagnostics();
            return true;
        }
        if (id == R.id.action_open_browser) {
            String url = webView.getUrl();
            if (url != null) {
                openExternally(Uri.parse(url));
            }
            return true;
        }
        if (id == R.id.action_copy_url) {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            String url = webView.getUrl();
            if (clipboard != null && url != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                toast(getString(R.string.toast_copied));
            }
            return true;
        }
        if (id == R.id.action_clear_data) {
            clearSiteData();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    public void onBackPressed() {
        if (errorPanel.getVisibility() == View.VISIBLE) {
            showReader();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (io != null) {
            io.shutdownNow();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
