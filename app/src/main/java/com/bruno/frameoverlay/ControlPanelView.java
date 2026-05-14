package com.bruno.frameoverlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public class ControlPanelView extends ScrollView {
    public interface Listener {
        void onFullscreenChanged(boolean enabled);
        void onInterpolationChanged(boolean enabled);
        void onHudChanged(boolean enabled);
        void onLsfgRealChanged(boolean enabled);
        void onMultiplierChanged(int multiplier);
        void onFlowScaleChanged(float flow);
        void onBatterySaverChanged(boolean enabled);
        void onRenderScaleChanged(int percent);
        void onLaunchTargetRequested();
        void onRecreateRequested();
        void onCloseRequested();
        void onStopRequested();
    }

    private Listener listener;
    private final LinearLayout content;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statsText;
    private TextView multiplierText;
    private TextView flowText;
    private TextView renderScaleText;
    private boolean userScrolling;
    private String pendingStats;

    private final Runnable releaseScrollRunnable = new Runnable() {
        @Override public void run() {
            userScrolling = false;
            if (pendingStats != null) {
                applyStats(pendingStats);
                pendingStats = null;
            }
        }
    };

    public ControlPanelView(Context context, boolean fullscreen, boolean interpolation, boolean hud,
                            boolean lsfgReal, int multiplier, float flowScale, boolean dllReady,
                            String targetLabel, boolean batterySaver, int renderScalePercent) {
        super(context);
        setFillViewport(false);
        setVerticalScrollBarEnabled(true);
        setScrollbarFadingEnabled(false);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setClipToPadding(false);
        setPadding(0, 0, 0, 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD40B1118);
        bg.setStroke(dp(1), 0xEE7FEAFF);
        bg.setCornerRadius(dp(20));
        setBackground(bg);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText("Bruno Overlay V3.7");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(2));
        addContent(title);

        TextView subtitle = new TextView(context);
        subtitle.setText("Painel limpo • FPS móvel • Render Scale native");
        subtitle.setTextColor(0xFF7FEAFF);
        subtitle.setTextSize(11);
        subtitle.setPadding(0, 0, 0, dp(8));
        addContent(subtitle);

        TextView target = new TextView(context);
        target.setText("Alvo: " + (targetLabel != null ? targetLabel : "não selecionado"));
        target.setTextColor(0xFFB7C4CD);
        target.setTextSize(12);
        target.setPadding(0, 0, 0, dp(6));
        addContent(target);

        statsText = new TextView(context);
        statsText.setTextColor(0xFFDDE7EF);
        statsText.setTextSize(12);
        statsText.setText("Status: aguardando captura");
        statsText.setPadding(dp(8), dp(7), dp(8), dp(7));
        GradientDrawable statsBg = new GradientDrawable();
        statsBg.setColor(0x55121E28);
        statsBg.setStroke(dp(1), 0x557FEAFF);
        statsBg.setCornerRadius(dp(12));
        statsText.setBackground(statsBg);
        addContent(statsText);

        addDivider(context, "Imagem/captura");
        CheckBox fullscreenBox = buildBox(context, "Mirror fullscreen", fullscreen);
        fullscreenBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { if (listener != null) listener.onFullscreenChanged(isChecked); }
        });
        addContent(fullscreenBox);

        CheckBox interpolationBox = buildBox(context, "Fallback blend simples", interpolation);
        interpolationBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { if (listener != null) listener.onInterpolationChanged(isChecked); }
        });
        addContent(interpolationBox);

        CheckBox lsfgBox = buildBox(context, "Native/Vulkan + Lossless.dll", lsfgReal && dllReady);
        lsfgBox.setEnabled(dllReady);
        lsfgBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { if (listener != null) listener.onLsfgRealChanged(isChecked); }
        });
        addContent(lsfgBox);

        TextView dllText = new TextView(context);
        dllText.setText(dllReady ? "DLL importada" : "DLL não importada");
        dllText.setTextColor(0xFFB7C4CD);
        dllText.setTextSize(11);
        dllText.setPadding(0, 0, 0, dp(6));
        addContent(dllText);

        addDivider(context, "IA / geração de frames");
        multiplierText = label(context);
        updateMultiplierText(multiplier);
        addContent(multiplierText);
        SeekBar multiplierSeek = new SeekBar(context);
        multiplierSeek.setMax(6);
        multiplierSeek.setProgress(Math.max(0, Math.min(6, multiplier - 2)));
        multiplierSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 2;
                updateMultiplierText(value);
                if (fromUser && listener != null) listener.onMultiplierChanged(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { markUserScrolling(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { releaseUserScrollingSoon(); }
        });
        addContent(multiplierSeek);

        flowText = label(context);
        updateFlowText(flowScale);
        addContent(flowText);
        SeekBar flowSeek = new SeekBar(context);
        flowSeek.setMax(90);
        flowSeek.setProgress(Math.max(0, Math.min(90, Math.round(flowScale * 100f) - 10)));
        flowSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = (progress + 10) / 100f;
                updateFlowText(value);
                if (fromUser && listener != null) listener.onFlowScaleChanged(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { markUserScrolling(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { releaseUserScrollingSoon(); }
        });
        addContent(flowSeek);

        addDivider(context, "Eficiência equilibrada");
        CheckBox batteryBox = buildBox(context, "Render Scale native equilibrado", batterySaver);
        batteryBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { if (listener != null) listener.onBatterySaverChanged(isChecked); }
        });
        addContent(batteryBox);

        renderScaleText = label(context);
        updateRenderScaleText(renderScalePercent);
        addContent(renderScaleText);
        SeekBar renderSeek = new SeekBar(context);
        renderSeek.setMax(10);
        renderSeek.setProgress(Math.max(0, Math.min(10, (renderScalePercent - 50) / 5)));
        renderSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 50 + progress * 5;
                updateRenderScaleText(value);
                if (fromUser && listener != null) listener.onRenderScaleChanged(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { markUserScrolling(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { releaseUserScrollingSoon(); }
        });
        addContent(renderSeek);

        CheckBox hudBox = buildBox(context, "HUD FPS móvel", hud);
        hudBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { if (listener != null) listener.onHudChanged(isChecked); }
        });
        addContent(hudBox);

        TextView help = new TextView(context);
        help.setText("HUD: arraste para mover • pinça para redimensionar • duplo toque para fixar/desfixar.");
        help.setTextColor(0xFFB7C4CD);
        help.setTextSize(11);
        help.setPadding(0, dp(4), 0, dp(8));
        addContent(help);

        addDivider(context, "Ações");
        Button launchTarget = buildButton(context, "Abrir jogo selecionado");
        launchTarget.setOnClickListener(v -> { if (listener != null) listener.onLaunchTargetRequested(); });
        addContent(launchTarget);

        Button recreate = buildButton(context, "Recriar captura/tamanho");
        recreate.setOnClickListener(v -> { if (listener != null) listener.onRecreateRequested(); });
        addContent(recreate);

        Button close = buildButton(context, "Fechar painel");
        close.setOnClickListener(v -> { if (listener != null) listener.onCloseRequested(); });
        addContent(close);

        Button stop = buildButton(context, "Parar serviço");
        stop.setOnClickListener(v -> { if (listener != null) listener.onStopRequested(); });
        addContent(stop);
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN || ev.getActionMasked() == MotionEvent.ACTION_MOVE) markUserScrolling();
        if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) releaseUserScrollingSoon();
        return super.onTouchEvent(ev);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setStats(String text) {
        String compact = compactStats(text);
        if (userScrolling) {
            pendingStats = compact;
            return;
        }
        applyStats(compact);
    }

    private void applyStats(String text) { if (statsText != null) statsText.setText(text != null ? text : ""); }

    private String compactStats(String text) {
        if (text == null || text.trim().length() == 0) return "Status: aguardando";
        String[] lines = text.split("\\n");
        String fps = "";
        String mode = "";
        String render = "";
        String nativeLine = "";
        for (String line : lines) {
            if (line.startsWith("FPS atual")) fps = line.replace("FPS atual app:", "FPS:").trim();
            else if (line.startsWith("FPS real")) fps = (fps.length() > 0 ? fps + "  •  " : "") + line.replace("FPS real gerado por IA:", "IA:").trim();
            else if (line.startsWith("Modo:")) mode = line.trim();
            else if (line.contains("Render ")) render = line.trim();
            else if (line.contains("Native") || line.contains("Vulkan") || line.contains("Engine")) nativeLine = line.trim();
        }
        StringBuilder out = new StringBuilder();
        if (fps.length() > 0) out.append(fps);
        if (mode.length() > 0) out.append(out.length() > 0 ? "\n" : "").append(mode);
        else if (render.length() > 0) out.append(out.length() > 0 ? "\n" : "").append(render);
        if (nativeLine.length() > 0) out.append(out.length() > 0 ? "\n" : "").append(nativeLine);
        if (out.length() == 0) {
            String first = lines[0].trim();
            if (first.length() > 90) first = first.substring(0, 90) + "...";
            out.append(first);
        }
        return out.toString();
    }

    private void markUserScrolling() {
        userScrolling = true;
        handler.removeCallbacks(releaseScrollRunnable);
    }

    private void releaseUserScrollingSoon() {
        handler.removeCallbacks(releaseScrollRunnable);
        handler.postDelayed(releaseScrollRunnable, 900L);
    }

    private void updateMultiplierText(int multiplier) { if (multiplierText != null) multiplierText.setText("Multiplier: " + multiplier + "x"); }
    private void updateFlowText(float flow) { if (flowText != null) flowText.setText("Flow: " + String.format(Locale.US, "%.2f", flow)); }
    private void updateRenderScaleText(int value) {
        if (renderScaleText != null) {
            int pixels = Math.max(25, Math.round(value * value / 100.0f));
            renderScaleText.setText("Render Scale: " + value + "%  • pixels ~" + pixels + "%");
        }
    }

    private void addDivider(Context context, String text) {
        TextView v = new TextView(context);
        v.setText(text.toUpperCase(Locale.US));
        v.setTextColor(0xFF7FEAFF);
        v.setTextSize(10);
        v.setTypeface(null, Typeface.BOLD);
        v.setPadding(0, dp(8), 0, dp(3));
        addContent(v);
    }

    private TextView label(Context context) {
        TextView t = new TextView(context);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    private void addContent(android.view.View view) { content.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)); }

    private CheckBox buildBox(Context context, String text, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(text);
        box.setTextColor(Color.WHITE);
        box.setTextSize(13);
        box.setChecked(checked);
        box.setPadding(0, dp(1), 0, dp(1));
        return box;
    }

    private Button buildButton(Context context, String text) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        return button;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
