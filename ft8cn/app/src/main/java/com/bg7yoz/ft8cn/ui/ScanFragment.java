package com.bg7yoz.ft8cn.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.Locale;

public class ScanFragment extends Fragment {
    private static final String TAG = "ScanFragment";
    private MainViewModel mainViewModel;
    private Button btnStartStop, btnClearTable;
    private EditText etScanCycles;
    private LinearLayout containerScanContent;
    private TextView tvTotalAll, tvTotalNew, tvUtcTime, tvUtcDelay, tvRfFreq;
    private CheckBox cbHeaderHide, cbHeaderSelect;
    private Handler utcDelayHandler;

    private boolean isScanning = false;
    private int scanCycles = 2;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mainViewModel = MainViewModel.getInstance(this);
        utcDelayHandler = new Handler(Looper.getMainLooper());

        // Привязка элементов управления
        btnStartStop = view.findViewById(R.id.btnScanStartStop);
        btnClearTable = view.findViewById(R.id.btnClearTable);
        etScanCycles = view.findViewById(R.id.etDwellCycles);
        containerScanContent = view.findViewById(R.id.containerScanContent);
        tvTotalAll = view.findViewById(R.id.tvTotalAll);
        tvTotalNew = view.findViewById(R.id.tvTotalNew);
        tvUtcTime = view.findViewById(R.id.tvUtcTime);
        tvUtcDelay = view.findViewById(R.id.tvUtcDelay);
        tvRfFreq = view.findViewById(R.id.tvRfFreq);
        cbHeaderHide = view.findViewById(R.id.cbHeaderHide);
        cbHeaderSelect = view.findViewById(R.id.cbHeaderSelect);

        // Обработка кнопки Start/Stop
        btnStartStop.setOnClickListener(v -> {
            isScanning = !isScanning;
            btnStartStop.setText(isScanning ? "Stop" : "Start");
            scanCycles = parseScanCycles();
            if (isScanning) startScan(); else stopScan();
        });

        // Обработка кнопки очистки таблицы
        btnClearTable.setOnClickListener(v -> {
            containerScanContent.removeAllViews();
            tvTotalAll.setText("0");
            tvTotalNew.setText("0");
            Toast.makeText(getContext(), "Table cleared", Toast.LENGTH_SHORT).show();
        });

        // Обработка массового скрытия/показа строк
        cbHeaderHide.setOnClickListener(v -> showAllFrequencies(cbHeaderHide.isChecked()));
        // Обработка массового выбора строк
        cbHeaderSelect.setOnClickListener(v -> selectAllFrequencies(cbHeaderSelect.isChecked()));

