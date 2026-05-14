package com.bruno.frameoverlay;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

public final class SurfaceFlingerFpsReader {
    public interface Callback {
        void onSurfaceFlingerFps(double fps, String status);
    }

    private static final long POLL_INTERVAL_MS = 3200L; // V3.5: reduz consumo do polling SurfaceFlinger/Shizuku.
    private static final int MAX_OUTPUT_CHARS = 12000;

    private final Context context;
    private final String targetPackage;
    private final String targetLabel;
    private final Callback callback;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private String selectedLayer;
    private boolean latencyClearedForLayer;
    private double smoothFps;
    private int noLayerCount;

    public SurfaceFlingerFpsReader(Context context, String targetPackage, String targetLabel, Callback callback) {
        this.context = context.getApplicationContext();
        this.targetPackage = targetPackage != null ? targetPackage : "";
        this.targetLabel = targetLabel != null ? targetLabel : "";
        this.callback = callback;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("Bruno-SurfaceFlinger-FPS");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(pollRunnable);
    }

    public void stop() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) {
            try { thread.quitSafely(); } catch (Throwable ignored) { }
        }
        handler = null;
        thread = null;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { pollOnce(); }
            catch (Throwable t) { dispatch(0.0, "SurfaceFlinger FPS erro: " + t.getClass().getSimpleName()); }
            if (handler != null && running) handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void pollOnce() throws Exception {
        if (!Shizuku.pingBinder()) {
            dispatch(0.0, "SurfaceFlinger FPS: Shizuku OFF");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            dispatch(0.0, "SurfaceFlinger FPS: autorize Shizuku no app");
            return;
        }
        if (selectedLayer == null || selectedLayer.length() == 0 || noLayerCount > 6) {
            selectedLayer = findBestLayer();
            latencyClearedForLayer = false;
        }
        if (selectedLayer == null || selectedLayer.length() == 0) {
            noLayerCount++;
            dispatch(0.0, "SurfaceFlinger FPS: layer do jogo não encontrada");
            return;
        }
        noLayerCount = 0;
        if (!latencyClearedForLayer) {
            runShell("dumpsys SurfaceFlinger --latency-clear " + quote(selectedLayer));
            latencyClearedForLayer = true;
            dispatch(smoothFps, "SurfaceFlinger FPS: calibrando layer " + compactLayer(selectedLayer));
            return;
        }
        String out = runShell("dumpsys SurfaceFlinger --latency " + quote(selectedLayer));
        double fps = parseLatencyFps(out);
        if (fps > 1.0 && fps < 260.0) {
            smoothFps = smoothFps <= 0.1 ? fps : (smoothFps * 0.72 + fps * 0.28);
            dispatch(smoothFps, "SurfaceFlinger FPS OK: " + NativeLsfgBridge.formatFps(smoothFps)
                    + " • " + compactLayer(selectedLayer));
        } else {
            dispatch(smoothFps, "SurfaceFlinger FPS: sem timestamps novos • " + compactLayer(selectedLayer));
        }
    }

    private String findBestLayer() throws Exception {
        String list = runShell("dumpsys SurfaceFlinger --list");
        if (list == null || list.length() == 0) return "";
        String pkg = targetPackage.toLowerCase(Locale.US).trim();
        String label = targetLabel.toLowerCase(Locale.US).trim();
        String[] lines = list.split("\\r?\\n");
        String best = "";
        int bestScore = -1;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.length() == 0) continue;
            String lower = line.toLowerCase(Locale.US);
            int score = 0;
            if (pkg.length() > 0 && lower.contains(pkg)) score += 100;
            if (pkg.length() > 0) {
                int lastDot = pkg.lastIndexOf('.');
                if (lastDot >= 0 && lower.contains(pkg.substring(lastDot + 1))) score += 20;
            }
            if (label.length() > 0) {
                String[] words = label.split("\\s+");
                for (String w : words) if (w.length() >= 4 && lower.contains(w)) score += 10;
            }
            if (lower.contains("surfaceview")) score += 8;
            if (lower.contains("blast")) score += 4;
            if (lower.contains("wallpaper") || lower.contains("statusbar") || lower.contains("navigationbar") || lower.contains("launcher")) score -= 80;
            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        return bestScore > 0 ? best : "";
    }

    private double parseLatencyFps(String out) {
        if (out == null) return 0.0;
        String[] lines = out.split("\\r?\\n");
        List<Long> times = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i] != null ? lines[i].trim() : "";
            if (line.length() == 0) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;
            long actual = parseLongSafe(parts[1]);
            if (actual <= 0L && parts.length > 0) actual = parseLongSafe(parts[0]);
            if (actual > 0L) times.add(actual);
        }
        if (times.size() < 3) return 0.0;
        Collections.sort(times);
        List<Long> clean = new ArrayList<>();
        long last = 0L;
        for (long t : times) {
            if (last == 0L || t - last > 1_000_000L) {
                clean.add(t);
                last = t;
            }
        }
        if (clean.size() < 3) return 0.0;
        long first = clean.get(0);
        long lastTs = clean.get(clean.size() - 1);
        long span = lastTs - first;
        if (span <= 0L) return 0.0;
        return (clean.size() - 1) * 1_000_000_000.0 / span;
    }

    private long parseLongSafe(String s) {
        try { return Long.parseLong(s); }
        catch (Throwable ignored) { return 0L; }
    }

    private String runShell(String command) throws Exception {
        Process process = startShizukuProcess(command);
        StringBuilder sb = new StringBuilder();
        BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        String line;
        while ((line = out.readLine()) != null) {
            if (sb.length() < MAX_OUTPUT_CHARS) sb.append(line).append('\n');
        }
        while ((line = err.readLine()) != null) {
            if (sb.length() < MAX_OUTPUT_CHARS) sb.append(line).append('\n');
        }
        try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { process.destroy(); } catch (Throwable ignored) { }
        return sb.toString();
    }

    private Process startShizukuProcess(String command) throws Exception {
        // Shizuku 13.x keeps newProcess hidden/private in some artifacts.
        // Reflection avoids the javac private-access error while still using the
        // installed Shizuku service when permission is granted.
        Method method = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Object result = method.invoke(null, new String[]{"sh", "-c", command}, null, null);
        if (!(result instanceof Process)) {
            throw new IllegalStateException("Shizuku newProcess did not return Process");
        }
        return (Process) result;
    }

    private void dispatch(double fps, String status) {
        if (callback != null) callback.onSurfaceFlingerFps(fps, status != null ? status : "");
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String compactLayer(String layer) {
        if (layer == null) return "sem layer";
        String s = layer.trim();
        return s.length() > 42 ? s.substring(0, 42) + "..." : s;
    }
}
