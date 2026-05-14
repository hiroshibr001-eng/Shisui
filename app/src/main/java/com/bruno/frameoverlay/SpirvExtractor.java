package com.bruno.frameoverlay;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;

public final class SpirvExtractor {
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final int moduleCount;
        public final long totalBytes;
        public final File shaderDir;

        Result(boolean ok, String message, int moduleCount, long totalBytes, File shaderDir) {
            this.ok = ok;
            this.message = message;
            this.moduleCount = moduleCount;
            this.totalBytes = totalBytes;
            this.shaderDir = shaderDir;
        }
    }

    private static final int SPIRV_MAGIC_LE = 0x07230203;
    private static final int MIN_MODULE_BYTES = 128;
    private static final int MAX_MODULE_BYTES = 8 * 1024 * 1024;
    private static final long MAX_DLL_BYTES_TO_SCAN = 256L * 1024L * 1024L;

    private SpirvExtractor() { }

    public static File getShaderDir(Context context) {
        File dir = new File(context.getFilesDir(), "lossless/shaders");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static Result extractFromPrivateDll(Context context) {
        return extract(context, LosslessDllManager.getPrivateDllFile(context));
    }

    public static Result extract(Context context, File dllFile) {
        File shaderDir = getShaderDir(context);
        clearDir(shaderDir);

        if (dllFile == null || !dllFile.exists()) {
            LsfgConfig.setShaderInfo(context, false, 0, 0, "Lossless.dll não encontrada.");
            return new Result(false, "Lossless.dll não encontrada.", 0, 0L, shaderDir);
        }
        long dllSize = dllFile.length();
        if (dllSize <= 0 || dllSize > MAX_DLL_BYTES_TO_SCAN) {
            String msg = "DLL fora do limite de varredura: " + LosslessDllManager.formatBytes(dllSize);
            LsfgConfig.setShaderInfo(context, false, 0, 0, msg);
            return new Result(false, msg, 0, 0L, shaderDir);
        }

        byte[] data = new byte[(int) dllSize];
        try (FileInputStream in = new FileInputStream(dllFile)) {
            int off = 0;
            while (off < data.length) {
                int read = in.read(data, off, data.length - off);
                if (read < 0) break;
                off += read;
            }
            if (off != data.length) {
                String msg = "Leitura incompleta da DLL.";
                LsfgConfig.setShaderInfo(context, false, 0, 0, msg);
                return new Result(false, msg, 0, 0L, shaderDir);
            }
        } catch (Throwable t) {
            String msg = "Erro lendo DLL: " + t.getClass().getSimpleName();
            LsfgConfig.setShaderInfo(context, false, 0, 0, msg);
            return new Result(false, msg, 0, 0L, shaderDir);
        }

        int count = 0;
        long extractedBytes = 0L;
        HashSet<String> seenHashes = new HashSet<>();
        for (int i = 0; i <= data.length - 20; i += 4) {
            if (readU32(data, i) != SPIRV_MAGIC_LE) continue;
            int lengthBytes = findSpirvLength(data, i);
            if (lengthBytes < MIN_MODULE_BYTES) continue;
            if (lengthBytes > MAX_MODULE_BYTES) continue;
            if (i + lengthBytes > data.length) continue;

            String sha = sha256(data, i, lengthBytes);
            if (sha.length() == 0 || seenHashes.contains(sha)) continue;
            seenHashes.add(sha);

            File out = new File(shaderDir, String.format(Locale.US, "lsfg_shader_%03d.spv", count));
            try (FileOutputStream fos = new FileOutputStream(out, false)) {
                fos.write(data, i, lengthBytes);
                count++;
                extractedBytes += lengthBytes;
            } catch (Throwable ignored) { }
        }

        boolean ok = count > 0;
        String msg = ok
                ? "SPIR-V extraído: " + count + " módulo(s), " + LosslessDllManager.formatBytes(extractedBytes)
                : "Nenhum módulo SPIR-V encontrado na DLL.";
        LsfgConfig.setShaderInfo(context, ok, count, extractedBytes, msg);
        return new Result(ok, msg, count, extractedBytes, shaderDir);
    }

    private static int findSpirvLength(byte[] data, int offset) {
        if (offset + 20 > data.length) return 0;
        int magic = readU32(data, offset);
        if (magic != SPIRV_MAGIC_LE) return 0;
        int version = readU32(data, offset + 4);
        int major = (version >> 16) & 0xff;
        int minor = (version >> 8) & 0xff;
        if (major != 1 || minor > 6) return 0;
        int bound = readU32(data, offset + 12);
        if (bound <= 0 || bound > 2_000_000) return 0;

        int maxWords = Math.min((data.length - offset) / 4, MAX_MODULE_BYTES / 4);
        int word = 5;
        boolean hasMemoryModel = false;
        boolean hasEntryPoint = false;
        int instructionCount = 0;
        int lastGoodWord = 5;

        while (word < maxWords) {
            int instruction = readU32(data, offset + word * 4);
            int wordCount = (instruction >>> 16) & 0xffff;
            int opcode = instruction & 0xffff;
            if (wordCount <= 0 || wordCount > 4096) break;
            if (word + wordCount > maxWords) break;
            if (opcode == 14) hasMemoryModel = true;   // OpMemoryModel
            if (opcode == 15) hasEntryPoint = true;    // OpEntryPoint
            instructionCount++;
            word += wordCount;
            lastGoodWord = word;
            if (instructionCount > 200000) break;
        }

        if (!hasMemoryModel || !hasEntryPoint || instructionCount < 3) return 0;
        int bytes = lastGoodWord * 4;
        return bytes >= MIN_MODULE_BYTES ? bytes : 0;
    }

    private static int readU32(byte[] data, int off) {
        if (off < 0 || off + 4 > data.length) return 0;
        return (data[off] & 0xff)
                | ((data[off + 1] & 0xff) << 8)
                | ((data[off + 2] & 0xff) << 16)
                | ((data[off + 3] & 0xff) << 24);
    }

    private static String sha256(byte[] data, int off, int len) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, off, len);
            byte[] out = digest.digest();
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void clearDir(File dir) {
        if (dir == null) return;
        if (!dir.exists()) dir.mkdirs();
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try { if (f != null && f.isFile()) f.delete(); } catch (Throwable ignored) { }
        }
    }
}