        // === UTC Time Observer ===
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                tvUtcTime.setText(UtcTimer.getTimeStr(aLong));
            }
        });

        // === UTC Delay Handler ===
        utcDelayHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tvUtcDelay != null && getActivity() != null) {
                    tvUtcDelay.setText(String.format(Locale.US, "%+d", UtcTimer.delay));
                    utcDelayHandler.postDelayed(this, 500);
                }
            }
        }, 500);
        // === END UTC Delay ===

        // Инициализация текущей частоты
        tvRfFreq.setText(formatFreq(GeneralVariables.band));

        populateFrequencyTable();
        updateTotals();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (utcDelayHandler != null) {
            utcDelayHandler.removeCallbacksAndMessages(null);
        }
    }

    // Парсинг и валидация количества циклов
    private int parseScanCycles() {
        try { return Math.max(1, Math.min(10, Integer.parseInt(etScanCycles.getText().toString().trim()))); }
        catch (Exception e) { return 2; }
    }

    // Заполнение таблицы частотами из базы
    private void populateFrequencyTable() {
        containerScanContent.removeAllViews();
        int count = 0;
        LayoutInflater inflater = LayoutInflater.from(getContext());

        for (int i = 0; i < 20; i++) {
            try {
                long freq = OperationBand.getBandFreq(i);
                if (freq <= 0) continue;
                View rowView = inflater.inflate(R.layout.item_scan_row, containerScanContent, false);
                bindRowData(rowView, formatFreq(freq), freq, true, true, 0, 0, 0, 0, 0);
                containerScanContent.addView(rowView);
                count++;
            } catch (Exception e) { break; }
        }
        Log.d(TAG, "Loaded frequencies: " + count);

        if (count == 0) {
            View rowView = inflater.inflate(R.layout.item_scan_row, containerScanContent, false);
            bindRowData(rowView, "Test 14.074 MHz", 14074000L, true, true, 1, 2, 3, 1, 1);
            containerScanContent.addView(rowView);
        }
    }

    // Привязка данных к инфлейнутому шаблону строки
    private void bindRowData(View rowView, String label, long freq, boolean visible, boolean selected, int tot, int d, int c, int itu, int newStations) {
        rowView.setVisibility(visible ? View.VISIBLE : View.GONE);
        rowView.setTag(freq);

        CheckBox cbHide = rowView.findViewById(R.id.cbRowHide);
        cbHide.setChecked(visible);
        cbHide.setOnClickListener(v -> {
            rowView.setVisibility(cbHide.isChecked() ? View.VISIBLE : View.GONE);
            updateTotals();
        });

        CheckBox cbSelect = rowView.findViewById(R.id.cbRowSelect);
        cbSelect.setChecked(selected);

        TextView tvFreq = rowView.findViewById(R.id.tvRowFreq);
        tvFreq.setText(label);
        tvFreq.setOnClickListener(v -> switchToFrequency(freq));

        ((TextView) rowView.findViewById(R.id.tvRowTot)).setText(String.valueOf(tot));
        ((TextView) rowView.findViewById(R.id.tvRowD)).setText(String.valueOf(d));
        ((TextView) rowView.findViewById(R.id.tvRowC)).setText(String.valueOf(c));
        ((TextView) rowView.findViewById(R.id.tvRowI)).setText(String.valueOf(itu));
        ((TextView) rowView.findViewById(R.id.tvRowNew)).setText(String.valueOf(newStations));

        Button btnGo = rowView.findViewById(R.id.btnRowGo);
        btnGo.setOnClickListener(v -> switchToFrequency(freq));
    }

    // Форматирование Гц в МГц
    private String formatFreq(long freqHz) { return String.format("%.3f MHz", freqHz / 1_000_000f); }

    // Переключение частоты рига
    private void switchToFrequency(long freq) {
        if (mainViewModel != null) {
            GeneralVariables.band = freq;
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
            mainViewModel.setOperationBand();
            tvRfFreq.setText(formatFreq(freq));
            Toast.makeText(getContext(), "Switched to " + formatFreq(freq), Toast.LENGTH_SHORT).show();
        }
    }

    // Показать/скрыть все строки
    private void showAllFrequencies(boolean show) {
        for (int j = 0; j < containerScanContent.getChildCount(); j++) {
            View row = containerScanContent.getChildAt(j);
            CheckBox cb = row.findViewById(R.id.cbRowHide);
            cb.setChecked(show);
            row.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        updateTotals();
    }

    // Выбрать/снять выбор со всех строк
    private void selectAllFrequencies(boolean select) {
        for (int j = 0; j < containerScanContent.getChildCount(); j++) {
            CheckBox cb = containerScanContent.getChildAt(j).findViewById(R.id.cbRowSelect);
            cb.setChecked(select);
        }
    }

    // Пересчёт итогов по видимым строкам
    private void updateTotals() {
        int total = 0, newCount = 0;
        for (int j = 0; j < containerScanContent.getChildCount(); j++) {
            View row = containerScanContent.getChildAt(j);
            if (row.getVisibility() == View.VISIBLE) {
                total += parseInt(((TextView) row.findViewById(R.id.tvRowTot)).getText().toString());
                newCount += parseInt(((TextView) row.findViewById(R.id.tvRowNew)).getText().toString());
            }
        }
        tvTotalAll.setText(String.valueOf(total));
        tvTotalNew.setText(String.valueOf(newCount));
    }

    private int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private void startScan() { Toast.makeText(getContext(), "Scan started: " + scanCycles + " cycles", Toast.LENGTH_SHORT).show(); }
    private void stopScan() { Toast.makeText(getContext(), "Scan stopped", Toast.LENGTH_SHORT).show(); }
}