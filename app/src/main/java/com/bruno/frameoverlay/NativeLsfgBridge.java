package com.bruno.frameoverlay;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/**
 * V3.6 prebuilt-native bridge.
 *
 * AndroidIDE only packages app/src/main/jniLibs/arm64-v8a/libbrunolsfg.so.
 * The .so is compiled by GitHub Actions from /native.
 *
 * V3.6 adds native Render Scale awareness: the native layer receives input size,
 * output/display size and renderScalePercent so Vulkan buffers can be sized for
 * lower internal resolution while the overlay remains fullscreen.
 */
public final class NativeLsfgBridge {
    private static final boolean libraryLoaded;
    private static final String loadMessage;
    private static String lastStatus = "Native ainda não configurado";
    private static boolean lastEngineReady = false;

    static {
        boolean loaded = false;
        String msg;
        try {
            System.loadLibrary("brunolsfg");
            loaded = true;
            msg = "Native ON • libbrunolsfg.so carregada";
        } catch (Throwable t) {
            msg = "Native OFF • libbrunolsfg.so ausente/inválida: " + t.getClass().getSimpleName();
        }
        libraryLoaded = loaded;
        loadMessage = msg;
        lastStatus = msg;
    }

    private NativeLsfgBridge() { }

    private static native String nativeGetVulkanSummary();

    // V3.0 legacy ABI kept for old .so fallback.
    private static native String nativeConfigure(String dllPath, int width, int height, int multiplier, float flowScale);
    private static native String nativePrepareEngine(String shaderDir, int width, int height, int multiplier, float flowScale);
    private static native boolean nativeIsEngineReady();
    private static native String nativeUpdateParams(int multiplier, float flowScale);

    // V3.6 ABI: native render-scale path. New .so should implement these.
    private static native String nativeConfigureScaled(String dllPath,
                                                       int inputWidth, int inputHeight,
                                                       int outputWidth, int outputHeight,
                                                       int multiplier, float flowScale,
                                                       int renderScalePercent,
                                                       boolean balancedEfficiency);

    private static native String nativePrepareEngineScaled(String shaderDir,
                                                           int inputWidth, int inputHeight,
                                                           int outputWidth, int outputHeight,
                                                           int multiplier, float flowScale,
                                                           int renderScalePercent,
                                                           boolean balancedEfficiency);

    private static native String nativeUpdateParamsScaled(int multiplier, float flowScale,
                                                          int renderScalePercent,
                                                          boolean balancedEfficiency);

    public static boolean isLibraryLoaded() { return libraryLoaded; }
    public static String getLoadMessage() { return loadMessage; }

    public static String getVulkanSummary() {
        if (!libraryLoaded) {
            return loadMessage + "\nColoque libbrunolsfg.so em app/src/main/jniLibs/arm64-v8a/ e recompile no AndroidIDE.";
        }
        try {
            return nativeGetVulkanSummary();
        } catch (Throwable t) {
            return "Native carregou, mas Vulkan falhou: " + t.getMessage();
        }
    }

    public static String configure(Context context,
                                   int inputWidth, int inputHeight,
                                   int outputWidth, int outputHeight,
                                   int multiplier, float flowScale,
                                   int renderScalePercent,
                                   boolean balancedEfficiency,
                                   boolean lsfgRequested) {
        if (!lsfgRequested) {
            lastEngineReady = false;
            lastStatus = "LSFG OFF • usando mirror/blend Java";
            LsfgConfig.setEngineStatus(context, false, lastStatus);
            return lastStatus;
        }

        if (!LsfgConfig.isDllReady(context)) {
            lastEngineReady = false;
            lastStatus = "DLL não importada • selecione sua Lossless.dll legítima";
            LsfgConfig.setEngineStatus(context, false, lastStatus);
            return lastStatus;
        }

        if (!LsfgConfig.isShaderReady(context) || LsfgConfig.getShaderCount(context) <= 0) {
            SpirvExtractor.Result extracted = SpirvExtractor.extractFromPrivateDll(context);
            if (!extracted.ok) {
                lastEngineReady = false;
                lastStatus = "SPIR-V não preparado: " + extracted.message;
                LsfgConfig.setEngineStatus(context, false, lastStatus);
                return lastStatus;
            }
        }

        File dll = LosslessDllManager.getPrivateDllFile(context);
        File shaderDir = SpirvExtractor.getShaderDir(context);
        int shaderCount = LsfgConfig.getShaderCount(context);

        if (!libraryLoaded) {
            lastEngineReady = false;
            lastStatus = "DLL OK + SPIR-V extraído: " + shaderCount + " módulo(s)"
                    + "\n" + loadMessage
                    + "\nModo ativo: fallback mirror/blend Java"
                    + "\nPara LSFG/Vulkan: gere libbrunolsfg.so pelo GitHub Actions e coloque em jniLibs/arm64-v8a.";
            LsfgConfig.setEngineStatus(context, false, lastStatus);
            return lastStatus;
        }

        int mult = Math.max(2, Math.min(8, multiplier));
        float flow = Math.max(0.10f, Math.min(1.00f, flowScale));
        int scale = Math.max(50, Math.min(100, renderScalePercent));
        int outW = Math.max(1, outputWidth);
        int outH = Math.max(1, outputHeight);

        try {
            String first;
            String second;
            try {
                first = nativeConfigureScaled(dll.getAbsolutePath(), inputWidth, inputHeight, outW, outH, mult, flow, scale, balancedEfficiency);
                second = nativePrepareEngineScaled(shaderDir.getAbsolutePath(), inputWidth, inputHeight, outW, outH, mult, flow, scale, balancedEfficiency);
            } catch (UnsatisfiedLinkError oldSo) {
                // Permite abrir com .so antiga, mas avisa que Render Scale native não está ativo.
                first = nativeConfigure(dll.getAbsolutePath(), inputWidth, inputHeight, mult, flow)
                        + "\nV3.6 aviso: .so antiga sem Render Scale native; gere nova libbrunolsfg.so.";
                second = nativePrepareEngine(shaderDir.getAbsolutePath(), inputWidth, inputHeight, mult, flow);
            }
            lastEngineReady = nativeIsEngineReady();
            lastStatus = first + "\n" + second;
            LsfgConfig.setEngineStatus(context, lastEngineReady, lastStatus);
            return lastStatus;
        } catch (Throwable t) {
            lastEngineReady = false;
            lastStatus = "Native/Vulkan falhou: " + t.getClass().getSimpleName() + " • " + t.getMessage()
                    + "\nFallback mirror/blend Java ativo.";
            LsfgConfig.setEngineStatus(context, false, lastStatus);
            return lastStatus;
        }
    }

