package com.bruno.frameoverlay;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLogger {
    private CrashLogger() { }

    public static void install(final Context context) {
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                writeCrash(context, thread, throwable);
                if (old != null) {
                    old.uncaughtException(thread, throwable);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    public static void writeCrash(Context context, Thread thread, Throwable throwable) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            File file = new File(dir, "bruno-frame-overlay-crash.txt");
            PrintWriter pw = new PrintWriter(new FileWriter(file, false));
            pw.println("Bruno Frame Overlay crash log");
            pw.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            pw.println("Thread: " + (thread != null ? thread.getName() : "unknown"));
            pw.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
            pw.println("Android: " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);
            pw.println("SDK_INT: " + Build.VERSION.SDK_INT);
            pw.println();
            if (throwable != null) throwable.printStackTrace(pw);
            pw.flush();
            pw.close();
        } catch (Throwable ignored) { }
    }

    public static String readCrash(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            File file = new File(dir, "bruno-frame-overlay-crash.txt");
            if (!file.exists()) return "Nenhum crash salvo ainda.";
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return "Falha ao ler crash: " + t.getClass().getSimpleName();
        }
    }
}
