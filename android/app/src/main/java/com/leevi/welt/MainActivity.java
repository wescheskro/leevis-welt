package com.leevi.welt;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Leevis Welt - Launcher App
 *
 * Diese App dient als Tablet-Launcher (Startbildschirm) für Leevi.
 * Sie lädt die Web-App und kann die echte YouTube-App starten.
 * Leevi kann nicht aus der App raus (Kiosk-Modus).
 */
public class MainActivity extends Activity {

    private WebView webView;
    private static final String APP_URL = "https://wescheskro.github.io/leevis-welt/leevis-app.html";

    // Erlaubte Apps die gestartet werden dürfen
    private static final String[] ALLOWED_PACKAGES = {
        "com.google.android.youtube",           // YouTube
        "com.google.android.apps.youtube.kids", // YouTube Kids
        "com.google.android.music",             // Google Play Music
        "com.spotify.music"                     // Spotify
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

        // Immersive Sticky Mode - versteckt System-Bars komplett
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

        // JavaScript-Bridge: Damit die Web-App native Android-Funktionen aufrufen kann
        webView.addJavascriptInterface(new AppBridge(), "AndroidBridge");

        // WebViewClient: Links innerhalb der App halten
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // YouTube-Links: Echte YouTube-App öffnen
                if (url.contains("youtube.com") || url.contains("youtu.be") || url.contains("youtubekids.com")) {
                    launchYouTube(url);
                    return true;
                }
                // Alles andere im WebView behalten
                return false;
            }
        });

        // WebChromeClient: Kamera/Mikrofon-Zugriff erlauben (für Emotionserkennung)
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        // App laden
        webView.loadUrl(APP_URL);
    }

    /**
     * Startet die echte YouTube-App oder YouTube Kids
     */
    private void launchYouTube(String url) {
        try {
            // Versuche YouTube Kids wenn URL darauf hinweist
            if (url.contains("youtubekids")) {
                Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.youtube.kids");
                if (intent != null) {
                    startActivity(intent);
                    return;
                }
            }

            // Versuche reguläre YouTube-App
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage("com.google.android.youtube");
            startActivity(intent);
        } catch (Exception e) {
            // Fallback: YouTube Kids versuchen
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.youtube.kids");
                if (intent != null) {
                    startActivity(intent);
                    return;
                }
            } catch (Exception e2) {}

            // Letzter Fallback: Im Browser öffnen
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception e3) {
                Toast.makeText(this, "YouTube konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * JavaScript-Bridge: Funktionen die aus der Web-App aufgerufen werden können
     * In der Web-App: AndroidBridge.launchYouTube() oder AndroidBridge.launchYouTubeKids()
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
                        // Fallback auf reguläres YouTube
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
                // Nur erlaubte Apps starten
                boolean allowed = false;
                for (String pkg : ALLOWED_PACKAGES) {
                    if (pkg.equals(packageName)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) return;

                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (intent != null) {
                        startActivity(intent);
                    }
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
    }

    /**
     * Vollbild-Modus: Versteckt Statusleiste und Navigationsleiste
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
        if (hasFocus) {
            hideSystemUI();
        }
    }

    /**
     * KIOSK-MODUS: Zurück-Button ist deaktiviert
     * Leevi kann nicht aus der App raus!
     */
    @Override
    public void onBackPressed() {
        // Wenn WebView zurück kann (z.B. innerhalb der App-Navigation), erlaube es
        if (webView.canGoBack()) {
            webView.goBack();
        }
        // Sonst: NICHTS tun - Leevi bleibt in der App
        // super.onBackPressed() wird NICHT aufgerufen!
    }

    /**
     * Home-Button abfangen: App bleibt im Vordergrund
     * (Funktioniert nur wenn App als Standard-Launcher gesetzt ist)
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true; // Event konsumieren, nichts passiert
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Wenn die App wieder in den Vordergrund kommt (z.B. nach YouTube)
     */
    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        // WebView fortsetzen
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }
}
