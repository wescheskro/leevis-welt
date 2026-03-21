package com.leevi.welt;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * OverlayService — Single floating "Zurück" button (bottom-left).
 *
 * Big red circle with a back arrow. Tap = return to Leevis Welt home.
 * Uses SYSTEM_ALERT_WINDOW permission (TYPE_APPLICATION_OVERLAY on Android 8+).
 */
public class OverlayService extends Service {

    private WindowManager windowManager;
    private TextView backButton;

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
        int btnSize = dpToPx(72);

        // --- Big red circle button with back arrow ---
        backButton = new TextView(this);
        backButton.setText("←");
        backButton.setTextColor(Color.WHITE);
        backButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        backButton.setTypeface(Typeface.DEFAULT_BOLD);
        backButton.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColors(new int[]{0xFFE74C3C, 0xFFC0392B});
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        bg.setStroke(dpToPx(3), Color.argb(80, 255, 255, 255));
        backButton.setBackground(bg);
        backButton.setElevation(dpToPx(12));

        // Touch feedback
        backButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.7f);
                    v.setScaleX(0.9f);
                    v.setScaleY(0.9f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    break;
            }
            return false;
        });

        // Click = return home
        backButton.setOnClickListener(v -> returnToApp());

        // --- Window params: bottom-left corner ---
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            btnSize,
            btnSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.LEFT;
        params.x = dpToPx(16);  // 16dp from left edge
        params.y = dpToPx(16);  // 16dp from bottom edge

        windowManager.addView(backButton, params);
    }

    private void returnToApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("overlay_action", "home");
        startActivity(intent);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (backButton != null && windowManager != null) {
            try {
                windowManager.removeView(backButton);
            } catch (Exception e) {}
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
