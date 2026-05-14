package com.bruno.frameoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;

public class CaptureService extends Service {
    public static final String ACTION_START = "com.bruno.frameoverlay.START";
    public static final String ACTION_STOP = "com.bruno.frameoverlay.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_FULLSCREEN_MIRROR = "fullscreen_mirror";
    public static final String EXTRA_SIMPLE_INTERPOLATION = "simple_interpolation";
    public static final String EXTRA_DEBUG_HUD = "debug_hud";
    public static final String EXTRA_LSFG_REAL = "lsfg_real";
    public static final String EXTRA_MULTIPLIER = "multiplier";
    public static final String EXTRA_FLOW_SCALE = "flow_scale";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_AUTO_LAUNCH = "auto_launch";

    private static final String CHANNEL_ID = "bruno_frame_overlay_capture";
    private static final int NOTIFICATION_ID = 11;
    private static final int MAX_IMAGES = 2; // V3.4: reduz fila de buffers e consumo de RAM/bateria.
    private static final long MIN_FRAME_INTERVAL_MS = 0L; // V3.0: não limita FPS por throttling artificial.
    private static final int MAX_CONSECUTIVE_BLACK_DROPS = 45;
    private static final long PANEL_STATS_INTERVAL_MS = 1300L; // V3.4: painel consome menos bateria.
    private static final long FIRST_FRAME_WATCHDOG_MS = 850L;
    private static final long FIRST_FRAME_SOFT_ACCEPT_MS = 1400L;
    private static final long PRIME_SURFACE_DELAY_MS = 70L;
    private static final int MAX_FIRST_FRAME_PRIMES = 3;
    private static final long FPS_STABLE_WINDOW_MS = 1000L;
    private static final long HUD_FPS_DISPLAY_INTERVAL_MS = 1200L; // V3.4: HUD mais estável e com menos invalidações.
    private static final long NATIVE_PARAM_APPLY_DEBOUNCE_MS = 700L;
    private static final double CAPTURE_30_LOCK_LOW = 24.0;
    private static final double CAPTURE_30_LOCK_HIGH = 34.5;
    private static final long ECO_NATIVE_BITMAP_COPY_INTERVAL_MS = 2500L; // V3.7: eficiência equilibrada; não congela diagnóstico/IA por tempo longo.
    private static final long ECO_NATIVE_PANEL_INTERVAL_MS = 1700L;
    private static final long ECO_NATIVE_HUD_INTERVAL_MS = 900L;
    private static final long ECO_IMAGE_FPS_SAMPLE_INTERVAL_MS = 900L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Rect frameSrcRect = new Rect();
    private final Rect frameDstRect = new Rect();
    private final Paint copyPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    private HandlerThread captureThread;
    private Handler captureHandler;
    private WindowManager windowManager;
    private DisplayManager displayManager;
    private MediaProjectionManager projectionManager;

    private OverlayView overlayView;
    private ControlBubbleView bubbleView;
    private ControlPanelView panelView;
    private FpsHudView fpsHudView;
    private WindowManager.LayoutParams overlayParams;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams fpsHudParams;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private Bitmap stagingBitmap;
    private int stagingPaddedWidth;
    private int stagingHeight;
    private final Bitmap[] frameBuffers = new Bitmap[3];
    private int currentFrameIndex = -1;
    private int previousFrameIndex = -1;
    private int frameBufferWidth;
    private int frameBufferHeight;

    private int captureWidth;
    private int captureHeight;
    private int captureDensity;
    private boolean hasSeenNonBlackFrame;
    private boolean receiverRegistered;
    private boolean fullscreenMirror = true;
    private boolean simpleInterpolation = true;
    private boolean debugHudVisible = true;
    private boolean lsfgRealRequested = false;
    private int frameMultiplier = 2;
    private float flowScale = 0.35f;
    private String targetPackage = "";
    private boolean autoLaunchTarget = true;
    private boolean targetAutoLaunched;
    private String nativeStatus = "Native não configurado";
    private int consecutiveBlackFrames;
    private long lastFrameCopyTime;
    private long frameCounter;
    private long fpsWindowStartMs;
    private long lastFpsFrameTimeMs;
    private long lastImageTimestampNs;
    private long lastPanelStatsPostMs;
    private long captureStartMs;
    private long lastAnyImageTimeMs;
    private int firstFramePrimeAttempts;
    private double currentFps;
    private double appliedFps;
    private double displayedAppFps;
    private double displayedAppliedFps;
    private long lastHudFpsDisplayMs;
    private double displayRefreshFps = 60.0;
    private double rawCaptureFps;
    private double correctedAppFps;
    private boolean captureRateLimited30;
    private SurfaceFlingerFpsReader surfaceFlingerFpsReader;
    private double surfaceFlingerFps;
    private String surfaceFlingerStatus = "SurfaceFlinger FPS: aguardando";
    private boolean usingSurfaceFlingerFps;
    private int renderScalePercent = 85;
    private boolean batterySaverEnabled = true;
    private int screenWidth;
    private int screenHeight;
    private long lastEcoBitmapCopyMs;
    private long lastEcoPanelStatsMs;
    private long lastEcoHudPostMs;
    private long lastEcoFpsSampleMs;
    private float fpsHudScale = 1.0f;
    private boolean fpsHudLocked;

    private final Runnable delayedRecreateRunnable = new Runnable() {
        @Override public void run() { recreateCapture("display refresh"); }
    };

