package com.bruno.frameoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public class ControlBubbleView extends View {
    public interface Listener {
        void onBubbleClick();
        void onBubbleDrag(float dx, float dy);
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint decoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Listener listener;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private boolean dragging;
    private boolean opened;
    private final int touchSlop;

    public ControlBubbleView(Context context) {
        super(context);
        touchSlop = dp(6);
        bgPaint.setColor(0xCC101820);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1));
        strokePaint.setColor(0xEE7FEAFF);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(3));
        glowPaint.setColor(0x6637DFFF);
        decoPaint.setColor(0xCC7FEAFF);
        decoPaint.setStrokeWidth(dp(1));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(dp(15));
        textPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setOpened(boolean opened) {
        this.opened = opened;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        rect.set(dp(4), dp(4), w - dp(4), h - dp(4));
        float radius = dp(14);
        canvas.drawRoundRect(rect, radius, radius, bgPaint);
        canvas.drawRoundRect(rect, radius, radius, glowPaint);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);

        float cx = w / 2f;
        float cy = h / 2f;
        decoPaint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, dp(16), decoPaint);
        canvas.drawCircle(cx, cy, dp(22), decoPaint);
        decoPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(rect.left + dp(10), rect.top + dp(10), dp(2), decoPaint);
        canvas.drawCircle(rect.right - dp(10), rect.top + dp(10), dp(2), decoPaint);
        canvas.drawCircle(rect.left + dp(10), rect.bottom - dp(10), dp(2), decoPaint);
        canvas.drawCircle(rect.right - dp(10), rect.bottom - dp(10), dp(2), decoPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(opened ? "×" : "B", cx, textY, textPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = lastRawX = event.getRawX();
                downRawY = lastRawY = event.getRawY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float rawX = event.getRawX();
                float rawY = event.getRawY();
                float totalDx = rawX - downRawX;
                float totalDy = rawY - downRawY;
                if (!dragging && (Math.abs(totalDx) > touchSlop || Math.abs(totalDy) > touchSlop)) dragging = true;
                if (dragging && listener != null) listener.onBubbleDrag(rawX - lastRawX, rawY - lastRawY);
                lastRawX = rawX;
                lastRawY = rawY;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging && listener != null) listener.onBubbleClick();
                dragging = false;
                return true;
        }
        return true;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
