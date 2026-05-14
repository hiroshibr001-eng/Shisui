package com.bruno.frameoverlay;

import android.content.Context;
import android.content.SharedPreferences;

public final class LsfgConfig {
    private static final String PREFS = "bruno_frame_overlay_lsfg";
    private static final String KEY_DLL_READY = "dll_ready";
    private static final String KEY_DLL_SHA256 = "dll_sha256";
    private static final String KEY_DLL_SIZE = "dll_size";
    private static final String KEY_MULTIPLIER = "multiplier";
    private static final String KEY_FLOW_SCALE = "flow_scale";
    private static final String KEY_LSFG_REAL = "lsfg_real";
    private static final String KEY_TARGET_PACKAGE = "target_package";
    private static final String KEY_TARGET_LABEL = "target_label";
    private static final String KEY_AUTO_LAUNCH = "auto_launch";
    private static final String KEY_SHADER_READY = "shader_ready";
    private static final String KEY_SHADER_COUNT = "shader_count";
    private static final String KEY_SHADER_BYTES = "shader_bytes";
    private static final String KEY_SHADER_STATUS = "shader_status";
    private static final String KEY_ENGINE_READY = "engine_ready";
    private static final String KEY_ENGINE_STATUS = "engine_status";
    private static final String KEY_SURFACE_FLINGER_FPS = "surface_flinger_fps";
    private static final String KEY_RENDER_SCALE = "render_scale_percent";
    private static final String KEY_BATTERY_SAVER = "battery_saver";
    private static final String KEY_FPS_HUD_X = "fps_hud_x";
    private static final String KEY_FPS_HUD_Y = "fps_hud_y";
    private static final String KEY_FPS_HUD_SCALE = "fps_hud_scale";
    private static final String KEY_FPS_HUD_LOCKED = "fps_hud_locked";

    private LsfgConfig() { }

    public static boolean isDllReady(Context context) {
        return prefs(context).getBoolean(KEY_DLL_READY, false);
    }

    public static void setDllInfo(Context context, boolean ready, String sha256, long sizeBytes) {
        prefs(context).edit()
                .putBoolean(KEY_DLL_READY, ready)
                .putString(KEY_DLL_SHA256, sha256 != null ? sha256 : "")
                .putLong(KEY_DLL_SIZE, sizeBytes)
                .apply();
    }

    public static String getDllSha256(Context context) {
        return prefs(context).getString(KEY_DLL_SHA256, "");
    }

    public static long getDllSize(Context context) {
        return prefs(context).getLong(KEY_DLL_SIZE, 0L);
    }

    public static int getMultiplier(Context context) {
        int value = prefs(context).getInt(KEY_MULTIPLIER, 2);
        if (value < 2) return 2;
        if (value > 8) return 8;
        return value;
    }

    public static void setMultiplier(Context context, int value) {
        if (value < 2) value = 2;
        if (value > 8) value = 8;
        prefs(context).edit().putInt(KEY_MULTIPLIER, value).apply();
    }

    public static float getFlowScale(Context context) {
        float value = prefs(context).getFloat(KEY_FLOW_SCALE, 0.35f);
        if (value < 0.10f) return 0.10f;
        if (value > 1.00f) return 1.00f;
        return value;
    }

    public static void setFlowScale(Context context, float value) {
        if (value < 0.10f) value = 0.10f;
        if (value > 1.00f) value = 1.00f;
        prefs(context).edit().putFloat(KEY_FLOW_SCALE, value).apply();
    }

    public static boolean isLsfgRealRequested(Context context) {
        return prefs(context).getBoolean(KEY_LSFG_REAL, false);
    }

