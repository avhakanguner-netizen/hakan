package com.hakanguner.mhiir;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int CARRIER_HZ = 36000;

    // Reverse-engineered protocol supplied for a very similar Mitsubishi Heavy remote:
    // 36 kHz, header 6000/7500 us, bit mark 500 us,
    // 0 gap 1500 us, 1 gap 3500 us, 32 data bits + inverted 32 bits,
    // footer 500/7500/500 us.
    // This has NOT yet been independently confirmed on SRK503HENF-W / RKH011H505B.

    private static final int HEADER_MARK = 6000;
    private static final int HEADER_GAP = 7500;
    private static final int BIT_MARK = 500;
    private static final int ZERO_GAP = 1500;
    private static final int ONE_GAP = 3500;

    // Conservative first-test frame using only unambiguous values:
    // fan low, swing off, 17 C, auto mode, power off, airflow auto.
    private static final String TEST_POWER_OFF_FIRST32 = "01010000000000000000000000000010";

    private ConsumerIrManager irManager;
    private TextView statusView;
    private TextView tempView;
    private TextView frameView;
    private int temperature = 24;
    private Spinner modeSpinner;
    private Spinner fanSpinner;
    private Spinner directionSpinner;
    private CheckBox swingBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        irManager = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        setContentView(buildUi());
        refreshIrStatus();
        refreshFramePreview(true);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(Color.rgb(246, 247, 249));
        scroll.addView(root);

        TextView title = text("MHI SRK503 IR Kumanda", 25, true);
        title.setTextColor(Color.rgb(25, 36, 55));
        root.addView(title);

        TextView subtitle = text("Mitsubishi Heavy Industries SRK503HENF-W için 36 kHz deneysel IR kumanda", 14, false);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        statusView = text("IR kontrol ediliyor…", 14, true);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusView.setBackgroundColor(Color.WHITE);
        root.addView(statusView, matchWrap());

        TextView warning = text("Not: Protokol, PJA502A704AA için yapılmış reverse-engineering verisine dayanıyor. SRK503HENF-W üzerinde ilk test için alttaki ‘İLK TEST: KAPAT’ düğmesini kullanın.", 13, false);
        warning.setTextColor(Color.rgb(120, 70, 0));
        warning.setPadding(0, dp(12), 0, dp(14));
        root.addView(warning);

        addSection(root, "Sıcaklık");
        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setOrientation(LinearLayout.HORIZONTAL);
        tempRow.setGravity(Gravity.CENTER_VERTICAL);

        Button minus = button("−");
        minus.setOnClickListener(v -> {
            if (temperature > 17) temperature--;
            refreshTemperature();
            refreshFramePreview(true);
        });
        tempRow.addView(minus, new LinearLayout.LayoutParams(dp(70), dp(52)));

        tempView = text("24 °C", 28, true);
        tempView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        tempRow.addView(tempView, tempLp);

        Button plus = button("+");
        plus.setOnClickListener(v -> {
            if (temperature < 30) temperature++;
            refreshTemperature();
            refreshFramePreview(true);
        });
        tempRow.addView(plus, new LinearLayout.LayoutParams(dp(70), dp(52)));
        root.addView(tempRow, matchWrap());

        addSection(root, "Çalışma modu");
        modeSpinner = spinner(new String[]{"Auto", "Soğutma", "Isıtma", "Kuru / Dry", "Fan"});
        root.addView(modeSpinner, matchWrap());

        addSection(root, "Fan hızı");
        fanSpinner = spinner(new String[]{"Düşük", "Orta", "Yüksek"});
        fanSpinner.setSelection(1);
        root.addView(fanSpinner, matchWrap());

        addSection(root, "Panjur / hava akışı");
        swingBox = new CheckBox(this);
        swingBox.setText("Swing açık");
        swingBox.setTextSize(16);
        root.addView(swingBox, matchWrap());

        directionSpinner = spinner(new String[]{"Otomatik", "Yukarı", "Aşağı"});
        root.addView(directionSpinner, matchWrap());

        View.OnClickListener previewListener = v -> refreshFramePreview(true);
        modeSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(previewListener));
        fanSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(previewListener));
        directionSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(previewListener));
        swingBox.setOnCheckedChangeListener((buttonView, isChecked) -> refreshFramePreview(true));

        addSection(root, "Gönder");

        Button sendOn = button("AÇ / AYARLARI GÖNDER");
        sendOn.setTextSize(17);
        sendOn.setMinHeight(dp(56));
        sendOn.setOnClickListener(v -> sendGenerated(true));
        root.addView(sendOn, matchWrapWithTop(dp(4)));

        Button sendOff = button("KAPAT (oluşturulan frame)");
        sendOff.setMinHeight(dp(52));
        sendOff.setOnClickListener(v -> sendGenerated(false));
        root.addView(sendOff, matchWrapWithTop(dp(8)));

        Button rawOff = button("İLK TEST: KAPAT (17°C / Auto)");
        rawOff.setMinHeight(dp(52));
        rawOff.setOnClickListener(v -> sendTestPowerOff());
        root.addView(rawOff, matchWrapWithTop(dp(8)));

        addSection(root, "64-bit veri");
        frameView = text("", 12, false);
        frameView.setTextIsSelectable(true);
        frameView.setPadding(dp(10), dp(10), dp(10), dp(10));
        frameView.setBackgroundColor(Color.WHITE);
        root.addView(frameView, matchWrap());

        TextView footer = text("Taşıyıcı: 36.000 Hz • Header: 6000/7500 µs • 0: 500/1500 µs • 1: 500/3500 µs", 12, false);
        footer.setTextColor(Color.GRAY);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer);

        return scroll;
    }

    private void refreshIrStatus() {
        if (irManager == null || !irManager.hasIrEmitter()) {
            statusView.setText("IR verici bulunamadı. Bu telefon ConsumerIrManager üzerinden kızılötesi gönderemiyor.");
            statusView.setTextColor(Color.rgb(160, 20, 20));
            return;
        }

        boolean supports36 = false;
        List<String> ranges = new ArrayList<>();
        ConsumerIrManager.CarrierFrequencyRange[] frequencyRanges = irManager.getCarrierFrequencies();
        if (frequencyRanges != null) {
            for (ConsumerIrManager.CarrierFrequencyRange range : frequencyRanges) {
                int min = range.getMinFrequency();
                int max = range.getMaxFrequency();
                if (CARRIER_HZ >= min && CARRIER_HZ <= max) supports36 = true;
                ranges.add((min / 1000.0) + "–" + (max / 1000.0) + " kHz");
            }
        }
        String rangeText = ranges.isEmpty() ? "frekans aralığı bildirilmedi" : TextUtils.join(", ", ranges);
        statusView.setText("IR verici: VAR\n36 kHz: " + (supports36 ? "destek aralığında" : "destek listesinde görünmüyor") + "\nTelefonun bildirdiği aralıklar: " + rangeText);
        statusView.setTextColor(supports36 ? Color.rgb(20, 115, 55) : Color.rgb(150, 90, 0));
    }

    private void sendGenerated(boolean powerOn) {
        String first32 = buildFirst32(powerOn);
        String frame64 = first32 + invert(first32);
        int[] pattern = buildPattern(frame64);
        transmit(pattern, (powerOn ? "Açık/ayar" : "Kapalı") + " komutu gönderildi", frame64);
    }

    private void sendTestPowerOff() {
        String frame64 = TEST_POWER_OFF_FIRST32 + invert(TEST_POWER_OFF_FIRST32);
        transmit(buildPattern(frame64), "İlk test kapatma komutu gönderildi", frame64);
    }

    private void transmit(int[] pattern, String successMessage, String frameText) {
        if (irManager == null || !irManager.hasIrEmitter()) {
            Toast.makeText(this, "Bu telefonda Android IR vericisi bulunamadı.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            irManager.transmit(CARRIER_HZ, pattern);
            frameView.setText(frameText + "\n\nPattern elemanı: " + pattern.length + " • 36.000 Hz");
            Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "IR gönderme hatası: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String buildFirst32(boolean powerOn) {
        StringBuilder b = new StringBuilder(32);
        b.append("010100000000");

        switch (fanSpinner.getSelectedItemPosition()) {
            case 0: b.append("00"); break;
            case 1: b.append("10"); break;
            case 2: b.append("01"); break;
            default: b.append("00");
        }

        b.append(swingBox.isChecked() ? '1' : '0');
        b.append('0');

        int tempCode = temperature - 17;
        for (int i = 0; i < 4; i++) {
            b.append(((tempCode >> i) & 1) == 1 ? '1' : '0');
        }

        switch (modeSpinner.getSelectedItemPosition()) {
            case 0: b.append("000"); break;
            case 1: b.append("010"); break;
            case 2: b.append("001"); break;
            case 3: b.append("100"); break;
            case 4: b.append("110"); break;
            default: b.append("000");
        }

        b.append(powerOn ? '1' : '0');
        b.append("0000");

        switch (directionSpinner.getSelectedItemPosition()) {
            case 0: b.append("00"); break;
            case 1: b.append("10"); break;
            case 2: b.append("11"); break;
            default: b.append("00");
        }

        b.append("10");

        if (b.length() != 32) {
            throw new IllegalStateException("Frame uzunluğu 32 değil: " + b.length());
        }
        return b.toString();
    }

    private String invert(String bits) {
        StringBuilder out = new StringBuilder(bits.length());
        for (int i = 0; i < bits.length(); i++) {
            out.append(bits.charAt(i) == '0' ? '1' : '0');
        }
        return out.toString();
    }

    private int[] buildPattern(String frame64) {
        List<Integer> p = new ArrayList<>();
        p.add(HEADER_MARK);
        p.add(HEADER_GAP);
        for (int i = 0; i < frame64.length(); i++) {
            p.add(BIT_MARK);
            p.add(frame64.charAt(i) == '1' ? ONE_GAP : ZERO_GAP);
        }
        p.add(500);
        p.add(7500);
        p.add(500);
        return toIntArray(p);
    }

    private int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    private void refreshTemperature() {
        tempView.setText(temperature + " °C");
    }

    private void refreshFramePreview(boolean powerOn) {
        if (frameView == null || modeSpinner == null || fanSpinner == null || directionSpinner == null || swingBox == null) return;
        try {
            String first32 = buildFirst32(powerOn);
            frameView.setText(first32 + "\n" + invert(first32) + "\n\nİlk 32 bit + bit-bit tersi");
        } catch (Exception ignored) {
        }
    }

    private void addSection(LinearLayout root, String title) {
        TextView v = text(title, 15, true);
        v.setTextColor(Color.rgb(50, 60, 75));
        v.setPadding(0, dp(16), 0, dp(6));
        root.addView(v);
    }

    private TextView text(String value, float sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        return s;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int top) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = top;
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final View.OnClickListener listener;
        SimpleItemSelectedListener(View.OnClickListener listener) { this.listener = listener; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            listener.onClick(parent);
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
    }
}