    /** Backward-compatible overload used by older call sites. */
    public static String configure(Context context, int width, int height, int multiplier, float flowScale, boolean lsfgRequested) {
        return configure(context, width, height, width, height, multiplier, flowScale, 100, false, lsfgRequested);
    }

    public static String updateRuntimeParams(Context context,
                                             int multiplier, float flowScale,
                                             int renderScalePercent,
                                             boolean balancedEfficiency,
                                             boolean lsfgRequested) {
        if (!lsfgRequested) return lastStatus;
        if (!libraryLoaded) return loadMessage;
        if (!lastEngineReady) return lastStatus;
        int mult = Math.max(2, Math.min(8, multiplier));
        float flow = Math.max(0.10f, Math.min(1.00f, flowScale));
        int scale = Math.max(50, Math.min(100, renderScalePercent));
        try {
            String result;
            try {
                result = nativeUpdateParamsScaled(mult, flow, scale, balancedEfficiency);
            } catch (UnsatisfiedLinkError oldSo) {
                result = nativeUpdateParams(mult, flow)
                        + "\nV3.6 aviso: .so antiga sem update de Render Scale native.";
            }
            lastStatus = result;
            LsfgConfig.setEngineStatus(context, true, lastStatus);
            return lastStatus;
        } catch (Throwable t) {
            lastStatus = "Native params falharam: " + t.getClass().getSimpleName() + " • mantendo último motor válido";
            LsfgConfig.setEngineStatus(context, lastEngineReady, lastStatus);
            return lastStatus;
        }
    }

    /** V3.0 signature compatibility. */
    public static String updateRuntimeParams(Context context, int multiplier, float flowScale, boolean lsfgRequested) {
        return updateRuntimeParams(context, multiplier, flowScale, LsfgConfig.getRenderScalePercent(context),
                LsfgConfig.isBatterySaverEnabled(context), lsfgRequested);
    }

    public static String getLastStatus() { return lastStatus; }
    public static boolean isLastEngineReady() { return lastEngineReady; }

    public static double estimateAfterFps(double beforeFps, int multiplier, boolean lsfgRequested, boolean simpleInterpolation) {
        return estimateAfterFps(beforeFps, multiplier, lsfgRequested, simpleInterpolation, 1.0f);
    }

    public static double estimateAfterFps(double beforeFps, int multiplier, boolean lsfgRequested, boolean simpleInterpolation, float flowScale) {
        if (beforeFps <= 0.0) return 0.0;
        float flow = Math.max(0.10f, Math.min(1.00f, flowScale));
        if (lsfgRequested && lastEngineReady) {
            return beforeFps * Math.max(1, multiplier);
        }
        if (simpleInterpolation) {
            double blendFactor = 1.0 + (0.65 + 0.35 * flow);
            return beforeFps * blendFactor;
        }
        return beforeFps;
    }

    public static String buildShortMode(boolean lsfgRequested, boolean simpleInterpolation) {
        if (lsfgRequested && lastEngineReady) return "LSFG Vulkan";
        if (lsfgRequested) return libraryLoaded ? "LSFG preparando" : "LSFG sem .so";
        if (simpleInterpolation) return "Blend 2x Java";
        return "Mirror";
    }

    public static String formatFps(double value) { return String.format(Locale.US, "%.1f", value); }
}