    private final Runnable applyNativeParamsRunnable = new Runnable() {
        @Override public void run() {
            if (!lsfgRealRequested) return;
            nativeStatus = NativeLsfgBridge.updateRuntimeParams(CaptureService.this, frameMultiplier, flowScale, renderScalePercent, batterySaverEnabled, true);
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (overlayView != null) overlayView.setLsfgControls(lsfgRealRequested, frameMultiplier, flowScale, nativeStatus);
                    if (panelView != null) panelView.setStats(buildRuntimeStats());
                }
            });
        }
    };

    private final Runnable firstFrameWatchdogRunnable = new Runnable() {
        @Override public void run() {
            if (mediaProjection == null || captureHandler == null || hasSeenNonBlackFrame) return;
            firstFramePrimeAttempts++;
            String msg = "Inicializando captura • Android 15/HyperOS safe mode " + firstFramePrimeAttempts + "/" + MAX_FIRST_FRAME_PRIMES;
            clearOverlay(msg);
            updatePanelStats(msg);
            pulseVirtualDisplaySurface();
            if (!hasSeenNonBlackFrame && firstFramePrimeAttempts < MAX_FIRST_FRAME_PRIMES) {
                captureHandler.postDelayed(firstFrameWatchdogRunnable, FIRST_FRAME_WATCHDOG_MS);
            } else if (!hasSeenNonBlackFrame) {
                scheduleRecreate("prime sem primeiro frame inicial");
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) scheduleRecreate("display changed");
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)
                    || Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                scheduleRecreate(action);
            }
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    cleanupAll();
                    stopSelf();
                }
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        CrashLogger.install(this);
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        captureThread = new HandlerThread("BrunoFrameOverlay-Capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        if (displayManager != null) displayManager.registerDisplayListener(displayListener, mainHandler);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver, filter);
            receiverRegistered = true;
        } catch (Throwable t) {
            CrashLogger.writeCrash(this, Thread.currentThread(), t);
            receiverRegistered = false;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            cleanupAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (!canDrawOverlay()) {
                Toast.makeText(this, "Permissão de sobreposição não liberada.", Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= 33) resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            else resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode == 0 || resultData == null || projectionManager == null) {
                Toast.makeText(this, "Dados de MediaProjection inválidos.", Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            fullscreenMirror = intent.getBooleanExtra(EXTRA_FULLSCREEN_MIRROR, true);
            simpleInterpolation = intent.getBooleanExtra(EXTRA_SIMPLE_INTERPOLATION, true);
            debugHudVisible = intent.getBooleanExtra(EXTRA_DEBUG_HUD, true);
            frameMultiplier = clamp(intent.getIntExtra(EXTRA_MULTIPLIER, LsfgConfig.getMultiplier(this)), 2, 8);
            flowScale = clamp(intent.getFloatExtra(EXTRA_FLOW_SCALE, LsfgConfig.getFlowScale(this)), 0.10f, 1.00f);
            renderScalePercent = clamp(LsfgConfig.getRenderScalePercent(this), 50, 100);
            batterySaverEnabled = LsfgConfig.isBatterySaverEnabled(this);
            lsfgRealRequested = intent.getBooleanExtra(EXTRA_LSFG_REAL, LsfgConfig.isLsfgRealRequested(this)) && LsfgConfig.isDllReady(this);
            targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
            if (targetPackage == null) targetPackage = LsfgConfig.getTargetPackage(this);
            autoLaunchTarget = intent.getBooleanExtra(EXTRA_AUTO_LAUNCH, LsfgConfig.isAutoLaunchEnabled(this));
            targetAutoLaunched = false;

            safeStartForeground("Capturando tela");
            showOverlays();
            startProjection(resultCode, resultData);
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        cleanupCaptureOnly();
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            Toast.makeText(this, "Falha ao iniciar MediaProjection.", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        mediaProjection.registerCallback(projectionCallback, mainHandler);
        setupCapture("start");
    }

    private void setupCapture(String reason) {
        if (mediaProjection == null || captureHandler == null) return;
        DisplayMetrics metrics = getRealDisplayMetrics();
        screenWidth = Math.max(1, metrics.widthPixels);
        screenHeight = Math.max(1, metrics.heightPixels);
        renderScalePercent = clamp(LsfgConfig.getRenderScalePercent(this), 50, 100);
        batterySaverEnabled = LsfgConfig.isBatterySaverEnabled(this);
        float renderScale = renderScalePercent / 100f;
        captureWidth = makeEven(Math.max(320, Math.round(screenWidth * renderScale)));
        captureHeight = makeEven(Math.max(180, Math.round(screenHeight * renderScale)));
        captureDensity = Math.max(DisplayMetrics.DENSITY_DEFAULT, metrics.densityDpi);
        hasSeenNonBlackFrame = false;
        consecutiveBlackFrames = 0;
        frameCounter = 0;
        fpsWindowStartMs = System.currentTimeMillis();
        lastFpsFrameTimeMs = 0L;
        lastImageTimestampNs = 0L;
        lastPanelStatsPostMs = 0L;
        captureStartMs = System.currentTimeMillis();
        lastAnyImageTimeMs = 0L;
        firstFramePrimeAttempts = 0;
        displayRefreshFps = readDisplayRefreshRate();
        rawCaptureFps = 0.0;
        correctedAppFps = 0.0;
        captureRateLimited30 = false;
        usingSurfaceFlingerFps = false;
        currentFps = 0.0;
        appliedFps = 0.0;
        displayedAppFps = 0.0;
        displayedAppliedFps = 0.0;
        lastHudFpsDisplayMs = 0L;
        lastFrameCopyTime = 0L;
        lastEcoBitmapCopyMs = 0L;
        lastEcoPanelStatsMs = 0L;
        lastEcoHudPostMs = 0L;
        lastEcoFpsSampleMs = 0L;
        nativeStatus = NativeLsfgBridge.configure(this, captureWidth, captureHeight, screenWidth, screenHeight, frameMultiplier, flowScale, renderScalePercent, batterySaverEnabled, lsfgRealRequested);
        startSurfaceFlingerFpsReader();
        clearOverlay("Recriando captura: " + reason + "\n" + captureWidth + "x" + captureHeight
                + " • Render " + renderScalePercent + "%");
        updatePanelStats("Recriando captura...\n" + captureWidth + "x" + captureHeight
                + " • Render " + renderScalePercent + "%"
                + "\n" + nativeStatus);

        captureHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, MAX_IMAGES);
                    imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                        @Override public void onImageAvailable(ImageReader reader) { handleImageAvailable(reader); }
                    }, captureHandler);
                    virtualDisplay = mediaProjection.createVirtualDisplay(
                            "BrunoFrameOverlay-VirtualDisplay",
                            captureWidth, captureHeight, captureDensity,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            imageReader.getSurface(), null, captureHandler);
                    updateOverlayStatus("Capturando " + captureWidth + "x" + captureHeight
                            + " • Render " + renderScalePercent + "%");
                    updatePanelStats("Capturando " + captureWidth + "x" + captureHeight
                            + " • Render " + renderScalePercent + "%"
                            + "\n" + nativeStatus);
                    if (autoLaunchTarget && !targetAutoLaunched) {
                        targetAutoLaunched = true;
                        launchTargetAppDelayed();
                    }
                    startFirstFrameWatchdog();
                    bringControlsToFrontSoon();
                } catch (Throwable t) {
                    CrashLogger.writeCrash(CaptureService.this, Thread.currentThread(), t);
                    updateOverlayStatus("Erro ao criar captura: " + t.getClass().getSimpleName());
                    updatePanelStats("Erro ao criar captura: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    private void handleImageAvailable(ImageReader reader) {
        long now = System.currentTimeMillis();
        if (MIN_FRAME_INTERVAL_MS > 0L && now - lastFrameCopyTime < MIN_FRAME_INTERVAL_MS) {
            Image skipped = null;
            try { skipped = reader.acquireLatestImage(); } catch (Throwable ignored) { }
            finally { if (skipped != null) skipped.close(); }
            return;
        }
        lastFrameCopyTime = now;
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || image.getPlanes() == null || image.getPlanes().length == 0) return;
            lastAnyImageTimeMs = now;
            long imageTimestampNs = image.getTimestamp();
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();
            if (pixelStride <= 0 || rowStride <= 0 || imageWidth <= 0 || imageHeight <= 0) return;
            if (pixelStride != 4) {
                updateOverlayStatus("PixelStride incompatível: " + pixelStride);
                updatePanelStats("PixelStride incompatível: " + pixelStride);
                return;
            }
            int paddedWidth = Math.max(imageWidth, rowStride / pixelStride);
            if (shouldUseNativeEcoPassThrough(now)) {
                if (now - lastEcoFpsSampleMs >= ECO_IMAGE_FPS_SAMPLE_INTERVAL_MS || surfaceFlingerFps <= 1.0) {
                    lastEcoFpsSampleMs = now;
                    updateFps(now, imageTimestampNs);
                }
                double appFpsForHudEco = getAppFpsForHud();
                double appliedEco = estimateAppliedFpsForHud(appFpsForHudEco);
                updateDisplayedFps(now, appFpsForHudEco, appliedEco);
                postEcoHudOnly(now);
                return;
            }
            ensureStagingBitmap(paddedWidth, imageHeight);
            buffer.rewind();
            stagingBitmap.copyPixelsFromBuffer(buffer);
            frameSrcRect.set(0, 0, imageWidth, imageHeight);
            boolean mostlyBlack = isMostlyBlack(stagingBitmap, frameSrcRect);
            if (mostlyBlack) {
                if (!hasSeenNonBlackFrame) {
                    long sinceStart = now - captureStartMs;
                    if (sinceStart < FIRST_FRAME_SOFT_ACCEPT_MS) {
                        clearOverlay("Inicializando captura\n" + imageWidth + "x" + imageHeight + " stride=" + rowStride
                                + "\nV3.0: aguardando frame válido com fallback Android 15.");
                        updatePanelStats("Inicializando captura\n" + imageWidth + "x" + imageHeight + " stride=" + rowStride
                                + "\nPrime " + firstFramePrimeAttempts + "/" + MAX_FIRST_FRAME_PRIMES);
                        return;
                    }
                    // V3.0: no HyperOS alguns jogos/telas escuras eram classificados como sem frame até puxar a barra.
                    // Depois de um pequeno prazo, aceitamos o frame recebido para destravar HUD/FPS/captura.
                    mostlyBlack = false;
                    consecutiveBlackFrames = 0;
                } else {
                    consecutiveBlackFrames++;
                    if (consecutiveBlackFrames < MAX_CONSECUTIVE_BLACK_DROPS) {
                        updatePanelStats("Anti-flicker: segurando último frame válido\nblack drops: " + consecutiveBlackFrames
                                + "\n" + buildRuntimeStats());
                        return;
                    }
                }
            } else {
                consecutiveBlackFrames = 0;
            }
            hasSeenNonBlackFrame = true;
            stopFirstFrameWatchdog();
            updateFps(now, imageTimestampNs);
            if (shouldUseNativeEcoPassThrough(now)) {
                double appFpsForHudEco = getAppFpsForHud();
                double appliedEco = estimateAppliedFpsForHud(appFpsForHudEco);
                updateDisplayedFps(now, appFpsForHudEco, appliedEco);
                postEcoHudOnly(now);
                return;
            }
            Bitmap currentFrame = copyIntoNextFrameBuffer(stagingBitmap, frameSrcRect, imageWidth, imageHeight);
            Bitmap previousFrame = previousFrameIndex >= 0 ? frameBuffers[previousFrameIndex] : null;
            double appFpsForHud = getAppFpsForHud();
            appliedFps = estimateAppliedFpsForHud(appFpsForHud);
            updateDisplayedFps(now, appFpsForHud, appliedFps);
            final String detail = "V3.7 native render " + renderScalePercent + "% • FPS SurfaceFlinger/Shizuku • "
                    + imageWidth + "x" + imageHeight + " • stride " + rowStride;
            final Bitmap currentFinal = currentFrame;
            final Bitmap previousFinal = previousFrame;
            final Rect srcFinal = new Rect(0, 0, imageWidth, imageHeight);
            final double beforeFinal = displayedAppFps;
            final double afterFinal = displayedAppliedFps;
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (overlayView != null) {
                        overlayView.setLsfgControls(lsfgRealRequested, frameMultiplier, flowScale, nativeStatus);
                        overlayView.setFrame(currentFinal, previousFinal, srcFinal, beforeFinal, afterFinal, detail);
                    }
                    updateFpsHud(beforeFinal, afterFinal);
                    postPanelStatsThrottled(now);
                }
            });
        } catch (Throwable t) {
            updateOverlayStatus("Frame error: " + t.getClass().getSimpleName());
            updatePanelStats("Frame error: " + t.getClass().getSimpleName());
        } finally {
            if (image != null) image.close();
        }
    }

    private void ensureStagingBitmap(int paddedWidth, int height) {
        if (stagingBitmap == null || stagingPaddedWidth != paddedWidth || stagingHeight != height) {
            if (stagingBitmap != null && !stagingBitmap.isRecycled()) stagingBitmap.recycle();
            stagingBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            stagingBitmap.setHasAlpha(false);
            stagingPaddedWidth = paddedWidth;
            stagingHeight = height;
        }
    }

    private void ensureFrameBuffers(int width, int height) {
        if (frameBufferWidth == width && frameBufferHeight == height && frameBuffers[0] != null) return;
        recycleFrameBuffers();
        for (int i = 0; i < frameBuffers.length; i++) {
            frameBuffers[i] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frameBuffers[i].setHasAlpha(false);
        }
        frameBufferWidth = width;
        frameBufferHeight = height;
        currentFrameIndex = -1;
        previousFrameIndex = -1;
    }

    private Bitmap copyIntoNextFrameBuffer(Bitmap source, Rect src, int width, int height) {
        ensureFrameBuffers(width, height);
        int writeIndex = currentFrameIndex < 0 ? 0 : (currentFrameIndex + 1) % frameBuffers.length;
        Bitmap target = frameBuffers[writeIndex];
        target.setHasAlpha(false);
        Canvas canvas = new Canvas(target);
        frameDstRect.set(0, 0, width, height);
        copyPaint.setAlpha(255);
        canvas.drawBitmap(source, src, frameDstRect, copyPaint);
        previousFrameIndex = currentFrameIndex;
        currentFrameIndex = writeIndex;
        return target;
    }

    private void recycleFrameBuffers() {
        for (int i = 0; i < frameBuffers.length; i++) {
            if (frameBuffers[i] != null && !frameBuffers[i].isRecycled()) frameBuffers[i].recycle();
            frameBuffers[i] = null;
        }
        frameBufferWidth = 0;
        frameBufferHeight = 0;
        currentFrameIndex = -1;
        previousFrameIndex = -1;
    }

    private boolean isMostlyBlack(Bitmap bitmap, Rect src) {
        if (bitmap == null || src == null || src.width() <= 0 || src.height() <= 0) return true;
        int samples = 0;
        int black = 0;
        int stepX = Math.max(1, src.width() / 10);
        int stepY = Math.max(1, src.height() / 10);
        for (int y = src.top; y < src.bottom; y += stepY) {
            for (int x = src.left; x < src.right; x += stepX) {
                int pixel = bitmap.getPixel(Math.min(x, bitmap.getWidth() - 1), Math.min(y, bitmap.getHeight() - 1));
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                if (r < 4 && g < 4 && b < 4) black++;
                samples++;
            }
        }
        return samples > 0 && black >= (int) Math.ceil(samples * 0.995f);
    }

    private boolean shouldUseNativeEcoPassThrough(long nowMs) {
        // V3.5: quando LSFG Native/Vulkan está ativo, copiar bitmap RGBA fullscreen
        // a cada frame é o maior dreno de CPU/bateria. Mantemos captura viva, FPS
        // via SurfaceFlinger/Shizuku e HUD, mas evitamos cópia Java quase sempre.
        if (!batterySaverEnabled) return false;
        if (!lsfgRealRequested || !fullscreenMirror) return false;
        if (!NativeLsfgBridge.isLastEngineReady()) return false;
        if (!hasSeenNonBlackFrame) return false;
        if (nowMs - lastEcoBitmapCopyMs >= ECO_NATIVE_BITMAP_COPY_INTERVAL_MS) {
            lastEcoBitmapCopyMs = nowMs;
            return false; // uma cópia ocasional mantém estado/diagnóstico sem drenar bateria.
        }
        return true;
    }

    private void postEcoHudOnly(final long nowMs) {
        final double beforeFinal = displayedAppFps;
        final double afterFinal = displayedAppliedFps;
        if (nowMs - lastEcoHudPostMs < ECO_NATIVE_HUD_INTERVAL_MS && displayedAppFps > 0.1) return;
        lastEcoHudPostMs = nowMs;
        final String detail = "V3.7 eficiência equilibrada • render " + renderScalePercent + "% • cópia Java reduzida sem pausar IA";
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (overlayView != null) {
                    overlayView.setLsfgControls(lsfgRealRequested, frameMultiplier, flowScale, nativeStatus);
                    overlayView.setFpsOnly(beforeFinal, afterFinal, detail);
                }
                updateFpsHud(beforeFinal, afterFinal);
                if (panelView != null && nowMs - lastEcoPanelStatsMs >= ECO_NATIVE_PANEL_INTERVAL_MS) {
                    lastEcoPanelStatsMs = nowMs;
                    panelView.setStats(buildRuntimeStats());
                }
            }
        });
    }

    private void updateFps(long nowMs, long imageTimestampNs) {
        frameCounter++;
        double instantFps = 0.0;
        if (imageTimestampNs > 0L && lastImageTimestampNs > 0L && imageTimestampNs > lastImageTimestampNs) {
            long deltaNs = Math.max(1L, imageTimestampNs - lastImageTimestampNs);
            instantFps = 1_000_000_000.0 / deltaNs;
        } else if (lastFpsFrameTimeMs > 0L) {
            long deltaMs = Math.max(1L, nowMs - lastFpsFrameTimeMs);
            instantFps = 1000.0 / deltaMs;
        }
        if (imageTimestampNs > 0L) lastImageTimestampNs = imageTimestampNs;
        lastFpsFrameTimeMs = nowMs;

        if (instantFps > 1.0 && instantFps < 240.0) {
            rawCaptureFps = rawCaptureFps <= 0.1 ? instantFps : (rawCaptureFps * 0.70 + instantFps * 0.30);
            currentFps = currentFps <= 0.1 ? instantFps : (currentFps * 0.82 + instantFps * 0.18);
        }

        long elapsed = Math.max(1, nowMs - fpsWindowStartMs);
        if (elapsed >= FPS_STABLE_WINDOW_MS) {
            double windowFps = (frameCounter * 1000.0) / elapsed;
            if (windowFps > 1.0 && windowFps < 240.0) {
                rawCaptureFps = rawCaptureFps <= 0.1 ? windowFps : (rawCaptureFps * 0.55 + windowFps * 0.45);
                currentFps = currentFps <= 0.1 ? windowFps : (currentFps * 0.58 + windowFps * 0.42);
            }
            frameCounter = 0;
            fpsWindowStartMs = nowMs;
        }
    }

    private void updateDisplayedFps(long nowMs, double measuredAppFps, double measuredAppliedFps) {
        if (nowMs - lastHudFpsDisplayMs < HUD_FPS_DISPLAY_INTERVAL_MS && displayedAppFps > 0.1) return;
        lastHudFpsDisplayMs = nowMs;
        displayedAppFps = displayedAppFps <= 0.1 ? measuredAppFps : (displayedAppFps * 0.70 + measuredAppFps * 0.30);
        displayedAppliedFps = displayedAppliedFps <= 0.1 ? measuredAppliedFps : (displayedAppliedFps * 0.70 + measuredAppliedFps * 0.30);
    }

    private double getAppFpsForHud() {
        double sf = surfaceFlingerFps;
        if (LsfgConfig.isSurfaceFlingerFpsEnabled(this) && sf > 1.0) {
            usingSurfaceFlingerFps = true;
            captureRateLimited30 = false;
            correctedAppFps = correctedAppFps <= 0.1 ? sf : (correctedAppFps * 0.70 + sf * 0.30);
            return correctedAppFps;
        }

        usingSurfaceFlingerFps = false;
        double measured = currentFps;
        if (measured <= 0.1) return 0.0;

        double refresh = displayRefreshFps > 1.0 ? displayRefreshFps : 60.0;

        // Fallback: MediaProjection mede captura, não FPS real. Só é usado quando Shizuku/SurfaceFlinger
        // ainda não está autorizado ou não achou a layer do jogo.
        double corrected = measured;
        captureRateLimited30 = false;

        if (measured >= 8.0 && measured < 24.0) {
            corrected = snapToGameFpsTier(measured * 3.0, refresh);
            captureRateLimited30 = true;
        } else if (measured >= CAPTURE_30_LOCK_LOW && measured <= CAPTURE_30_LOCK_HIGH) {
            corrected = snapToGameFpsTier(measured * 2.0, refresh);
            captureRateLimited30 = true;
        } else if (refresh >= 90.0 && measured > 34.0 && measured < 58.0) {
            corrected = snapToGameFpsTier(measured, refresh);
        } else {
            corrected = Math.min(measured, Math.max(refresh, 60.0));
        }

        correctedAppFps = correctedAppFps <= 0.1 ? corrected : (correctedAppFps * 0.62 + corrected * 0.38);
        return correctedAppFps;
    }

    private double estimateAppliedFpsForHud(double appFpsForHud) {
        double estimate = NativeLsfgBridge.estimateAfterFps(appFpsForHud, frameMultiplier, lsfgRealRequested, simpleInterpolation, flowScale);
        // V3.2: não limitar ao refresh retornado pelo Android, porque em HyperOS esse valor pode vir 60 Hz
        // enquanto o contador do sistema/jogo mostra 90~120 FPS no lobby. Mantemos só um teto seguro.
        if (lsfgRealRequested && NativeLsfgBridge.isLastEngineReady()) {
            return Math.min(Math.max(estimate, appFpsForHud), 240.0);
        }
        return Math.min(estimate, 240.0);
    }

    private double snapToGameFpsTier(double value, double refresh) {
        double max = Math.max(30.0, refresh > 1.0 ? refresh : 60.0);
        if (max < 61.0) max = 60.0;
        double[] tiers = max >= 118.0
                ? new double[]{30.0, 35.0, 45.0, 60.0, 90.0, 120.0}
                : (max >= 88.0 ? new double[]{30.0, 35.0, 45.0, 60.0, 90.0} : new double[]{30.0, 35.0, 45.0, 60.0});
        double best = tiers[0];
        double bestDiff = Math.abs(value - best);
        for (double tier : tiers) {
            if (tier > max + 1.0) continue;
            double diff = Math.abs(value - tier);
            if (diff < bestDiff) {
                best = tier;
                bestDiff = diff;
            }
        }
        return Math.min(best, max);
    }

    private void scheduleNativeParamApply() {
        if (captureHandler == null || !lsfgRealRequested) return;
        captureHandler.removeCallbacks(applyNativeParamsRunnable);
        captureHandler.postDelayed(applyNativeParamsRunnable, NATIVE_PARAM_APPLY_DEBOUNCE_MS);
    }

    private void postPanelStatsThrottled(long nowMs) {
        if (panelView == null) return;
        if (nowMs - lastPanelStatsPostMs < PANEL_STATS_INTERVAL_MS) return;
        lastPanelStatsPostMs = nowMs;
        panelView.setStats(buildRuntimeStats());
    }

    private void showOverlays() {
        showMirrorOverlay();
        showFpsHud();
        showControlBubble();
    }

    private void showMirrorOverlay() {
        if (overlayView != null || windowManager == null) return;
        overlayView = new OverlayView(this);
        overlayView.setFullscreenMirror(fullscreenMirror);
        overlayView.setSimpleInterpolation(simpleInterpolation);
        overlayView.setDebugHudVisible(false); // V3.7: FPS agora usa janela própria móvel.
        overlayView.setLsfgControls(lsfgRealRequested, frameMultiplier, flowScale, nativeStatus);
        overlayView.setStatus("Aguardando frames...");
        int type = getOverlayType();
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SECURE;
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.alpha = 1.0f;
        overlayParams.dimAmount = 0.0f;
        overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        overlayParams.setTitle("BrunoFrameOverlayV37Mirror");
        if (Build.VERSION.SDK_INT >= 28) overlayParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try { windowManager.addView(overlayView, overlayParams); }
        catch (Throwable t) {
            CrashLogger.writeCrash(this, Thread.currentThread(), t);
            overlayView = null;
            Toast.makeText(this, "Erro ao abrir overlay: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }


    private void showFpsHud() {
        if (!debugHudVisible || fpsHudView != null || windowManager == null) return;
        fpsHudScale = LsfgConfig.getFpsHudScale(this);
        fpsHudLocked = LsfgConfig.isFpsHudLocked(this);
        fpsHudView = new FpsHudView(this);
        fpsHudView.setLocked(fpsHudLocked);
        fpsHudView.setFps(displayedAppFps, displayedAppliedFps);
        fpsHudView.setListener(new FpsHudView.Listener() {
            @Override public void onHudDrag(float dx, float dy) { moveFpsHud(dx, dy); }
            @Override public void onHudScale(float factor) { scaleFpsHud(factor); }
            @Override public void onHudDoubleTap() { toggleFpsHudLocked(); }
        });
        int type = getOverlayType();
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SECURE;
        DisplayMetrics metrics = getRealDisplayMetrics();
        int w = Math.round(dp(78) * fpsHudScale);
        int h = Math.round(dp(34) * fpsHudScale);
        fpsHudParams = new WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT);
        fpsHudParams.gravity = Gravity.TOP | Gravity.START;
        int savedX = LsfgConfig.getFpsHudX(this);
        int savedY = LsfgConfig.getFpsHudY(this);
        fpsHudParams.x = savedX >= 0 ? savedX : dp(10);
        fpsHudParams.y = savedY >= 0 ? savedY : dp(10);
        fpsHudParams.alpha = 0.92f;
        fpsHudParams.dimAmount = 0.0f;
        fpsHudParams.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        fpsHudParams.setTitle("BrunoFrameOverlayV37FpsHud");
        if (Build.VERSION.SDK_INT >= 28) fpsHudParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try { windowManager.addView(fpsHudView, fpsHudParams); clampControlsToScreen(); }
        catch (Throwable t) {
            CrashLogger.writeCrash(this, Thread.currentThread(), t);
            fpsHudView = null;
            fpsHudParams = null;
        }
    }

    private void updateFpsHud(final double appFps, final double aiFps) {
        if (!debugHudVisible || fpsHudView == null) return;
        mainHandler.post(new Runnable() { @Override public void run() {
            if (fpsHudView != null) fpsHudView.setFps(appFps, aiFps);
        }});
    }

    private void moveFpsHud(float dx, float dy) {
        if (windowManager == null || fpsHudView == null || fpsHudParams == null || fpsHudLocked) return;
        DisplayMetrics metrics = getRealDisplayMetrics();
        fpsHudParams.x += (int) dx;
        fpsHudParams.y += (int) dy;
        int maxX = Math.max(0, metrics.widthPixels - fpsHudParams.width);
        int maxY = Math.max(0, metrics.heightPixels - fpsHudParams.height);
        if (fpsHudParams.x < 0) fpsHudParams.x = 0;
        if (fpsHudParams.y < 0) fpsHudParams.y = 0;
        if (fpsHudParams.x > maxX) fpsHudParams.x = maxX;
        if (fpsHudParams.y > maxY) fpsHudParams.y = maxY;
        try { windowManager.updateViewLayout(fpsHudView, fpsHudParams); } catch (Throwable ignored) { }
        LsfgConfig.setFpsHudPosition(this, fpsHudParams.x, fpsHudParams.y);
    }

    private void scaleFpsHud(float factor) {
        if (windowManager == null || fpsHudView == null || fpsHudParams == null || fpsHudLocked) return;
        fpsHudScale = clamp(fpsHudScale * factor, 0.75f, 1.80f);
        fpsHudParams.width = Math.round(dp(78) * fpsHudScale);
        fpsHudParams.height = Math.round(dp(34) * fpsHudScale);
        try { windowManager.updateViewLayout(fpsHudView, fpsHudParams); } catch (Throwable ignored) { }
        LsfgConfig.setFpsHudScale(this, fpsHudScale);
        clampControlsToScreen();
    }

    private void toggleFpsHudLocked() {
        fpsHudLocked = !fpsHudLocked;
        LsfgConfig.setFpsHudLocked(this, fpsHudLocked);
        if (fpsHudView != null) fpsHudView.setLocked(fpsHudLocked);
        Toast.makeText(this, fpsHudLocked ? "HUD FPS fixado" : "HUD FPS destravado", Toast.LENGTH_SHORT).show();
    }

    private void removeFpsHud() {
        if (windowManager != null && fpsHudView != null) {
            try { windowManager.removeView(fpsHudView); } catch (Throwable ignored) { }
        }
        fpsHudView = null;
        fpsHudParams = null;
    }

    private void showControlBubble() {
        if (bubbleView != null || windowManager == null) return;
        bubbleView = new ControlBubbleView(this);
        bubbleView.setListener(new ControlBubbleView.Listener() {
            @Override public void onBubbleClick() { togglePanel(); }
            @Override public void onBubbleDrag(float dx, float dy) { moveBubble(dx, dy); }
        });
        int type = getOverlayType();
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SECURE;
        int size = dp(64);
        DisplayMetrics metrics = getRealDisplayMetrics();
        bubbleParams = new WindowManager.LayoutParams(size, size, type, flags, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = Math.max(dp(8), metrics.widthPixels - size - dp(14));
        bubbleParams.y = Math.max(dp(24), metrics.heightPixels / 2 - size / 2);
        bubbleParams.setTitle("BrunoFrameOverlayV37Bubble");
        if (Build.VERSION.SDK_INT >= 28) bubbleParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try { windowManager.addView(bubbleView, bubbleParams); clampControlsToScreen(); }
        catch (Throwable t) {
            CrashLogger.writeCrash(this, Thread.currentThread(), t);
            bubbleView = null;
        }
    }

    private void togglePanel() {
        if (panelView != null) removePanel(); else showPanel();
        if (bubbleView != null) bubbleView.setOpened(panelView != null);
    }

    private void showPanel() {
        if (panelView != null || windowManager == null) return;
        panelView = new ControlPanelView(this, fullscreenMirror, simpleInterpolation, debugHudVisible,
                lsfgRealRequested, frameMultiplier, flowScale, LsfgConfig.isDllReady(this), LsfgConfig.getTargetLabel(this),
                batterySaverEnabled, renderScalePercent);
        panelView.setListener(new ControlPanelView.Listener() {
            @Override public void onFullscreenChanged(boolean enabled) {
                fullscreenMirror = enabled;
                if (overlayView != null) overlayView.setFullscreenMirror(enabled);
            }
            @Override public void onInterpolationChanged(boolean enabled) {
                simpleInterpolation = enabled;
                if (overlayView != null) overlayView.setSimpleInterpolation(enabled);
                appliedFps = estimateAppliedFpsForHud(getAppFpsForHud());
                updatePanelStats(buildRuntimeStats());
            }
            @Override public void onHudChanged(boolean enabled) {
                debugHudVisible = enabled;
                if (overlayView != null) overlayView.setDebugHudVisible(false);
                if (enabled) showFpsHud(); else removeFpsHud();
            }
            @Override public void onLsfgRealChanged(boolean enabled) {
                lsfgRealRequested = enabled && LsfgConfig.isDllReady(CaptureService.this);
                LsfgConfig.setLsfgRealRequested(CaptureService.this, lsfgRealRequested);
                nativeStatus = NativeLsfgBridge.configure(CaptureService.this, captureWidth, captureHeight, screenWidth, screenHeight, frameMultiplier, flowScale, renderScalePercent, batterySaverEnabled, lsfgRealRequested);
                appliedFps = estimateAppliedFpsForHud(getAppFpsForHud());
                if (overlayView != null) overlayView.setLsfgControls(lsfgRealRequested, frameMultiplier, flowScale, nativeStatus);
                updatePanelStats(buildRuntimeStats());
            }
            @Override public void onMultiplierChanged(int multiplier) {
                frameMultiplier = clamp(multiplier, 2, 8);
                LsfgConfig.setMultiplier(CaptureService.this, frameMultiplier);
                appliedFps = estimateAppliedFpsForHud(getAppFpsForHud());
                if (overlayView != null) overlayView.setLsfgControls(lsfgRealRequested, frameMultiplier, flowScale, nativeStatus);
                scheduleNativeParamApply();
                updatePanelStats(buildRuntimeStats());
            }
            @Override public void onFlowScaleChanged(float flow) {
                flowScale = clamp(flow, 0.10f, 1.00f);
                LsfgConfig.setFlowScale(CaptureService.this, flowScale);
                appliedFps = estimateAppliedFpsForHud(getAppFpsForHud());
                if (overlayView != null) overlayView.setLsfgControls(lsfgRealRequested, frameMultiplier, flowScale, nativeStatus);
                scheduleNativeParamApply();
                updatePanelStats(buildRuntimeStats());
            }
            @Override public void onBatterySaverChanged(boolean enabled) {
                batterySaverEnabled = enabled;
                LsfgConfig.setBatterySaverEnabled(CaptureService.this, enabled);
                updatePanelStats(buildRuntimeStats());
            }
            @Override public void onRenderScaleChanged(int percent) {
                renderScalePercent = clamp(percent, 50, 100);
                LsfgConfig.setRenderScalePercent(CaptureService.this, renderScalePercent);
                scheduleNativeParamApply();
                updatePanelStats("Render Scale alterado para " + renderScalePercent
                        + "%\nRecrie a captura para redimensionar ImageReader/Vulkan. IA continua ativa; economia não reduz multiplier/flow.\n" + buildRuntimeStats());
            }
            @Override public void onLaunchTargetRequested() { launchTargetAppNow(); }
            @Override public void onRecreateRequested() { scheduleRecreate("manual panel recreate"); }
            @Override public void onCloseRequested() { removePanel(); }
            @Override public void onStopRequested() {
                cleanupAll();
                stopSelf();
            }
        });
        panelView.setStats(buildRuntimeStats());
        int type = getOverlayType();
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SECURE;
        DisplayMetrics metrics = getRealDisplayMetrics();
        int width = Math.min(Math.max(dp(300), metrics.widthPixels - dp(32)), dp(430));
        int height = Math.min(Math.max(dp(260), metrics.heightPixels - dp(36)), dp(560));
        panelParams = new WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.RIGHT;
        panelParams.x = dp(12);
        panelParams.y = dp(18);
        panelParams.alpha = 0.90f;
        panelParams.dimAmount = 0.0f;
        panelParams.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        panelParams.setTitle("BrunoFrameOverlayV37Panel");
        if (Build.VERSION.SDK_INT >= 28) panelParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try { windowManager.addView(panelView, panelParams); bringHudAndBubbleToFront(); }
        catch (Throwable t) {
            CrashLogger.writeCrash(this, Thread.currentThread(), t);
            panelView = null;
        }
    }

    private void removePanel() {
        if (windowManager != null && panelView != null) {
            try { windowManager.removeView(panelView); } catch (Throwable ignored) { }
        }
        panelView = null;
        panelParams = null;
        if (bubbleView != null) bubbleView.setOpened(false);
    }

    private void moveBubble(float dx, float dy) {
        if (windowManager == null || bubbleView == null || bubbleParams == null) return;
        DisplayMetrics metrics = getRealDisplayMetrics();
        bubbleParams.x += (int) dx;
        bubbleParams.y += (int) dy;
        int maxX = Math.max(0, metrics.widthPixels - bubbleParams.width);
        int maxY = Math.max(0, metrics.heightPixels - bubbleParams.height);
        if (bubbleParams.x < 0) bubbleParams.x = 0;
        if (bubbleParams.y < 0) bubbleParams.y = 0;
        if (bubbleParams.x > maxX) bubbleParams.x = maxX;
        if (bubbleParams.y > maxY) bubbleParams.y = maxY;
        try { windowManager.updateViewLayout(bubbleView, bubbleParams); } catch (Throwable ignored) { }
    }

    private void clampControlsToScreen() {
        if (windowManager == null) return;
        DisplayMetrics metrics = getRealDisplayMetrics();
        if (bubbleView != null && bubbleParams != null) {
            int maxX = Math.max(0, metrics.widthPixels - bubbleParams.width);
            int maxY = Math.max(0, metrics.heightPixels - bubbleParams.height);
            if (bubbleParams.x < 0) bubbleParams.x = 0;
            if (bubbleParams.y < 0) bubbleParams.y = 0;
            if (bubbleParams.x > maxX) bubbleParams.x = Math.max(dp(8), maxX - dp(8));
            if (bubbleParams.y > maxY) bubbleParams.y = Math.max(dp(8), maxY - dp(8));
            try { windowManager.updateViewLayout(bubbleView, bubbleParams); } catch (Throwable ignored) { }
        }
        if (fpsHudView != null && fpsHudParams != null) {
            int maxX = Math.max(0, metrics.widthPixels - fpsHudParams.width);
            int maxY = Math.max(0, metrics.heightPixels - fpsHudParams.height);
            if (fpsHudParams.x < 0) fpsHudParams.x = 0;
            if (fpsHudParams.y < 0) fpsHudParams.y = 0;
            if (fpsHudParams.x > maxX) fpsHudParams.x = maxX;
            if (fpsHudParams.y > maxY) fpsHudParams.y = maxY;
            try { windowManager.updateViewLayout(fpsHudView, fpsHudParams); } catch (Throwable ignored) { }
            LsfgConfig.setFpsHudPosition(this, fpsHudParams.x, fpsHudParams.y);
        }
        if (panelView != null && panelParams != null) {
            panelParams.height = Math.min(Math.max(dp(260), metrics.heightPixels - dp(36)), dp(560));
            int maxX = Math.max(0, metrics.widthPixels - panelParams.width);
            int maxY = Math.max(0, metrics.heightPixels - panelParams.height);
            if (panelParams.x < 0) panelParams.x = 0;
            if (panelParams.y < 0) panelParams.y = dp(8);
            if (panelParams.x > maxX) panelParams.x = maxX;
            if (panelParams.y > maxY) panelParams.y = Math.max(dp(8), maxY);
            try { windowManager.updateViewLayout(panelView, panelParams); } catch (Throwable ignored) { }
        }
    }

    private void bringControlsToFrontSoon() {
        mainHandler.postDelayed(new Runnable() { @Override public void run() { clampControlsToScreen(); bringHudAndBubbleToFront(); } }, 250);
        mainHandler.postDelayed(new Runnable() { @Override public void run() { clampControlsToScreen(); bringHudAndBubbleToFront(); } }, 1200);
    }

    private void bringHudAndBubbleToFront() {
        if (windowManager == null) return;
        if (fpsHudView != null && fpsHudParams != null) {
            try {
                windowManager.removeView(fpsHudView);
                windowManager.addView(fpsHudView, fpsHudParams);
            } catch (Throwable ignored) {
                try { windowManager.updateViewLayout(fpsHudView, fpsHudParams); } catch (Throwable ignoredAgain) { }
            }
        }
        if (bubbleView != null && bubbleParams != null) {
            try {
                windowManager.removeView(bubbleView);
                windowManager.addView(bubbleView, bubbleParams);
            } catch (Throwable ignored) {
                try { windowManager.updateViewLayout(bubbleView, bubbleParams); } catch (Throwable ignoredAgain) { }
            }
        }
    }

    private void startSurfaceFlingerFpsReader() {
        stopSurfaceFlingerFpsReader();
        if (!LsfgConfig.isSurfaceFlingerFpsEnabled(this)) {
            surfaceFlingerStatus = "SurfaceFlinger FPS: desativado";
            surfaceFlingerFps = 0.0;
            return;
        }
        String label = LsfgConfig.getTargetLabel(this);
        surfaceFlingerFpsReader = new SurfaceFlingerFpsReader(this, targetPackage, label, new SurfaceFlingerFpsReader.Callback() {
            @Override public void onSurfaceFlingerFps(final double fps, final String status) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (fps > 1.0) surfaceFlingerFps = fps;
                        surfaceFlingerStatus = status != null ? status : "";
                        if (panelView != null) panelView.setStats(buildRuntimeStats());
                    }
                });
            }
        });
        surfaceFlingerFpsReader.start();
    }

    private void stopSurfaceFlingerFpsReader() {
        if (surfaceFlingerFpsReader != null) {
            try { surfaceFlingerFpsReader.stop(); } catch (Throwable ignored) { }
        }
        surfaceFlingerFpsReader = null;
        surfaceFlingerFps = 0.0;
        usingSurfaceFlingerFps = false;
    }

    private String buildRuntimeStats() {
        double app = displayedAppFps > 0.1 ? displayedAppFps : getAppFpsForHud();
        double ai = displayedAppliedFps > 0.1 ? displayedAppliedFps : NativeLsfgBridge.estimateAfterFps(app, frameMultiplier, lsfgRealRequested, simpleInterpolation, flowScale);
        double gain = app > 0.1 ? ai / app : 0.0;
        String fpsMode = usingSurfaceFlingerFps ? "SurfaceFlinger/Shizuku" : (captureRateLimited30 ? "fallback estimado" : "fallback captura");
        return "FPS atual app: " + NativeLsfgBridge.formatFps(app) + "  (" + fpsMode + ")"
                + "\nFPS real gerado por IA: " + NativeLsfgBridge.formatFps(ai) + "  x" + String.format(java.util.Locale.US, "%.2f", gain)
                + "\nFonte FPS: " + fpsMode + " • " + surfaceFlingerStatus
                + "\nCaptura: " + NativeLsfgBridge.formatFps(rawCaptureFps) + " • Tela " + NativeLsfgBridge.formatFps(displayRefreshFps) + "Hz"
                + "\nModo: " + NativeLsfgBridge.buildShortMode(lsfgRealRequested, simpleInterpolation)
                + " • Economia " + (batterySaverEnabled ? "ON" : "OFF")
                + " • Render " + renderScalePercent + "%"
                + " • Mult " + frameMultiplier + "x • Flow " + String.format(java.util.Locale.US, "%.2f", flowScale)
                + "\nAlvo: " + LsfgConfig.getTargetLabel(this)
                + "\n" + nativeStatus;
    }

    private void launchTargetAppDelayed() {
        mainHandler.postDelayed(new Runnable() { @Override public void run() { launchTargetAppNow(); } }, 900);
    }

    private void launchTargetAppNow() {
        if (targetPackage == null || targetPackage.trim().length() == 0) {
            targetPackage = LsfgConfig.getTargetPackage(this);
        }
        if (targetPackage == null || targetPackage.trim().length() == 0) {
            Toast.makeText(this, "Nenhum jogo/app selecionado.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(targetPackage);
            if (launch == null) {
                Toast.makeText(this, "Não consegui abrir: " + targetPackage, Toast.LENGTH_LONG).show();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(launch);
        } catch (Throwable t) {
            Toast.makeText(this, "Erro ao abrir alvo: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void startFirstFrameWatchdog() {
        if (captureHandler == null) return;
        captureHandler.removeCallbacks(firstFrameWatchdogRunnable);
        captureHandler.postDelayed(firstFrameWatchdogRunnable, FIRST_FRAME_WATCHDOG_MS);
    }

    private void stopFirstFrameWatchdog() {
        if (captureHandler != null) captureHandler.removeCallbacks(firstFrameWatchdogRunnable);
    }

    private void pulseVirtualDisplaySurface() {
        if (captureHandler == null || virtualDisplay == null || imageReader == null) return;
        try { virtualDisplay.setSurface(null); } catch (Throwable ignored) { }
        captureHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (virtualDisplay != null && imageReader != null) {
                        virtualDisplay.setSurface(imageReader.getSurface());
                    }
                } catch (Throwable ignored) { }
            }
        }, PRIME_SURFACE_DELAY_MS);
    }

    private void scheduleRecreate(String reason) {
        if (mediaProjection == null || captureHandler == null) return;
        mainHandler.post(new Runnable() { @Override public void run() { clampControlsToScreen(); bringControlsToFrontSoon(); } });
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14/15 pode derrubar a sessão se o mesmo token de MediaProjection for reutilizado
            // para criar outro VirtualDisplay. Evitamos recriação automática; usuário pode reiniciar a sessão.
            updatePanelStats("Android 15 safe: mudança detectada (" + reason + ")\nSessão mantida sem recriar token. Reinicie captura se o tamanho ficar errado.");
            return;
        }
        captureHandler.removeCallbacks(delayedRecreateRunnable);
        clearOverlay("Mudança detectada: " + reason + "\nRecriando em 500ms...");
        updatePanelStats("Mudança detectada: " + reason + "\nRecriando em 500ms...");
        captureHandler.postDelayed(delayedRecreateRunnable, 500);
    }

    private void recreateCapture(String reason) {
        if (mediaProjection == null || captureHandler == null) return;
        cleanupCaptureSurfaces();
        resetFrameMemory(false);
        setupCapture(reason);
    }

    private void cleanupAll() {
        stopSurfaceFlingerFpsReader();
        cleanupCaptureOnly();
        removePanel();
        removeFpsHud();
        removeBubble();
        removeMirrorOverlay();
    }

    private void cleanupCaptureOnly() {
        stopSurfaceFlingerFpsReader();
        cleanupCaptureSurfaces();
        if (mediaProjection != null) {
            try { mediaProjection.unregisterCallback(projectionCallback); } catch (Throwable ignored) { }
            try { mediaProjection.stop(); } catch (Throwable ignored) { }
            mediaProjection = null;
        }
        resetFrameMemory(true);
        hasSeenNonBlackFrame = false;
        consecutiveBlackFrames = 0;
    }

    private void cleanupCaptureSurfaces() {
        if (captureHandler != null) {
            captureHandler.removeCallbacks(delayedRecreateRunnable);
            captureHandler.removeCallbacks(firstFrameWatchdogRunnable);
            captureHandler.removeCallbacks(applyNativeParamsRunnable);
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.setSurface(null); } catch (Throwable ignored) { }
            try { virtualDisplay.release(); } catch (Throwable ignored) { }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.setOnImageAvailableListener(null, null); } catch (Throwable ignored) { }
            try { imageReader.close(); } catch (Throwable ignored) { }
            imageReader = null;
        }
    }

    private void resetFrameMemory(boolean recycleStaging) {
        if (recycleStaging && stagingBitmap != null && !stagingBitmap.isRecycled()) stagingBitmap.recycle();
        if (recycleStaging) {
            stagingBitmap = null;
            stagingPaddedWidth = 0;
            stagingHeight = 0;
        }
        recycleFrameBuffers();
    }

    private void removeMirrorOverlay() {
        if (windowManager != null && overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Throwable ignored) { }
        }
        overlayView = null;
        overlayParams = null;
    }

    private void removeBubble() {
        if (windowManager != null && bubbleView != null) {
            try { windowManager.removeView(bubbleView); } catch (Throwable ignored) { }
        }
        bubbleView = null;
        bubbleParams = null;
    }

    private void clearOverlay(final String status) {
        mainHandler.post(new Runnable() { @Override public void run() { if (overlayView != null) overlayView.clearFrame(status); } });
    }

    private void updateOverlayStatus(final String status) {
        mainHandler.post(new Runnable() { @Override public void run() { if (overlayView != null) overlayView.setStatus(status); } });
    }

    private void updatePanelStats(final String status) {
        mainHandler.post(new Runnable() { @Override public void run() { if (panelView != null) panelView.setStats(status); } });
    }

    private double readDisplayRefreshRate() {
        double best = 60.0;
        try {
            if (windowManager != null) {
                Display display = windowManager.getDefaultDisplay();
                if (display != null) {
                    if (display.getRefreshRate() > best) best = display.getRefreshRate();
                    if (Build.VERSION.SDK_INT >= 23 && display.getSupportedModes() != null) {
                        for (Display.Mode mode : display.getSupportedModes()) {
                            if (mode != null && mode.getRefreshRate() > best) best = mode.getRefreshRate();
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return best;
    }

    private DisplayMetrics getRealDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager != null) {
            Display display = windowManager.getDefaultDisplay();
            if (display != null) display.getRealMetrics(metrics);
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) metrics = getResources().getDisplayMetrics();
        return metrics;
    }

    private boolean canDrawOverlay() { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this); }

    private int getOverlayType() { return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE; }


    private int makeEven(int value) {
        if (value < 2) return 2;
        return (value % 2 == 0) ? value : value - 1;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    private void safeStartForeground(String text) {
        try {
            Notification notification = buildNotification(text);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            CrashLogger.writeCrash(this, Thread.currentThread(), t);
            try { startForeground(NOTIFICATION_ID, buildNotification("Captura ativa")); } catch (Throwable ignored) { }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bruno Frame Overlay", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Captura e overlay experimental");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder
                .setContentTitle("Bruno Frame Overlay V3.7")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        cleanupAll();
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Throwable ignored) { }
        }
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) { }
            receiverRegistered = false;
        }
        if (captureThread != null) {
            if (Build.VERSION.SDK_INT >= 18) captureThread.quitSafely(); else captureThread.quit();
            captureThread = null;
            captureHandler = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
