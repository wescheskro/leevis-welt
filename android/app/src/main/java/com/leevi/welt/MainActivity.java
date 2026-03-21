package com.leevi.welt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
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

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Leevis Welt - Launcher App
 *
 * Tablet-Launcher fuer Leevi. Laedt die Web-App von GitHub Pages.
 * YouTube laeuft in einem zweiten WebView mit nativen Overlay-Buttons.
 * Kiosk-Modus verhindert Verlassen. Rotation erlaubt.
 *
 * Die AppBridge stellt alle nativen Funktionen bereit, die die Web-App
 * nicht selbst kann. So muss die APK nur selten aktualisiert werden.
 */
public class MainActivity extends Activity {

    private static final String TAG = "LeevisWelt";
    private static final String APP_URL = "https://wescheskro.github.io/leevis-welt/leevis-app.html";
    private static final String PREFS_NAME = "leevis_web";

    private FrameLayout rootLayout;
    private WebView webView;
    private WebView ytWebView;
    private LinearLayout ytOverlay;
    private boolean ytModeActive = false;

    // Native TTS
    private TextToSpeech tts;
    private boolean ttsReady = false;

    // Screen time limit
    private Handler timeLimitHandler;
    private Runnable timeLimitChecker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        WebView.setWebContentsDebuggingEnabled(true);

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

