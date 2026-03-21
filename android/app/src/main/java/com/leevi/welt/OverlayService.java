package com.leevi.welt;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * OverlayService — Floating button bar that stays on top of ALL apps.
 *
 * Shows 3 buttons when Leevi is in an external app (YouTube etc.):
 *   🐷 Peppa   — Opens YouTube with Peppa Pig search
 *   😊 Stimmung — Returns to Leevis Welt and opens mood picker
 *   ← Zurück   — Returns to Leevis Welt home screen
 *
 * Uses SYSTEM_ALERT_WINDOW permission (TYPE_APPLICATION_OVERLAY on Android 8+).
 * Motor Planning: buttons are ALWAYS in the same position (bottom of screen).
 */
public class OverlayService extends Service {

    private WindowManager windowManager;
    private LinearLayout overlayBar;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlay();
    }

    private void buildOverlay() {
        int barHeight = dpToPx(80);

        // --- Container bar ---
        overlayBar = new LinearLayout(this);
        overlayBar.setOrientation(LinearLayout.HORIZONTAL);
        overlayBar.setGravity(Gravity.CENTER_VERTICAL);
        overlayBar.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));

        // Dark background with rounded top corners
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(245, 15, 15, 26));
        barBg.setCornerRadii(new float[]{
            dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16), 0, 0, 0, 0
        });
        barBg.setStroke(dpToPx(2), Color.argb(60, 255, 255, 255));
        overlayBar.setBackground(barBg);
        overlayBar.setElevation(dpToPx(12));

        // --- 🐷 Peppa Button ---
        TextView peppaBtn = makeButton("🐷\nPeppa", new int[]{0xFFE91E63, 0xFFC2185B});
        peppaBtn.setOnClickListener(v -> {
            // Open YouTube with Peppa Pig search
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=Peppa+Pig+Deutsch"));
                intent.setPackage("com.google.android.youtube");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                // Fallback: open in any browser
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://m.youtube.com/results?search_query=Peppa+Pig+Deutsch"));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e2) {}
            }
        });

        // --- 😊 Stimmung Button ---
        TextView moodBtn = makeButton("😊\nStimmung", new int[]{0xFFFF9800, 0xFFE65100});
        moodBtn.setOnClickListener(v -> {
            // Return to Leevis Welt and open mood picker
            returnToApp("mood");
        });

        // --- ← Zurück Button (bigger, red) ---
        TextView backBtn = makeButton("← Zurück", new int[]{0xFFE74C3C, 0xFFC0392B});
        backBtn.setOnClickListener(v -> {
            // Return to Leevis Welt home
            returnToApp("home");
        });

        // Layout weights: Peppa=1, Stimmung=1, Zurück=1.5
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp1.setMargins(dpToPx(3), 0, dpToPx(3), 0);

        LinearLayout.LayoutParams lp15 = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f);
        lp15.setMargins(dpToPx(3), 0, dpToPx(3), 0);

        overlayBar.addView(peppaBtn, lp1);
        overlayBar.addView(moodBtn, lp1);
        overlayBar.addView(backBtn, lp15);

        // --- Window params: overlay on top of everything ---
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            barHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM;

        windowManager.addView(overlayBar, params);
    }

    /**
     * Return to Leevis Welt app
     * @param action "home" to go to home screen, "mood" to open mood picker
     */
    private void returnToApp(String action) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("overlay_action", action);
        startActivity(intent);

        // Stop the overlay service
        stopSelf();
    }

    /**
     * Create a styled button for the overlay bar
     */
    private TextView makeButton(String text, int[] gradientColors) {
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

        // Touch feedback
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.8f);
                    v.setScaleX(0.95f);
                    v.setScaleY(0.95f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    break;
            }
            return false; // let onClick also fire
        });

        return btn;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayBar != null && windowManager != null) {
            try {
                windowManager.removeView(overlayBar);
            } catch (Exception e) {}
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
