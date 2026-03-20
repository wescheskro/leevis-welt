package com.leevi.welt;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Leevis Welt - Launcher App
 *
 * Tablet-Launcher für Leevi. Lädt die Web-App von GitHub Pages.
 * Kann YouTube starten. Kiosk-Modus verhindert Verlassen.
 * Rotation erlaubt (Quer + Hochformat).
 */
public class MainActivity extends Activity {

    private WebView webView;
    private static final String APP_URL = "https://wescheskro.github.io/leevis-welt/leevis-app.html";

    // Erlaubte Apps die gestartet werden dürfen
    private static final String[] ALLOWED_PACKAGES = {
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "com.google.android.music",
        "com.spotify.music"
    };

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

        // WebView erstellen
        webView = new WebView(this);
        setContentView(webView);

        // WebView konfigurieren
        WebSettings settings = webView.getSettings();
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

        // Wichtig: User-Agent ergänzen damit Web-App erkennt dass sie in der APK läuft
        String ua = settings.getUserAgentString();
        settings.setUserAgentString(ua + " LeevisWeltApp/1.0");

        // JavaScript-Bridge
        webView.addJavascriptInterface(new AppBridge(), "AndroidBridge");

        // WebViewClient: Links innerhalb der App halten + YouTube abfangen
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.contains("youtube.com") || url.contains("youtu.be") || url.contains("youtubekids.com")) {
                    openYouTube(url);
                    return true;
                }
                // Intent-URLs abfangen (z.B. intent://...)
                if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        if (intent != null) {
                            PackageManager pm = getPackageManager();
                            if (intent.resolveActivity(pm) != null) {
                                startActivity(intent);
                                return true;
                            }
                            // Fallback: Play Store
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
                hideSystemUI();
            }
        });

        // WebChromeClient: Kamera/Mikrofon erlauben + Fullscreen für Videos
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        // App laden
        webView.loadUrl(APP_URL);
    }

    /**
     * YouTube-App starten
     */
    private void openYouTube(String url) {
        try {
            if (url.contains("youtubekids")) {
                Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.youtube.kids");
                if (intent != null) { startActivity(intent); return; }
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage("com.google.android.youtube");
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception e2) {
                Toast.makeText(this, "YouTube nicht gefunden", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * JavaScript-Bridge: Funktionen die aus der Web-App aufgerufen werden
     */
    public class AppBridge {

        @JavascriptInterface
        public void launchYouTube() {
            runOnUiThread(() -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.android.youtube");
                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"));
                        startActivity(web);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "YouTube nicht gefunden", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void launchYouTubeKids() {
            runOnUiThread(() -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.youtube.kids");
                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        launchYouTube();
                    }
                } catch (Exception e) {
                    launchYouTube();
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

        /**
         * Web-App kann native Zurück-Navigation auslösen
         */
        @JavascriptInterface
        public void goHome() {
            runOnUiThread(() -> {
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
     * ZURÜCK-BUTTON: Navigiert innerhalb der Web-App zurück zum Home-Screen.
     * Wenn schon auf Home: nichts tun (Kiosk-Modus).
     */
    @Override
    public void onBackPressed() {
        // Rufe die go('home') Funktion in der Web-App auf
        webView.evaluateJavascript(
            "(function(){ if(typeof currentScreen !== 'undefined' && currentScreen !== 'home'){ go('home'); return 'navigated'; } return 'home'; })()",
            value -> {
                // Wenn bereits auf Home: nichts tun (Kiosk)
                // super.onBackPressed() wird NICHT aufgerufen
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    /**
     * Rotation: WebView-State erhalten bei Konfigurationsänderung
     */
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
