package com.bruno.frameoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

public class OverlayView extends View {
    private final Object lock = new Object();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect localSrc = new Rect();
    private final RectF dst = new RectF();

    private Bitmap currentBitmap;
    private Bitmap previousBitmap;
    private Rect frameSrcRect;
    private boolean hasFrame;
    private boolean fullscreenMirror = true;
    private boolean simpleInterpolation;
    private boolean debugHudVisible;
    private boolean lsfgRealRequested;
    private int frameMultiplier = 2;
    private float flowScale = 0.35f;
    private String status = "Aguardando frames...";
    private String detail = "";
    private String nativeStatus = "Native não configurado";
    private double beforeFps;
    private double afterFps;

    public OverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        // V3.4: hardware layer reduz custo de CPU/bateria no HUD e evita Canvas software em cima do jogo.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // V3.1: HUD minimalista; sem painel flutuante fixo desenhado por cima do jogo.
        panelPaint.setColor(0x22000000);
        linePaint.setColor(0xFF7FEAFF);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(12));
        textPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        setBackgroundColor(Color.TRANSPARENT);
        bitmapPaint.setAlpha(255);
        bitmapPaint.setFilterBitmap(false);
    }

    public void setFullscreenMirror(boolean enabled) {
        synchronized (lock) { fullscreenMirror = enabled; }
        postInvalidateOnAnimationCompat();
    }

    public void setSimpleInterpolation(boolean enabled) {
        synchronized (lock) { simpleInterpolation = enabled; }
        postInvalidateOnAnimationCompat();
    }

    public void setDebugHudVisible(boolean enabled) {
        synchronized (lock) { debugHudVisible = enabled; }
        postInvalidateOnAnimationCompat();
    }

    public void setLsfgControls(boolean lsfgRequested, int multiplier, float flow, String nativeInfo) {
        synchronized (lock) {
            lsfgRealRequested = lsfgRequested;
            frameMultiplier = Math.max(2, Math.min(8, multiplier));
            flowScale = Math.max(0.10f, Math.min(1.00f, flow));
            nativeStatus = nativeInfo != null ? nativeInfo : "";
        }
        postInvalidateOnAnimationCompat();
    }

    public void setFrame(Bitmap current, Bitmap previous, Rect srcRect, double beforeValue, double afterValue, String frameDetail) {
        synchronized (lock) {
            currentBitmap = current;
            previousBitmap = previous;
            frameSrcRect = srcRect;
            hasFrame = current != null && srcRect != null && !srcRect.isEmpty();
            beforeFps = beforeValue;
            afterFps = afterValue;
            detail = frameDetail != null ? frameDetail : "";
            status = "Capturando";
        }
        postInvalidateOnAnimationCompat();
    }

    /**
     * V3.4: modo econômico. Atualiza só o HUD sem manter/copyar bitmap fullscreen.
     * Útil quando Native/Vulkan está em pass-through e o espelho Java não precisa ser redesenhado.
     */
    public void setFpsOnly(double beforeValue, double afterValue, String frameDetail) {
        synchronized (lock) {
            beforeFps = beforeValue;
            afterFps = afterValue;
            detail = frameDetail != null ? frameDetail : "";
            status = "Capturando";
            hasFrame = true;
        }
        postInvalidateOnAnimationCompat();
    }

    public void clearFrame(String newStatus) {
        synchronized (lock) {
            currentBitmap = null;
            previousBitmap = null;
            frameSrcRect = null;
            hasFrame = false;
            beforeFps = 0.0;
            afterFps = 0.0;
            detail = "";
            status = newStatus != null ? newStatus : "Aguardando frames...";
        }
        postInvalidateOnAnimationCompat();
    }

    public void setStatus(String newStatus) {
        synchronized (lock) { status = newStatus != null ? newStatus : ""; }
        postInvalidateOnAnimationCompat();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // V3.0: não usamos CLEAR em tela cheia. Em alguns firmwares isso escurecia o app quando o overlay começava a receber frames.

        Bitmap current;
        Bitmap previous;
        Rect src;
        boolean drawFrame;
        String statusCopy;
        String detailCopy;
        String nativeCopy;
        double beforeCopy;
        double afterCopy;
        boolean fullscreenCopy;
        boolean interpolationCopy;
        boolean hudCopy;
        boolean lsfgCopy;
        int multiplierCopy;
        float flowCopy;

        synchronized (lock) {
            current = currentBitmap;
            previous = previousBitmap;
            src = frameSrcRect != null ? new Rect(frameSrcRect) : null;
            drawFrame = hasFrame && current != null && !current.isRecycled() && src != null;
            statusCopy = status;
            detailCopy = detail;
            nativeCopy = nativeStatus;
            beforeCopy = beforeFps;
            afterCopy = afterFps;
            fullscreenCopy = fullscreenMirror;
            interpolationCopy = simpleInterpolation;
            hudCopy = debugHudVisible;
            lsfgCopy = lsfgRealRequested;
            multiplierCopy = frameMultiplier;
            flowCopy = flowScale;
        }

        boolean transparentLsfgPassThrough = drawFrame && fullscreenCopy && lsfgCopy;

        if (drawFrame && !transparentLsfgPassThrough) {
            localSrc.set(src);
            if (fullscreenCopy) {
                dst.set(0, 0, getWidth(), getHeight());
            } else {
                float margin = dp(12);
                float previewW = Math.min(getWidth() - margin * 2, dp(360));
                float aspect = src.height() > 0 ? (src.width() / (float) src.height()) : 1.777f;
                float previewH = previewW / Math.max(0.1f, aspect);
                dst.set(margin, getHeight() - previewH - margin, margin + previewW, getHeight() - margin);
                Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(0x22000000);
                canvas.drawRoundRect(dst.left - dp(4), dst.top - dp(4), dst.right + dp(4), dst.bottom + dp(4), dp(8), dp(8), bg);
            }
            try {
                if (interpolationCopy && !lsfgCopy && previous != null && !previous.isRecycled()) {
                    int prevAlpha = Math.max(8, Math.min(28, (int) (flowCopy * 42f)));
                    drawBitmapWithAlpha(canvas, previous, localSrc, dst, prevAlpha);
                    drawBitmapWithAlpha(canvas, current, localSrc, dst, 255);
                } else {
                    drawBitmapWithAlpha(canvas, current, localSrc, dst, 255);
                }
            } catch (Throwable ignored) { }
        }

        // V3.7: o contador FPS agora é uma janela própria, móvel/redimensionável.
        // Este overlay fica só para mirror/pass-through; não desenha mais o quadradinho de FPS aqui.

        if (drawFrame && interpolationCopy && !lsfgCopy) {
            postInvalidateDelayed(10);
        }
    }

    private void drawBitmapWithAlpha(Canvas canvas, Bitmap bitmap, Rect src, RectF dstRect, int alpha) {
        bitmapPaint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, src, dstRect, bitmapPaint);
        bitmapPaint.setAlpha(255);
    }

    private void drawMinimalFpsHud(Canvas canvas, double appFps, double aiFps) {
        int app = fpsInt(appFps);
        int ai = fpsInt(aiFps);
        String text = app + "/" + ai;

        textPaint.setTextSize(dp(14));
        textPaint.setShadowLayer(4f, 1f, 1f, Color.BLACK);
        float padX = dp(7);
        float padY = dp(5);
        float textW = textPaint.measureText(text);
        float h = dp(24);
        float w = Math.max(dp(58), textW + padX * 2);
        float left = dp(8);
        float top = dp(8);

        panelPaint.setColor(0x33000000);
        canvas.drawRoundRect(left, top, left + w, top + h, dp(8), dp(8), panelPaint);
        canvas.drawText(text, left + padX, top + dp(17), textPaint);
        panelPaint.setColor(0x22000000);
    }

    private int fpsInt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) return 0;
        return (int) Math.round(value);
    }

    private void postInvalidateOnAnimationCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 16) postInvalidateOnAnimation(); else postInvalidate();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
