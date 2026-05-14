package com.bruno.frameoverlay;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public final class ShizukuFpsPermission {
    private static final int REQ_SHIZUKU_FPS = 3301;

    private ShizukuFpsPermission() { }

    public static boolean isAvailable() {
        try { return Shizuku.pingBinder(); }
        catch (Throwable ignored) { return false; }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String status(Context context) {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku: aguardando binder do serviço";
            int perm = Shizuku.checkSelfPermission();
            if (perm == PackageManager.PERMISSION_GRANTED) return "Shizuku: OK para SurfaceFlinger FPS";
            return "Shizuku: sem permissão para FPS real";
        } catch (Throwable t) {
            return "Shizuku: indisponível (" + t.getClass().getSimpleName() + ")";
        }
    }

    public static void request(Activity activity) {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(activity, "Shizuku rodando, mas binder ainda não chegou no app. Feche/abra o app ou aguarde 2s e toque de novo.", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(activity, "Shizuku já autorizado para FPS SurfaceFlinger.", Toast.LENGTH_SHORT).show();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU_FPS);
            Toast.makeText(activity, "Autorize o app no prompt do Shizuku.", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(activity, "Falha ao pedir Shizuku: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }
}
