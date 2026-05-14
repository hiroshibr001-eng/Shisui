package com.bruno.frameoverlay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class MainActivity extends Activity {
    private final Handler statusRefreshHandler = new Handler(Looper.getMainLooper());
    private int statusRefreshTicks;
    private boolean shizukuListenersInstalled;

    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;
    private static final int REQ_LOSSLESS_DLL = 1004;

    private TextView statusText;
    private TextView crashText;
    private TextView multiplierText;
    private TextView flowText;
    private TextView renderScaleText;
    private TextView targetText;
    private CheckBox fullscreenMirrorBox;
    private CheckBox interpolationBox;
    private CheckBox hudBox;
    private CheckBox lsfgRealBox;
    private CheckBox autoLaunchBox;
    private CheckBox batterySaverBox;
    private SeekBar multiplierSeek;
    private SeekBar flowSeek;
    private SeekBar renderScaleSeek;
    private MediaProjectionManager projectionManager;

    private static final class AppEntry {
        final String label;
        final String packageName;
        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLogger.install(this);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            buildUi();
            installShizukuStatusListeners();
            startStatusAutoRefresh();
            updateStatus();
        } catch (Throwable t) {
            CrashLogger.writeCrash(this, Thread.currentThread(), t);
            TextView fallback = new TextView(this);
            fallback.setText("Falha ao abrir UI no Android 15: " + t.getClass().getSimpleName()
                    + "\nAbra Ver/copiar último crash ou reinstale a V3.3 limpa.");
            fallback.setTextSize(16);
            fallback.setPadding(dp(18), dp(18), dp(18), dp(18));
            setContentView(fallback);
        }
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Bruno Frame Overlay V3.7");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("V3.7: HUD FPS móvel/redimensionável, painel limpo sem mensagens piscando e layout neon na bolha.");
        desc.setTextSize(15);
        desc.setPadding(0, 0, 0, dp(14));
        root.addView(desc, new LinearLayout.LayoutParams(-1, -2));

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        targetText = new TextView(this);
        targetText.setTextSize(14);
        targetText.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(targetText, new LinearLayout.LayoutParams(-1, -2));

        Button selectTargetButton = new Button(this);
        selectTargetButton.setText("Selecionar jogo/app alvo");
        selectTargetButton.setOnClickListener(v -> showTargetAppPicker());
        root.addView(selectTargetButton, new LinearLayout.LayoutParams(-1, -2));

        autoLaunchBox = new CheckBox(this);
        autoLaunchBox.setText("Após iniciar captura, abrir jogo selecionado automaticamente uma vez por sessão");
        autoLaunchBox.setChecked(LsfgConfig.isAutoLaunchEnabled(this));
        autoLaunchBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LsfgConfig.setAutoLaunchEnabled(this, isChecked);
            updateStatus();
        });
        root.addView(autoLaunchBox, new LinearLayout.LayoutParams(-1, -2));

        fullscreenMirrorBox = new CheckBox(this);
        fullscreenMirrorBox.setText("Mirror fullscreen / tela toda");
        fullscreenMirrorBox.setChecked(true);
        root.addView(fullscreenMirrorBox, new LinearLayout.LayoutParams(-1, -2));

        interpolationBox = new CheckBox(this);
        interpolationBox.setText("Fallback blend/interpolação simples");
        interpolationBox.setChecked(true);
        root.addView(interpolationBox, new LinearLayout.LayoutParams(-1, -2));

        lsfgRealBox = new CheckBox(this);
        lsfgRealBox.setText("Ativar modo Native/Vulkan + Lossless.dll");
        lsfgRealBox.setChecked(LsfgConfig.isLsfgRealRequested(this) && LsfgConfig.isDllReady(this));
        lsfgRealBox.setEnabled(LsfgConfig.isDllReady(this));
        lsfgRealBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean enabled = isChecked && LsfgConfig.isDllReady(this);
            LsfgConfig.setLsfgRealRequested(this, enabled);
            updateStatus();
        });
        root.addView(lsfgRealBox, new LinearLayout.LayoutParams(-1, -2));

        multiplierText = new TextView(this);
        multiplierText.setTextSize(14);
        multiplierText.setPadding(0, dp(8), 0, 0);
        root.addView(multiplierText, new LinearLayout.LayoutParams(-1, -2));

        multiplierSeek = new SeekBar(this);
        multiplierSeek.setMax(6); // 2x..8x
        multiplierSeek.setProgress(LsfgConfig.getMultiplier(this) - 2);
        multiplierSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int multiplier = progress + 2;
                LsfgConfig.setMultiplier(MainActivity.this, multiplier);
                updateMultiplierText(multiplier);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        root.addView(multiplierSeek, new LinearLayout.LayoutParams(-1, -2));
        updateMultiplierText(LsfgConfig.getMultiplier(this));

        flowText = new TextView(this);
        flowText.setTextSize(14);
        flowText.setPadding(0, dp(8), 0, 0);
        root.addView(flowText, new LinearLayout.LayoutParams(-1, -2));

        flowSeek = new SeekBar(this);
        flowSeek.setMax(90); // 0.10..1.00
        flowSeek.setProgress(Math.max(0, Math.min(90, Math.round(LsfgConfig.getFlowScale(this) * 100f) - 10)));
        flowSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float flow = (progress + 10) / 100f;
                LsfgConfig.setFlowScale(MainActivity.this, flow);
                updateFlowText(flow);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        root.addView(flowSeek, new LinearLayout.LayoutParams(-1, -2));
        updateFlowText(LsfgConfig.getFlowScale(this));

        batterySaverBox = new CheckBox(this);
        batterySaverBox.setText("Modo economia de bateria / menos cópia fullscreen");
        batterySaverBox.setChecked(LsfgConfig.isBatterySaverEnabled(this));
        batterySaverBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LsfgConfig.setBatterySaverEnabled(this, isChecked);
            updateStatus();
        });
        root.addView(batterySaverBox, new LinearLayout.LayoutParams(-1, -2));

        renderScaleText = new TextView(this);
        renderScaleText.setTextSize(14);
        renderScaleText.setPadding(0, dp(8), 0, 0);
        root.addView(renderScaleText, new LinearLayout.LayoutParams(-1, -2));

        renderScaleSeek = new SeekBar(this);
        renderScaleSeek.setMax(10); // 50%..100% em passos de 5
        renderScaleSeek.setProgress(Math.max(0, Math.min(10, (LsfgConfig.getRenderScalePercent(this) - 50) / 5)));
        renderScaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 50 + progress * 5;
                LsfgConfig.setRenderScalePercent(MainActivity.this, value);
                updateRenderScaleText(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        root.addView(renderScaleSeek, new LinearLayout.LayoutParams(-1, -2));
        updateRenderScaleText(LsfgConfig.getRenderScalePercent(this));

        hudBox = new CheckBox(this);
        hudBox.setText("HUD FPS: atual app / IA gerado");
        hudBox.setChecked(true);
        root.addView(hudBox, new LinearLayout.LayoutParams(-1, -2));

        Button importDllButton = new Button(this);
        importDllButton.setText("Selecionar minha Lossless.dll");
        importDllButton.setOnClickListener(v -> chooseLosslessDll());
        root.addView(importDllButton, new LinearLayout.LayoutParams(-1, -2));

        Button prepareEngineButton = new Button(this);
        prepareEngineButton.setText("Preparar motor LSFG / validar SPIR-V + Vulkan");
        prepareEngineButton.setOnClickListener(v -> prepareLsfgEngineNow());
        root.addView(prepareEngineButton, new LinearLayout.LayoutParams(-1, -2));

        Button stopButton = new Button(this);
        stopButton.setText("0. Parar serviço travado");
        stopButton.setOnClickListener(v -> stopCaptureService());
        root.addView(stopButton, new LinearLayout.LayoutParams(-1, -2));

        Button overlayButton = new Button(this);
        overlayButton.setText("1. Liberar permissão de sobreposição");
        overlayButton.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlayButton, new LinearLayout.LayoutParams(-1, -2));

        Button notifButton = new Button(this);
        notifButton.setText("2. Liberar notificação no Android 13+");
        notifButton.setOnClickListener(v -> requestNotificationPermissionIfNeeded());
        root.addView(notifButton, new LinearLayout.LayoutParams(-1, -2));

        Button shizukuButton = new Button(this);
        shizukuButton.setText("2.1. Reconectar/autorizar Shizuku para FPS real SurfaceFlinger");
        shizukuButton.setOnClickListener(v -> {
            ShizukuFpsPermission.request(this);
            installShizukuStatusListeners();
            startStatusAutoRefresh();
            updateStatus();
            statusText.postDelayed(() -> updateStatus(), 700);
            statusText.postDelayed(() -> updateStatus(), 1800);
        });
        root.addView(shizukuButton, new LinearLayout.LayoutParams(-1, -2));

        Button startButton = new Button(this);
        startButton.setText("3. Iniciar captura / overlay");
        startButton.setOnClickListener(v -> startCaptureFlow());
        root.addView(startButton, new LinearLayout.LayoutParams(-1, -2));

        Button crashButton = new Button(this);
        crashButton.setText("Ver/copiar último crash salvo");
        crashButton.setOnClickListener(v -> showCrashLog());
        root.addView(crashButton, new LinearLayout.LayoutParams(-1, -2));

        crashText = new TextView(this);
        crashText.setTextSize(12);
        crashText.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(crashText, new LinearLayout.LayoutParams(-1, -2));

        TextView tips = new TextView(this);
        tips.setText("Teste recomendado:\n\n" +
                "1) Selecione o jogo, ex: Brawl Stars.\n" +
                "2) Importe sua Lossless.dll legítima.\n" +
                "3) Use Multiplier 2x e Flow 0.25~0.35 no primeiro teste.\n" +
                "4) Inicie a captura; após aceitar, o app abre o jogo sozinho se a opção estiver ligada.\n" +
                "5) Use a bolinha B para painel e HUD atual app / IA gerado.\n\n" +
                "Economia: use Render Scale 75~85% para reduzir pixels processados, calor e bateria. " +
                "A V3.7 mantém Render Scale native e adiciona HUD móvel: arraste, pinça para tamanho e duplo toque para fixar/desfixar.");
        tips.setTextSize(14);
        tips.setPadding(0, dp(18), 0, 0);
        root.addView(tips, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scrollView);
        updateTargetText();
    }

    private void updateMultiplierText(int multiplier) {
        if (multiplierText != null) multiplierText.setText("Frame multiplier: " + multiplier + "x");
    }

    private void updateFlowText(float flow) {
        if (flowText != null) flowText.setText("Flow scale: " + String.format(Locale.US, "%.2f", flow));
    }

    private void updateRenderScaleText(int value) {
        if (renderScaleText != null) {
            int pixels = Math.max(25, Math.round(value * value / 100.0f));
            renderScaleText.setText("Render Scale: " + value + "%  •  pixels processados ~" + pixels + "%");
        }
    }

    private void updateTargetText() {
        if (targetText == null) return;
        String pkg = LsfgConfig.getTargetPackage(this);
        String label = LsfgConfig.getTargetLabel(this);
        targetText.setText("Jogo/app alvo: " + label + (pkg.length() > 0 ? "\nPacote: " + pkg : ""));
    }

    private void showTargetAppPicker() {
        final List<AppEntry> entries = loadLaunchableApps();
        if (entries.isEmpty()) {
            Toast.makeText(this, "Nenhum app iniciável encontrado.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            AppEntry e = entries.get(i);
            labels[i] = e.label + "\n" + e.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("Selecionar jogo/app alvo")
                .setItems(labels, (dialog, which) -> {
                    AppEntry e = entries.get(which);
                    LsfgConfig.setTargetApp(MainActivity.this, e.packageName, e.label);
                    updateTargetText();
                    updateStatus();
                    Toast.makeText(MainActivity.this, "Alvo: " + e.label, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private List<AppEntry> loadLaunchableApps() {
        List<AppEntry> entries = new ArrayList<>();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = getPackageManager().queryIntentActivities(intent, 0);
        if (infos != null) {
            for (ResolveInfo info : infos) {
                if (info == null || info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (getPackageName().equals(pkg)) continue;
                CharSequence labelSeq = info.loadLabel(getPackageManager());
                String label = labelSeq != null ? labelSeq.toString() : pkg;
                entries.add(new AppEntry(label, pkg));
            }
        }
        Collections.sort(entries, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return entries;
    }

    private void chooseLosslessDll() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_LOSSLESS_DLL);
        } catch (Throwable t) {
            Toast.makeText(this, "Seletor de arquivo indisponível: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void prepareLsfgEngineNow() {
        if (!LsfgConfig.isDllReady(this)) {
            Toast.makeText(this, "Importe a Lossless.dll primeiro.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!LsfgConfig.isShaderReady(this)) {
            SpirvExtractor.Result extraction = SpirvExtractor.extractFromPrivateDll(this);
            Toast.makeText(this, extraction.message, Toast.LENGTH_LONG).show();
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);
        String status = NativeLsfgBridge.configure(this, width, height,
                LsfgConfig.getMultiplier(this), LsfgConfig.getFlowScale(this), true);
        Toast.makeText(this, status.length() > 160 ? status.substring(0, 160) + "..." : status, Toast.LENGTH_LONG).show();
        updateStatus();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else {
            Toast.makeText(this, "Android abaixo de 13 não precisa dessa permissão.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } else {
            Toast.makeText(this, "Sobreposição já liberada.", Toast.LENGTH_SHORT).show();
            updateStatus();
        }
    }

    private void startCaptureFlow() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Libere a permissão de sobreposição primeiro.", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        if (projectionManager == null) {
            Toast.makeText(this, "MediaProjection indisponível neste aparelho.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQ_MEDIA_PROJECTION);
    }

    private void stopCaptureService() {
        try {
            Intent intent = new Intent(this, CaptureService.class);
            intent.setAction(CaptureService.ACTION_STOP);
            startService(intent);
            stopService(new Intent(this, CaptureService.class));
            Toast.makeText(this, "Serviço parado.", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Falha ao parar: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
        updateStatus();
    }

    private void showCrashLog() {
        String crash = CrashLogger.readCrash(this);
        crashText.setText(crash);
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("bruno-frame-overlay-crash", crash));
                Toast.makeText(this, "Crash copiado para a área de transferência.", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            updateStatus();
            return;
        }
        if (requestCode == REQ_LOSSLESS_DLL) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                LosslessDllManager.Result result = LosslessDllManager.importDll(this, data.getData());
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                if (result.ok) {
                    LsfgConfig.setLsfgRealRequested(this, true);
                    if (lsfgRealBox != null) {
                        lsfgRealBox.setEnabled(true);
                        lsfgRealBox.setChecked(true);
                    }
                }
                updateStatus();
            } else {
                Toast.makeText(this, "Seleção da DLL cancelada.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, CaptureService.class);
                serviceIntent.setAction(CaptureService.ACTION_START);
                serviceIntent.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
                serviceIntent.putExtra(CaptureService.EXTRA_FULLSCREEN_MIRROR, fullscreenMirrorBox != null && fullscreenMirrorBox.isChecked());
                serviceIntent.putExtra(CaptureService.EXTRA_SIMPLE_INTERPOLATION, interpolationBox != null && interpolationBox.isChecked());
                serviceIntent.putExtra(CaptureService.EXTRA_DEBUG_HUD, hudBox != null && hudBox.isChecked());
                serviceIntent.putExtra(CaptureService.EXTRA_LSFG_REAL, lsfgRealBox != null && lsfgRealBox.isChecked() && LsfgConfig.isDllReady(this));
                serviceIntent.putExtra(CaptureService.EXTRA_MULTIPLIER, LsfgConfig.getMultiplier(this));
                serviceIntent.putExtra(CaptureService.EXTRA_FLOW_SCALE, LsfgConfig.getFlowScale(this));
                serviceIntent.putExtra(CaptureService.EXTRA_TARGET_PACKAGE, LsfgConfig.getTargetPackage(this));
                serviceIntent.putExtra(CaptureService.EXTRA_AUTO_LAUNCH, autoLaunchBox == null || autoLaunchBox.isChecked());
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent); else startService(serviceIntent);
                    Toast.makeText(this, "Captura iniciada. Abrindo alvo se configurado.", Toast.LENGTH_LONG).show();
                    moveTaskToBack(true);
                } catch (Throwable t) {
                    Toast.makeText(this, "Erro ao iniciar serviço: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Permissão de captura negada.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        installShizukuStatusListeners();
        startStatusAutoRefresh();
        updateStatus();
        updateTargetText();
        if (lsfgRealBox != null) {
            lsfgRealBox.setEnabled(LsfgConfig.isDllReady(this));
            lsfgRealBox.setChecked(LsfgConfig.isLsfgRealRequested(this) && LsfgConfig.isDllReady(this));
        }
        if (autoLaunchBox != null) autoLaunchBox.setChecked(LsfgConfig.isAutoLaunchEnabled(this));
        if (batterySaverBox != null) batterySaverBox.setChecked(LsfgConfig.isBatterySaverEnabled(this));
        if (renderScaleSeek != null) renderScaleSeek.setProgress(Math.max(0, Math.min(10, (LsfgConfig.getRenderScalePercent(this) - 50) / 5)));
        updateRenderScaleText(LsfgConfig.getRenderScalePercent(this));
    }

    private void startStatusAutoRefresh() {
        statusRefreshTicks = 0;
        statusRefreshHandler.removeCallbacksAndMessages(null);
        statusRefreshHandler.post(new Runnable() {
            @Override public void run() {
                try { updateStatus(); } catch (Throwable ignored) { }
                statusRefreshTicks++;
                if (statusRefreshTicks < 20) {
                    statusRefreshHandler.postDelayed(this, 1000);
                }
            }
        });
    }

    private void installShizukuStatusListeners() {
        if (shizukuListenersInstalled) return;
        try {
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            Class<?> receivedClass = Class.forName("rikka.shizuku.Shizuku$OnBinderReceivedListener");
            Object receivedProxy = Proxy.newProxyInstance(
                    getClassLoader(),
                    new Class[]{receivedClass},
                    (proxy, method, args) -> {
                        runOnUiThread(() -> { startStatusAutoRefresh(); updateStatus(); });
                        return null;
                    });
            try {
                Method sticky = shizukuClass.getMethod("addBinderReceivedListenerSticky", receivedClass);
                sticky.invoke(null, receivedProxy);
            } catch (NoSuchMethodException noSticky) {
                Method add = shizukuClass.getMethod("addBinderReceivedListener", receivedClass);
                add.invoke(null, receivedProxy);
            }

            try {
                Class<?> deadClass = Class.forName("rikka.shizuku.Shizuku$OnBinderDeadListener");
                Object deadProxy = Proxy.newProxyInstance(
                        getClassLoader(),
                        new Class[]{deadClass},
                        (proxy, method, args) -> {
                            runOnUiThread(() -> { startStatusAutoRefresh(); updateStatus(); });
                            return null;
                        });
                Method addDead = shizukuClass.getMethod("addBinderDeadListener", deadClass);
                addDead.invoke(null, deadProxy);
            } catch (Throwable ignoredDead) { }

            shizukuListenersInstalled = true;
        } catch (Throwable ignored) {
            // Se a API variar, o botão ainda faz atualização por polling.
        }
    }

    @Override
    protected void onDestroy() {
        statusRefreshHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void updateStatus() {
        boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        String vulkan = NativeLsfgBridge.getVulkanSummary();
        statusText.setText("Status:\n" +
                "• Sobreposição: " + (overlayOk ? "OK" : "não liberada") + "\n" +
                "• Android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n" +
                "• V3.7: HUD móvel + painel limpo + Render Scale native\n" +
                "• " + ShizukuFpsPermission.status(this) + "\n" +
                "• " + LosslessDllManager.getReadableStatus(this) + "\n" +
                "• Modo Native/Vulkan: " + ((LsfgConfig.isLsfgRealRequested(this) && LsfgConfig.isDllReady(this)) ? "solicitado" : "OFF") + "\n" +
                "• Economia: " + (LsfgConfig.isBatterySaverEnabled(this) ? "ON" : "OFF")
                    + " • Render Scale " + LsfgConfig.getRenderScalePercent(this) + "%\n" +
                "• " + NativeLsfgBridge.getLoadMessage() + "\n" +
                "• " + vulkan);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
