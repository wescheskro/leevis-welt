package com.leevi.welt;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.util.Log;

/**
 * Leevis Welt — Android Launcher for Leevi
 *
 * This is a HOME LAUNCHER that replaces the normal Android home screen.
 * It loads the Leevis Welt web app in a fullscreen WebView.
 *
 * When Leevi opens YouTube (or any allowed app), a floating overlay service
 * (OverlayService) shows Peppa/Stimmung/Zurück buttons on top of everything.
 * The Zurück button brings Leevi back here.
 *
 * Registered as HOME + LAUNCHER in AndroidManifest.xml.
 * Uses SYSTEM_ALERT_WINDOW for the floating overlay buttons.
 */
public class MainActivity extends Activity {

    private static final String TAG = "LeevisWelt";
    private static final String APP_URL = "https://wescheskro.github.io/leevis-welt/leevis-app.html";

    // Apps Leevi is allowed to launch (controlled by parents)
    private static final String[] ALLOWED_PACKAGES = {
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "com.google.android.music",
        "com.spotify.music",
        "com.google.android.apps.photos",
        "com.android.camera2",
        "com.android.camera"
    };

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Vollbild: Keine Statusleiste, keine Navigationsleiste
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        // Screen bleibt an
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Immersive Sticky Mode
        hideSystemUI();

        // WebView Debug (Chrome DevTools: chrome://inspect)
        WebView.setWebContentsDebuggingEnabled(true);

        // WebView erstellen
        webView = new WebView(this);
        setContentView(webView);

        // WebView konfigurieren
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowContentAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setTextZoom(100);

        // User-Agent: Web-App erkennt APK-Modus
        String ua = settings.getUserAgentString();
        settings.setUserAgentString(ua + " LeevisWeltApp/2.0");

        // JavaScript-Bridge
        webView.addJavascriptInterface(new AppBridge(), "AndroidBridge");

        // WebViewClient: Links in der App halten
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // YouTube URLs → launch real YouTube app
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    launchExternalApp("com.google.android.youtube", url);
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

        // WebChromeClient: Kamera/Mikrofon + Console
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

        // Request overlay permission on first launch (needed for floating buttons)
        requestOverlayPermission();

        // App laden
        webView.clearCache(true);
        webView.loadUrl(APP_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // Handle overlay service callbacks
        String action = intent.getStringExtra("overlay_action");
        if (action != null) {
            Log.d(TAG, "Overlay action: " + action);
            if ("mood".equals(action)) {
                // Open mood picker in web app
                webView.evaluateJavascript("go('home');setTimeout(function(){toggleMoodPicker()},300)", null);
            } else {
                // Default: go home
                webView.evaluateJavascript("go('home')", null);
            }
        }

        hideSystemUI();
    }

    /**
     * Request SYSTEM_ALERT_WINDOW permission (needed for floating overlay)
     */
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // Open settings to grant permission
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this,
                    "Bitte 'Über anderen Apps anzeigen' erlauben für Leevis Welt",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Check if overlay permission is granted
     */
    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true; // Pre-M doesn't need runtime permission
    }

    /**
     * Launch an external app with the floating overlay buttons
     */
    private void launchExternalApp(String packageName, String url) {
        try {
            Intent intent;
            if (url != null && !url.isEmpty()) {
                // Open specific URL in the app
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.setPackage(packageName);
            } else {
                // Just launch the app
                intent = getPackageManager().getLaunchIntentForPackage(packageName);
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);

                // Start the floating overlay service (Peppa/Stimmung/Zurück buttons)
                if (hasOverlayPermission()) {
                    Intent overlayIntent = new Intent(this, OverlayService.class);
                    startService(overlayIntent);
                }
                Log.d(TAG, "Launched: " + packageName + (url != null ? " with " + url : ""));
            } else {
                // App not installed — try as a generic intent
                Intent generic = new Intent(Intent.ACTION_VIEW, Uri.parse(url != null ? url : "https://www.youtube.com"));
                generic.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(generic);
                Log.d(TAG, "App not found, opened URL: " + url);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch " + packageName + ": " + e.getMessage());
            Toast.makeText(this, "App konnte nicht gestartet werden", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * JavaScript-Bridge: Funktionen die aus der Web-App aufgerufen werden
     */
    public class AppBridge {

        /**
         * Launch YouTube (with optional search target)
         * Targets: "peppa", "lieder", "babybus", "sensory", or "" for home
         */
        @JavascriptInterface
        public void openYouTube(String target) {
            runOnUiThread(() -> {
                String url;
                if ("peppa".equals(target)) {
                    url = "https://www.youtube.com/results?search_query=Peppa+Pig+Deutsch";
                } else if ("lieder".equals(target)) {
                    url = "https://www.youtube.com/results?search_query=Kinderlieder+deutsch";
                } else if ("babybus".equals(target)) {
                    url = "https://www.youtube.com/results?search_query=BabyBus+Deutsch";
                } else if ("sensory".equals(target)) {
                    url = "https://www.youtube.com/results?search_query=Hey+Bear+Sensory";
                } else {
                    url = "https://www.youtube.com";
                }
                launchExternalApp("com.google.android.youtube", url);
            });
        }

        @JavascriptInterface
        public void launchYouTube() {
            runOnUiThread(() -> launchExternalApp("com.google.android.youtube", null));
        }

        @JavascriptInterface
        public void launchYouTubeKids() {
            runOnUiThread(() -> launchExternalApp("com.google.android.apps.youtube.kids", null));
        }

        /**
         * Launch any allowed app by package name
         */
        @JavascriptInterface
        public void launchApp(String packageName) {
            runOnUiThread(() -> {
                boolean allowed = false;
                for (String pkg : ALLOWED_PACKAGES) {
                    if (pkg.equals(packageName)) { allowed = true; break; }
                }
                if (!allowed) {
                    Log.w(TAG, "App not in allowed list: " + packageName);
                    return;
                }
                launchExternalApp(packageName, null);
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
        public boolean hasOverlay() {
            return hasOverlayPermission();
        }

        @JavascriptInterface
        public void requestOverlay() {
            runOnUiThread(() -> requestOverlayPermission());
        }

        @JavascriptInterface
        public void goHome() {
            runOnUiThread(() -> webView.evaluateJavascript("go('home')", null));
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
     * ZURÜCK-BUTTON: Navigiert innerhalb der Web-App zurück zum Home-Screen.
     * Wenn schon auf Home: nichts tun (Kiosk-Modus).
     */
    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
            "(function(){ if(typeof currentScreen !== 'undefined' && currentScreen !== 'home'){ go('home'); return 'navigated'; } return 'home'; })()",
            value -> {
                // Auf Home: nichts tun (Kiosk — Leevi kann App nicht verlassen)
            }
        );
    }

    /**
     * Home-Button: Kiosk-Modus — App bleibt im Vordergrund
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
        // Stop overlay when returning to Leevis Welt (user came back)
        stopService(new Intent(this, OverlayService.class));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
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
}
