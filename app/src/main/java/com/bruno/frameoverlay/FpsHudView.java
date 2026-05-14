package com.bruno.frameoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

public class FpsHudView extends View {
    public interface Listener {
        void onHudDrag(float dx, float dy);
        void onHudScale(float factor);
        void onHudDoubleTap();
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Listener listener;
    private String text = "0/0";
    private boolean locked;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private boolean dragging;
    private boolean scaling;
    private float lastPinchDistance;
    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;
    private final int touchSlop;

    public FpsHudView(Context context) {
        super(context);
        touchSlop = dp(6);
        setWillNotDraw(false);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        bgPaint.setColor(0x6622384A);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1));
        strokePaint.setColor(0xDD7FEAFF);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(3));
        glowPaint.setColor(0x5537DFFF);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(dp(15));
        textPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        smallPaint.setColor(0xAA7FEAFF);
        smallPaint.setStrokeWidth(dp(1));
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setLocked(boolean locked) {
        this.locked = locked;
        invalidate();
    }

    public void setFps(double appFps, double aiFps) {
        int app = fpsInt(appFps);
        int ai = fpsInt(aiFps);
        String next = app + "/" + ai;
        if (!next.equals(text)) {
            text = next;
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        rect.set(dp(3), dp(3), w - dp(3), h - dp(3));
        canvas.drawRoundRect(rect, dp(9), dp(9), bgPaint);
        canvas.drawRoundRect(rect, dp(9), dp(9), glowPaint);
        canvas.drawRoundRect(rect, dp(9), dp(9), strokePaint);

        // Cantos em estilo sci-fi, inspirado no layout enviado pelo usuário.
        float c = dp(10);
        canvas.drawLine(rect.left, rect.top + c, rect.left, rect.top + dp(3), smallPaint);
        canvas.drawLine(rect.left + dp(3), rect.top, rect.left + c, rect.top, smallPaint);
        canvas.drawLine(rect.right - c, rect.top, rect.right - dp(3), rect.top, smallPaint);
        canvas.drawLine(rect.right, rect.top + dp(3), rect.right, rect.top + c, smallPaint);
        canvas.drawLine(rect.left, rect.bottom - c, rect.left, rect.bottom - dp(3), smallPaint);
        canvas.drawLine(rect.left + dp(3), rect.bottom, rect.left + c, rect.bottom, smallPaint);
        canvas.drawLine(rect.right - c, rect.bottom, rect.right - dp(3), rect.bottom, smallPaint);
        canvas.drawLine(rect.right, rect.bottom - c, rect.right, rect.bottom - dp(3), smallPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float y = h / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, w / 2f, y, textPaint);

        if (locked) {
            smallPaint.setColor(0xDD7FEAFF);
            smallPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(w - dp(9), dp(9), dp(3), smallPaint);
            smallPaint.setStyle(Paint.Style.STROKE);
            smallPaint.setColor(0xAA7FEAFF);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (event.getPointerCount() >= 2) {
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE) {
                float d = distance(event);
                if (lastPinchDistance > 1f && d > 1f && listener != null) {
                    float factor = d / lastPinchDistance;
                    if (factor > 0.80f && factor < 1.25f) listener.onHudScale(factor);
                }
                lastPinchDistance = d;
                scaling = true;
                return true;
            }
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downRawX = lastRawX = event.getRawX();
                downRawY = lastRawY = event.getRawY();
                dragging = false;
                scaling = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (locked || scaling) return true;
                float rawX = event.getRawX();
                float rawY = event.getRawY();
                float totalDx = rawX - downRawX;
                float totalDy = rawY - downRawY;
                if (!dragging && (Math.abs(totalDx) > touchSlop || Math.abs(totalDy) > touchSlop)) dragging = true;
                if (dragging && listener != null) listener.onHudDrag(rawX - lastRawX, rawY - lastRawY);
                lastRawX = rawX;
                lastRawY = rawY;
                return true;
            case MotionEvent.ACTION_UP:
                long now = SystemClock.uptimeMillis();
                float upX = event.getRawX();
                float upY = event.getRawY();
                boolean smallMove = Math.abs(upX - downRawX) <= touchSlop && Math.abs(upY - downRawY) <= touchSlop;
                boolean doubleTap = smallMove && now - lastTapTime < 360
                        && Math.abs(upX - lastTapX) <= dp(22)
                        && Math.abs(upY - lastTapY) <= dp(22);
                if (doubleTap && listener != null) {
                    listener.onHudDoubleTap();
                    lastTapTime = 0L;
                } else if (smallMove) {
                    lastTapTime = now;
                    lastTapX = upX;
                    lastTapY = upY;
                }
                dragging = false;
                scaling = false;
                lastPinchDistance = 0f;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                scaling = false;
                lastPinchDistance = 0f;
                return true;
        }
        return true;
    }

    private float distance(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private int fpsInt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) return 0;
        return (int) Math.round(value);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
