package com.bruno.frameoverlay;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class LosslessDllManager {
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final String sha256;
        public final long sizeBytes;

        Result(boolean ok, String message, String sha256, long sizeBytes) {
            this.ok = ok;
            this.message = message;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
        }
    }

    private LosslessDllManager() { }

    public static File getPrivateDllFile(Context context) {
        File dir = new File(context.getFilesDir(), "lossless");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "Lossless.dll");
    }

    public static Result importDll(Context context, Uri uri) {
        if (uri == null) return new Result(false, "URI inválida.", "", 0L);

        File outFile = getPrivateDllFile(context);
        byte[] firstBytes = new byte[2];
        long total = 0L;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Throwable t) {
            return new Result(false, "SHA-256 indisponível: " + t.getClass().getSimpleName(), "", 0L);
        }

        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile, false)) {
            if (in == null) return new Result(false, "Não foi possível abrir a DLL.", "", 0L);

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (total == 0 && read >= 2) {
                    firstBytes[0] = buffer[0];
                    firstBytes[1] = buffer[1];
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                total += read;
            }
        } catch (Throwable t) {
            try { if (outFile.exists()) outFile.delete(); } catch (Throwable ignored) { }
            return new Result(false, "Erro ao importar DLL: " + t.getClass().getSimpleName(), "", 0L);
        }

        if (total < 1024 * 1024) {
            try { if (outFile.exists()) outFile.delete(); } catch (Throwable ignored) { }
            return new Result(false, "Arquivo pequeno demais para parecer Lossless.dll.", "", total);
        }

        if (firstBytes[0] != 'M' || firstBytes[1] != 'Z') {
            try { if (outFile.exists()) outFile.delete(); } catch (Throwable ignored) { }
            return new Result(false, "Não parece DLL/PE válida: assinatura MZ ausente.", "", total);
        }

        String sha = toHex(digest.digest());
        LsfgConfig.setDllInfo(context, true, sha, total);
        SpirvExtractor.Result shaderResult = SpirvExtractor.extractFromPrivateDll(context);
        LsfgConfig.setEngineStatus(context, false, "Motor LSFG: aguardando preparação Vulkan");
        String msg = "Lossless.dll importada. " + shaderResult.message;
        return new Result(true, msg, sha, total);
    }

    public static String getReadableStatus(Context context) {
        if (!LsfgConfig.isDllReady(context)) {
            return "Lossless.dll: não importada";
        }
        String sha = LsfgConfig.getDllSha256(context);
        long size = LsfgConfig.getDllSize(context);
        String shortSha = sha != null && sha.length() >= 12 ? sha.substring(0, 12) : sha;
        return "Lossless.dll: OK • " + formatBytes(size) + " • SHA-256 " + shortSha + "..."
                + "\n" + LsfgConfig.getShaderStatus(context)
                + "\n" + LsfgConfig.getEngineStatus(context);
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.2f MB", mb);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }
}
