package com.leevi.welt;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Leevis Welt - Launcher App
 *
 * Tablet-Launcher für Leevi. Lädt die Web-App von GitHub Pages.
 * YouTube läuft in einem zweiten WebView mit nativen Overlay-Buttons.
 * Kiosk-Modus verhindert Verlassen. Rotation erlaubt.
 */
public class MainActivity extends Activity {

    private static final String TAG = "LeevisWelt";
    private static final String APP_URL = "https://wescheskro.github.io/leevis-welt/leevis-app.html";

    // Erlaubte Apps die gestartet werden dürfen
    private static final String[] ALLOWED_PACKAGES = {
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "com.google.android.music",
        "com.spotify.music"
    };

    private FrameLayout rootLayout;
    private WebView webView;         // Main app WebView
    private WebView ytWebView;       // YouTube WebView (overlay)
    private LinearLayout ytOverlay;  // Native button bar on top of YouTube
    private boolean ytModeActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Vollbild: Keine Statusleiste, keine Navigationsleiste
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        // Screen bleibt an während App aktiv ist
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Immersive Sticky Mode
        hideSystemUI();

        // WebView Debug (Chrome DevTools: chrome://inspect)
        WebView.setWebContentsDebuggingEnabled(true);

        // Root FrameLayout — stacks main WebView, YouTube WebView, and overlay
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);
        setContentView(rootLayout);

        // ===== 1. Main App WebView =====
        webView = new WebView(this);
        configureWebView(webView);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // JavaScript-Bridge
        webView.addJavascriptInterface(new AppBridge(), "AndroidBridge");

        // WebViewClient: Links innerhalb der App halten + YouTube abfangen
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Custom protocol: app://youtube, app://youtube-peppa etc.
                if (url.startsWith("app://youtube")) {
                    String target = url.replace("app://youtube", "").replace("-", "");
                    enterYouTubeMode(target);
                    return true;
                }
                // Block any YouTube navigation in the main WebView
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    enterYouTubeMode("");
                    return true;
                }
                // Intent-URLs abfangen
                if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        if (intent != null) {
                            PackageManager pm = getPackageManager();
                            if (intent.resolveActivity(pm) != null) {
                                startActivity(intent);
                                return true;
                            }
                            String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                            if (fallbackUrl != null) {
                                view.loadUrl(fallbackUrl);
                                return true;
                            }
                        }
                    } catch (Exception e) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page loaded: " + url);
                hideSystemUI();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.e(TAG, "WebView error: " + error.getDescription() + " URL: " + request.getUrl());
            }
        });

        // WebChromeClient: Kamera/Mikrofon erlauben + Console-Logging
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "JS: " + consoleMessage.message() + " (line " + consoleMessage.lineNumber() + ")");
                return true;
            }
        });

        // ===== 2. YouTube WebView (hidden initially) =====
        ytWebView = new WebView(this);
        configureWebView(ytWebView);
        // YouTube braucht Third-Party Cookies
        CookieManager.getInstance().setAcceptThirdPartyCookies(ytWebView, true);
        ytWebView.setVisibility(View.GONE);
        rootLayout.addView(ytWebView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ytWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Keep YouTube navigation inside the YouTube WebView
                if (url.contains("youtube.com") || url.contains("youtu.be") || url.contains("google.com")) {
                    return false; // let it load
                }
                // External links: open in main WebView or browser
                return true; // block
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideSystemUI();
                // Inject CSS to add bottom padding so content isn't hidden behind overlay
                view.evaluateJavascript(
                    "(function(){ " +
                    "  var s = document.createElement('style'); " +
                    "  s.textContent = 'body { padding-bottom: 90px !important; }'; " +
                    "  document.head.appendChild(s); " +
                    "})()",
                    null
                );
            }
        });

        ytWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        // ===== 3. Overlay Button Bar (native Android buttons) =====
        buildOverlayBar();

        // App laden
        webView.clearCache(true);
        webView.loadUrl(APP_URL);
    }

    /**
     * Configure WebView settings (shared between main and YouTube WebViews)
     */
    private void configureWebView(WebView wv) {
        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowContentAccess(true);
        settings.setTextZoom(100);

        // User-Agent: damit YouTube Mobile-Version liefert
        String ua = settings.getUserAgentString();
        settings.setUserAgentString(ua + " LeevisWeltApp/1.0");
    }

    /**
     * Build the native overlay button bar for YouTube mode
     * 3 buttons: 🐷 Peppa | 😊 Stimmung | ← Zurück
     */
    private void buildOverlayBar() {
        int barHeight = dpToPx(80);

        ytOverlay = new LinearLayout(this);
        ytOverlay.setOrientation(LinearLayout.HORIZONTAL);
        ytOverlay.setGravity(Gravity.CENTER_VERTICAL);
        ytOverlay.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
        ytOverlay.setVisibility(View.GONE);

        // Semi-transparent dark background
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(240, 15, 15, 26));
        barBg.setCornerRadii(new float[]{
            dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16), 0, 0, 0, 0
        });
        ytOverlay.setBackground(barBg);
        ytOverlay.setElevation(dpToPx(8));

        // --- Peppa Button ---
        TextView peppaBtn = makeOverlayButton("🐷\nPeppa", new int[]{0xFFE91E63, 0xFFC2185B});
        peppaBtn.setOnClickListener(v -> {
            ytWebView.loadUrl("https://m.youtube.com/results?search_query=Peppa+Pig+Deutsch");
        });

        // --- Stimmung Button ---
        TextView moodBtn = makeOverlayButton("😊\nStimmung", new int[]{0xFFFF9800, 0xFFE65100});
        moodBtn.setOnClickListener(v -> {
            // Exit YouTube mode and open mood picker in the app
            exitYouTubeMode();
            webView.evaluateJavascript("toggleMoodPicker()", null);
        });

        // --- Zurück Button (bigger, red) ---
        TextView backBtn = makeOverlayButton("← Zurück", new int[]{0xFFE74C3C, 0xFFC0392B});
        backBtn.setOnClickListener(v -> exitYouTubeMode());

        // Add with weights: Peppa=1, Stimmung=1, Zurück=1.5
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp1.setMargins(dpToPx(3), 0, dpToPx(3), 0);
        LinearLayout.LayoutParams lp15 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f);
        lp15.setMargins(dpToPx(3), 0, dpToPx(3), 0);

        ytOverlay.addView(peppaBtn, lp1);
        ytOverlay.addView(moodBtn, lp1);
        ytOverlay.addView(backBtn, lp15);

        // Position: bottom of screen
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, barHeight
        );
        overlayParams.gravity = Gravity.BOTTOM;
        rootLayout.addView(ytOverlay, overlayParams);
    }

    /**
     * Create a styled overlay button
     */
    private TextView makeOverlayButton(String text, int[] gradientColors) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));

        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            gradientColors
        );
        bg.setCornerRadius(dpToPx(14));
        btn.setBackground(bg);
        btn.setElevation(dpToPx(4));

        return btn;
    }

    /**
     * Enter YouTube mode: show YouTube WebView + overlay, hide main app
     * @param target optional target: "peppa", "lieder", "babybus", "sensory", or "" for home
     */
    private void enterYouTubeMode(String target) {
        ytModeActive = true;

        // Determine URL
        String url;
        if ("peppa".equals(target)) {
            url = "https://m.youtube.com/results?search_query=Peppa+Pig+Deutsch";
        } else if ("lieder".equals(target)) {
            url = "https://m.youtube.com/results?search_query=Kinderlieder+deutsch+zum+Mitsingen";
        } else if ("babybus".equals(target)) {
            url = "https://m.youtube.com/results?search_query=BabyBus+Deutsch";
        } else if ("sensory".equals(target)) {
            url = "https://m.youtube.com/results?search_query=Hey+Bear+Sensory+Baby";
        } else {
            url = "https://m.youtube.com";
        }

        // Show YouTube WebView on top of main WebView
        ytWebView.setVisibility(View.VISIBLE);
        ytWebView.loadUrl(url);

        // Show overlay buttons
        ytOverlay.setVisibility(View.VISIBLE);

        hideSystemUI();
        Log.d(TAG, "YouTube mode entered: " + url);
    }

    /**
     * Exit YouTube mode: hide YouTube WebView + overlay, return to app
     */
    private void exitYouTubeMode() {
        ytModeActive = false;

        // Stop YouTube playback
        ytWebView.loadUrl("about:blank");
        ytWebView.setVisibility(View.GONE);

        // Hide overlay
        ytOverlay.setVisibility(View.GONE);

        // Ensure main WebView is showing the app
        // (it's been underneath the whole time, no reload needed)
        hideSystemUI();
        Log.d(TAG, "YouTube mode exited");
    }

    /**
     * JavaScript-Bridge: Funktionen die aus der Web-App aufgerufen werden
     */
    public class AppBridge {

        @JavascriptInterface
        public void openYouTube(String target) {
            runOnUiThread(() -> enterYouTubeMode(target != null ? target : ""));
        }

        @JavascriptInterface
        public void launchYouTube() {
            runOnUiThread(() -> enterYouTubeMode(""));
        }

        @JavascriptInterface
        public void launchYouTubeKids() {
            runOnUiThread(() -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.youtube.kids");
                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        enterYouTubeMode("");
                    }
                } catch (Exception e) {
                    enterYouTubeMode("");
                }
            });
        }

        @JavascriptInterface
        public void launchApp(String packageName) {
            runOnUiThread(() -> {
                boolean allowed = false;
                for (String pkg : ALLOWED_PACKAGES) {
                    if (pkg.equals(packageName)) { allowed = true; break; }
                }
                if (!allowed) return;
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (intent != null) startActivity(intent);
                } catch (Exception e) {}
            });
        }

        @JavascriptInterface
        public boolean isAppInstalled(String packageName) {
            try {
                getPackageManager().getPackageInfo(packageName, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean isLauncher() {
            return true;
        }

        @JavascriptInterface
        public void goHome() {
            runOnUiThread(() -> {
                if (ytModeActive) exitYouTubeMode();
                webView.evaluateJavascript("go('home')", null);
            });
        }
    }

    /**
     * Vollbild-Modus
     */
    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    /**
     * ZURÜCK-BUTTON: In YouTube mode exits YouTube. In app navigates home.
     */
    @Override
    public void onBackPressed() {
        if (ytModeActive) {
            // In YouTube mode: check if YouTube WebView can go back, otherwise exit
            if (ytWebView.canGoBack()) {
                ytWebView.goBack();
            } else {
                exitYouTubeMode();
            }
            return;
        }
        // In main app: navigate to home screen
        webView.evaluateJavascript(
            "(function(){ if(typeof currentScreen !== 'undefined' && currentScreen !== 'home'){ go('home'); return 'navigated'; } return 'home'; })()",
            value -> {
                // Wenn bereits auf Home: nichts tun (Kiosk)
            }
        );
    }

    /**
     * Home-Button: App bleibt im Vordergrund (nur als Standard-Launcher)
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * WebView Lifecycle
     */
    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (webView != null) webView.onResume();
        if (ytWebView != null && ytModeActive) ytWebView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        if (ytWebView != null) ytWebView.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (webView != null) webView.restoreState(savedInstanceState);
    }

    /** Helper: dp to pixels */
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