    public static void setLsfgRealRequested(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_LSFG_REAL, enabled).apply();
    }

    public static String getTargetPackage(Context context) {
        return prefs(context).getString(KEY_TARGET_PACKAGE, "");
    }

    public static String getTargetLabel(Context context) {
        return prefs(context).getString(KEY_TARGET_LABEL, "Nenhum jogo/app selecionado");
    }

    public static void setTargetApp(Context context, String packageName, String label) {
        prefs(context).edit()
                .putString(KEY_TARGET_PACKAGE, packageName != null ? packageName : "")
                .putString(KEY_TARGET_LABEL, label != null && label.length() > 0 ? label : "App selecionado")
                .apply();
    }

    public static boolean isShaderReady(Context context) {
        return prefs(context).getBoolean(KEY_SHADER_READY, false);
    }

    public static int getShaderCount(Context context) {
        return prefs(context).getInt(KEY_SHADER_COUNT, 0);
    }

    public static long getShaderBytes(Context context) {
        return prefs(context).getLong(KEY_SHADER_BYTES, 0L);
    }

    public static String getShaderStatus(Context context) {
        return prefs(context).getString(KEY_SHADER_STATUS, "Shaders SPIR-V: não preparados");
    }

    public static void setShaderInfo(Context context, boolean ready, int count, long bytes, String status) {
        prefs(context).edit()
                .putBoolean(KEY_SHADER_READY, ready)
                .putInt(KEY_SHADER_COUNT, Math.max(0, count))
                .putLong(KEY_SHADER_BYTES, Math.max(0L, bytes))
                .putString(KEY_SHADER_STATUS, status != null ? status : "")
                .apply();
    }

    public static boolean isEngineReady(Context context) {
        return prefs(context).getBoolean(KEY_ENGINE_READY, false);
    }

    public static String getEngineStatus(Context context) {
        return prefs(context).getString(KEY_ENGINE_STATUS, "Motor LSFG: não preparado");
    }

    public static void setEngineStatus(Context context, boolean ready, String status) {
        prefs(context).edit()
                .putBoolean(KEY_ENGINE_READY, ready)
                .putString(KEY_ENGINE_STATUS, status != null ? status : "")
                .apply();
    }

    public static boolean isSurfaceFlingerFpsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SURFACE_FLINGER_FPS, true);
    }

    public static void setSurfaceFlingerFpsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SURFACE_FLINGER_FPS, enabled).apply();
    }

    public static boolean isAutoLaunchEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_LAUNCH, false);
    }

    public static void setAutoLaunchEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_LAUNCH, enabled).apply();
    }

    public static int getRenderScalePercent(Context context) {
        int value = prefs(context).getInt(KEY_RENDER_SCALE, 85);
        if (value < 50) return 50;
        if (value > 100) return 100;
        return value;
    }

    public static void setRenderScalePercent(Context context, int value) {
        if (value < 50) value = 50;
        if (value > 100) value = 100;
        // Mantém valores múltiplos de 5 para evitar recriações pequenas demais.
        value = Math.round(value / 5.0f) * 5;
        prefs(context).edit().putInt(KEY_RENDER_SCALE, value).apply();
    }

    public static boolean isBatterySaverEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BATTERY_SAVER, true);
    }

    public static void setBatterySaverEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BATTERY_SAVER, enabled).apply();
    }

    public static int getFpsHudX(Context context) {
        return prefs(context).getInt(KEY_FPS_HUD_X, -1);
    }

    public static int getFpsHudY(Context context) {
        return prefs(context).getInt(KEY_FPS_HUD_Y, -1);
    }

    public static void setFpsHudPosition(Context context, int x, int y) {
        prefs(context).edit().putInt(KEY_FPS_HUD_X, Math.max(0, x)).putInt(KEY_FPS_HUD_Y, Math.max(0, y)).apply();
    }

    public static float getFpsHudScale(Context context) {
        float value = prefs(context).getFloat(KEY_FPS_HUD_SCALE, 1.0f);
        if (value < 0.75f) return 0.75f;
        if (value > 1.80f) return 1.80f;
        return value;
    }

    public static void setFpsHudScale(Context context, float value) {
        if (value < 0.75f) value = 0.75f;
        if (value > 1.80f) value = 1.80f;
        prefs(context).edit().putFloat(KEY_FPS_HUD_SCALE, value).apply();
    }

    public static boolean isFpsHudLocked(Context context) {
        return prefs(context).getBoolean(KEY_FPS_HUD_LOCKED, false);
    }

    public static void setFpsHudLocked(Context context, boolean locked) {
        prefs(context).edit().putBoolean(KEY_FPS_HUD_LOCKED, locked).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