        webView.addJavascriptInterface(new AppBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("app://youtube")) {
                    String target = url.replace("app://youtube", "").replace("-", "");
                    enterYouTubeMode(target);
                    return true;
                }
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    enterYouTubeMode("");
                    return true;
                }
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

        // ===== 2. YouTube WebView =====
        ytWebView = new WebView(this);
        configureWebView(ytWebView);
        CookieManager.getInstance().setAcceptThirdPartyCookies(ytWebView, true);
        ytWebView.setVisibility(View.GONE);
        rootLayout.addView(ytWebView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ytWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.contains("youtube.com") || url.contains("youtu.be") || url.contains("google.com")) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideSystemUI();
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

        // ===== 3. Overlay Button Bar =====
        buildOverlayBar();

        // ===== 4. Native TTS =====
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.GERMAN);
                ttsReady = true;
                Log.d(TAG, "TTS ready");
            }
        });

        // ===== 5. Screen time limit handler =====
        timeLimitHandler = new Handler(Looper.getMainLooper());

        // Load app
        webView.clearCache(true);
        webView.loadUrl(APP_URL);
    }

    // ==================== WebView Config ====================

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
        String ua = settings.getUserAgentString();
        settings.setUserAgentString(ua + " LeevisWeltApp/1.0");
    }

    // ==================== YouTube Mode ====================

    private void buildOverlayBar() {
        int barHeight = dpToPx(80);
        ytOverlay = new LinearLayout(this);
        ytOverlay.setOrientation(LinearLayout.HORIZONTAL);
        ytOverlay.setGravity(Gravity.CENTER_VERTICAL);
        ytOverlay.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
        ytOverlay.setVisibility(View.GONE);

        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(240, 15, 15, 26));
        barBg.setCornerRadii(new float[]{
            dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16), 0, 0, 0, 0
        });
        ytOverlay.setBackground(barBg);
        ytOverlay.setElevation(dpToPx(8));

        TextView peppaBtn = makeOverlayButton("\uD83D\uDC37\nPeppa", new int[]{0xFFE91E63, 0xFFC2185B});
        peppaBtn.setOnClickListener(v ->
            ytWebView.loadUrl("https://m.youtube.com/results?search_query=Peppa+Pig+Deutsch"));

        TextView moodBtn = makeOverlayButton("\uD83D\uDE0A\nStimmung", new int[]{0xFFFF9800, 0xFFE65100});
        moodBtn.setOnClickListener(v -> {
            exitYouTubeMode();
            webView.evaluateJavascript("toggleMoodPicker()", null);
        });

        TextView backBtn = makeOverlayButton("\u2190 Zur\u00fcck", new int[]{0xFFE74C3C, 0xFFC0392B});
        backBtn.setOnClickListener(v -> exitYouTubeMode());

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp1.setMargins(dpToPx(3), 0, dpToPx(3), 0);
        LinearLayout.LayoutParams lp15 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f);
        lp15.setMargins(dpToPx(3), 0, dpToPx(3), 0);

        ytOverlay.addView(peppaBtn, lp1);
        ytOverlay.addView(moodBtn, lp1);
        ytOverlay.addView(backBtn, lp15);

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, barHeight
        );
        overlayParams.gravity = Gravity.BOTTOM;
        rootLayout.addView(ytOverlay, overlayParams);
    }

    private TextView makeOverlayButton(String text, int[] gradientColors) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));

        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, gradientColors);
        bg.setCornerRadius(dpToPx(14));
        btn.setBackground(bg);
        btn.setElevation(dpToPx(4));
        return btn;
    }

    private void enterYouTubeMode(String target) {
        ytModeActive = true;
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
        ytWebView.setVisibility(View.VISIBLE);
        ytWebView.loadUrl(url);
        ytOverlay.setVisibility(View.VISIBLE);
        hideSystemUI();
        Log.d(TAG, "YouTube mode entered: " + url);
    }

    private void exitYouTubeMode() {
        ytModeActive = false;
        ytWebView.loadUrl("about:blank");
        ytWebView.setVisibility(View.GONE);
        ytOverlay.setVisibility(View.GONE);
        hideSystemUI();
        Log.d(TAG, "YouTube mode exited");
    }

    // ==================== JAVASCRIPT BRIDGE ====================

    public class AppBridge {

        // ===== APP MANAGEMENT =====

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

        /** Launch ANY installed app by package name (no restrictions) */
        @JavascriptInterface
        public void launchApp(String packageName) {
            runOnUiThread(() -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (intent != null) {
                        startActivity(intent);
                        showFloatingOverlay();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch: " + packageName, e);
                }
            });
        }

        /** Check if an app is installed */
        @JavascriptInterface
        public boolean isAppInstalled(String packageName) {
            try {
                getPackageManager().getPackageInfo(packageName, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        /** Get the display name of an installed app */
        @JavascriptInterface
        public String getAppName(String packageName) {
            try {
                return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)
                ).toString();
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }

        /** Get all launchable apps as JSON array [{pkg, name}] */
        @JavascriptInterface
        public String getInstalledApps() {
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = getPackageManager().queryIntentActivities(mainIntent, 0);
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (ResolveInfo ri : apps) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                String name = ri.loadLabel(getPackageManager()).toString();
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"pkg\":\"").append(pkg.replace("\"", "\\\""))
                  .append("\",\"name\":\"").append(name.replace("\"", "\\\""))
                  .append("\"}");
            }
            sb.append("]");
            return sb.toString();
        }

        /** Get app icon as base64 data URI */
        @JavascriptInterface
        public String getAppIcon(String packageName) {
            try {
                Drawable icon = getPackageManager().getApplicationIcon(packageName);
                Bitmap bmp;
                if (icon instanceof BitmapDrawable) {
                    bmp = ((BitmapDrawable) icon).getBitmap();
                } else {
                    bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    icon.setBounds(0, 0, 96, 96);
                    icon.draw(canvas);
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
                String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                return "data:image/png;base64," + b64;
            } catch (Exception e) {
                return "";
            }
        }

        /** Open system uninstall dialog for an app */
        @JavascriptInterface
        public void uninstallApp(String packageName) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_DELETE);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                } catch (Exception e) {}
            });
        }

        /** Open system app settings for a specific app */
        @JavascriptInterface
        public void openAppSettings(String packageName) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                } catch (Exception e) {}
            });
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

        // ===== DEVICE INFO =====

        /** Get battery level 0-100 */
        @JavascriptInterface
        public int getBatteryLevel() {
            try {
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent battery = registerReceiver(null, ifilter);
                if (battery != null) {
                    int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    return (int) (level * 100f / scale);
                }
            } catch (Exception e) {}
            return -1;
        }

        /** Check if battery is charging */
        @JavascriptInterface
        public boolean isBatteryCharging() {
            try {
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent battery = registerReceiver(null, ifilter);
                if (battery != null) {
                    int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL;
                }
            } catch (Exception e) {}
            return false;
        }

        /** Get WiFi status as JSON {connected, ssid, signalLevel} */
        @JavascriptInterface
        public String getWifiStatus() {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm.getActiveNetworkInfo();
                boolean connected = ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
                String ssid = "";
                int signal = 0;
                if (connected) {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wi = wm.getConnectionInfo();
                    ssid = wi.getSSID().replace("\"", "");
                    signal = WifiManager.calculateSignalLevel(wi.getRssi(), 5);
                }
                return "{\"connected\":" + connected + ",\"ssid\":\"" + ssid.replace("\"", "\\\"") +
                       "\",\"signalLevel\":" + signal + "}";
            } catch (Exception e) {
                return "{\"connected\":false,\"ssid\":\"\",\"signalLevel\":0}";
            }
        }

        /** Get storage info as JSON {totalMB, freeMB} */
        @JavascriptInterface
        public String getStorageInfo() {
            try {
                StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
                long totalBytes = stat.getTotalBytes();
                long freeBytes = stat.getAvailableBytes();
                return "{\"totalMB\":" + (totalBytes / 1048576) + ",\"freeMB\":" + (freeBytes / 1048576) + "}";
            } catch (Exception e) {
                return "{\"totalMB\":0,\"freeMB\":0}";
            }
        }

        /** Get device info as JSON */
        @JavascriptInterface
        public String getDeviceInfo() {
            String model = Build.MODEL;
            String androidVersion = Build.VERSION.RELEASE;
            int sdkInt = Build.VERSION.SDK_INT;
            String appVersion = getAppVersionString();
            int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
            int heightDp = (int) (getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
            return "{\"model\":\"" + model.replace("\"", "\\\"") +
                   "\",\"androidVersion\":\"" + androidVersion +
                   "\",\"sdkInt\":" + sdkInt +
                   ",\"appVersion\":\"" + appVersion +
                   "\",\"screenWidthDp\":" + widthDp +
                   ",\"screenHeightDp\":" + heightDp + "}";
        }

        // ===== DEVICE CONTROL =====

        /** Set screen brightness (0-255), only affects this window */
        @JavascriptInterface
        public void setBrightness(int level) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = Math.max(0, Math.min(255, level)) / 255f;
                getWindow().setAttributes(lp);
            });
        }

        /** Get current screen brightness (0-255) */
        @JavascriptInterface
        public int getBrightness() {
            float b = getWindow().getAttributes().screenBrightness;
            if (b < 0) b = 0.5f; // system default
            return (int) (b * 255);
        }

        /** Set media volume (0 to maxVolume) */
        @JavascriptInterface
        public void setVolume(int level) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, level)), 0);
            } catch (Exception e) {}
        }

        /** Get current media volume */
        @JavascriptInterface
        public int getVolume() {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                return am.getStreamVolume(AudioManager.STREAM_MUSIC);
            } catch (Exception e) {
                return 0;
            }
        }

        /** Get max media volume */
        @JavascriptInterface
        public int getMaxVolume() {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            } catch (Exception e) {
                return 15;
            }
        }

        /** Show a native toast message */
        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        /** Vibrate for given milliseconds (max 2000ms) */
        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                int duration = Math.max(1, Math.min(2000, ms));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(duration);
                }
            } catch (Exception e) {}
        }

        /** Vibrate with pattern, JSON array e.g. [0,100,50,100] */
        @JavascriptInterface
        public void vibratePattern(String patternJson) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                org.json.JSONArray arr = new org.json.JSONArray(patternJson);
                long[] pattern = new long[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    pattern[i] = Math.min(2000, arr.getLong(i));
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            } catch (Exception e) {}
        }

        // ===== NATIVE TTS =====

        /** Speak text using native TTS (more reliable than WebSpeech API) */
        @JavascriptInterface
        public void ttsSpeak(String text, String lang, float rate) {
            if (!ttsReady || tts == null) return;
            try {
                if (lang != null && !lang.isEmpty()) {
                    tts.setLanguage(Locale.forLanguageTag(lang));
                }
                tts.setSpeechRate(Math.max(0.25f, Math.min(3f, rate)));
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "leevis_tts");
            } catch (Exception e) {}
        }

        /** Stop TTS */
        @JavascriptInterface
        public void ttsStop() {
            if (tts != null) tts.stop();
        }

        /** Check if TTS is currently speaking */
        @JavascriptInterface
        public boolean ttsIsSpeaking() {
            return tts != null && tts.isSpeaking();
        }

        /** Get available TTS languages as JSON array ["de-DE","en-US",...] */
        @JavascriptInterface
        public String ttsGetLanguages() {
            if (!ttsReady || tts == null) return "[]";
            try {
                Set<Locale> locales = tts.getAvailableLanguages();
                if (locales == null) return "[]";
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Locale loc : locales) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(loc.toLanguageTag()).append("\"");
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        // ===== SCREEN TIME =====

        /** Set daily screen time limit in minutes (0 = disabled) */
        @JavascriptInterface
        public void setTimeLimitMinutes(int minutes) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putInt("time_limit_minutes", Math.max(0, minutes)).apply();
            startTimeLimitChecker();
        }

        /** Get current time limit setting */
        @JavascriptInterface
        public int getTimeLimitMinutes() {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt("time_limit_minutes", 0);
        }

        // ===== SETTINGS & SYSTEM =====

        /** Open Android device settings */
        @JavascriptInterface
        public void openDeviceSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                } catch (Exception e) {}
            });
        }

        /** Open WiFi settings directly */
        @JavascriptInterface
        public void openWifiSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception e) {}
            });
        }

        /** Force reload the web app (clear cache) */
        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(() -> {
                webView.clearCache(true);
                webView.loadUrl(APP_URL);
            });
        }

        /** Get APK version string */
        @JavascriptInterface
        public String getAppVersion() {
            return getAppVersionString();
        }

        /** Close the app completely */
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> {
                if (tts != null) { tts.stop(); tts.shutdown(); }
                finishAffinity();
            });
        }

        // ===== PERSISTENT STORAGE =====
        // More reliable than localStorage (survives WebView cache clears)

        /** Save a key-value setting natively */
        @JavascriptInterface
        public void saveSetting(String key, String value) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString("web_" + key, value).apply();
        }

        /** Load a setting (returns empty string if not found) */
        @JavascriptInterface
        public String loadSetting(String key) {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString("web_" + key, "");
        }
    }

    // ==================== Screen Time Limit ====================

    private void startTimeLimitChecker() {
        if (timeLimitChecker != null) {
            timeLimitHandler.removeCallbacks(timeLimitChecker);
        }
        int limitMinutes = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt("time_limit_minutes", 0);
        if (limitMinutes <= 0) return;

        // Store session start if not set
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getLong("session_start", 0) == 0) {
            prefs.edit().putLong("session_start", System.currentTimeMillis()).apply();
        }

        timeLimitChecker = new Runnable() {
            @Override
            public void run() {
                SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                int limit = p.getInt("time_limit_minutes", 0);
                if (limit <= 0) return;
                long start = p.getLong("session_start", System.currentTimeMillis());
                long elapsed = (System.currentTimeMillis() - start) / 60000;
                if (elapsed >= limit) {
                    runOnUiThread(() -> {
                        webView.evaluateJavascript(
                            "if(typeof onTimeLimitReached==='function')onTimeLimitReached(" + elapsed + "," + limit + ")",
                            null);
                    });
                }
                timeLimitHandler.postDelayed(this, 60000); // check every minute
            }
        };
        timeLimitHandler.postDelayed(timeLimitChecker, 60000);
    }

    // ==================== Helpers ====================

    private String getAppVersionString() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ==================== System UI ====================

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

    @Override
    public void onBackPressed() {
        if (ytModeActive) {
            if (ytWebView.canGoBack()) {
                ytWebView.goBack();
            } else {
                exitYouTubeMode();
            }
            return;
        }
        webView.evaluateJavascript(
            "(function(){ if(typeof currentScreen !== 'undefined' && currentScreen !== 'home'){ go('home'); return 'navigated'; } return 'home'; })()",
            value -> {}
        );
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) return true;
        return super.onKeyDown(keyCode, event);
    }

    // ==================== Floating Overlay ====================

    /** Show floating back-button overlay on top of other apps */
    private void showFloatingOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted, requesting...");
            try {
                Intent permIntent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(permIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to request overlay permission", e);
            }
            return;
        }
        // Delay slightly so the external app has time to come to foreground
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent svc = new Intent(MainActivity.this, OverlayService.class);
                startService(svc);
                Log.d(TAG, "OverlayService started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start OverlayService", e);
            }
        }, 500);
    }

    /** Hide floating overlay (called when returning to app) */
    private void hideFloatingOverlay() {
        try {
            stopService(new Intent(this, OverlayService.class));
        } catch (Exception e) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Handle return from OverlayService
        if (intent != null && intent.hasExtra("overlay_action")) {
            String action = intent.getStringExtra("overlay_action");
            hideFloatingOverlay();
            if ("mood".equals(action) && webView != null) {
                webView.evaluateJavascript("toggleMoodPicker()", null);
            }
            // "home" just brings us back (already done by the intent)
        }
    }

    // ==================== Lifecycle ====================

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        hideFloatingOverlay();
        if (webView != null) webView.onResume();
        if (ytWebView != null && ytModeActive) ytWebView.onResume();
        startTimeLimitChecker();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        if (ytWebView != null) ytWebView.onPause();
        if (timeLimitHandler != null && timeLimitChecker != null) {
            timeLimitHandler.removeCallbacks(timeLimitChecker);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (timeLimitHandler != null && timeLimitChecker != null) {
            timeLimitHandler.removeCallbacks(timeLimitChecker);
        }
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

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
