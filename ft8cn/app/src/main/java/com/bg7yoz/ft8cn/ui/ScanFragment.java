package com.bg7yoz.ft8cn.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.database.OperationBand;

public class ScanFragment extends Fragment {

    private MainViewModel mainViewModel;
    private Button btnStartStop;
    private EditText etDwellCycles;
    private TableLayout tableScanContent;
    private TextView tvTotalD, tvTotalC, tvTotalI, tvTotalAll;
    private CheckBox cbHeaderHideShow;

    private boolean isScanning = false;
    private int dwellCycles = 2;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mainViewModel = MainViewModel.getInstance(this);

        btnStartStop = view.findViewById(R.id.btnScanStartStop);
        etDwellCycles = view.findViewById(R.id.etDwellCycles);
        tableScanContent = view.findViewById(R.id.tableScanContent);
        tvTotalD = view.findViewById(R.id.tvTotalD);
        tvTotalC = view.findViewById(R.id.tvTotalC);
        tvTotalI = view.findViewById(R.id.tvTotalI);
        tvTotalAll = view.findViewById(R.id.tvTotalAll);
        cbHeaderHideShow = view.findViewById(R.id.cbHeaderHideShow);

        // Start/Stop button
        btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isScanning = !isScanning;
                btnStartStop.setText(isScanning ? "Stop" : "Start");
                dwellCycles = parseDwellCycles();
                if (isScanning) {
                    startScan();
                } else {
                    stopScan();
                }
            }
        });

        // Header Hide/Show: показать все скрытые частоты
        cbHeaderHideShow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAllFrequencies(cbHeaderHideShow.isChecked());
            }
        });

        // Populate table from OperationBand
        populateFrequencyTable();
        updateTotals();
    }

    private int parseDwellCycles() {
        try {
            int val = Integer.parseInt(etDwellCycles.getText().toString().trim());
            return Math.max(1, Math.min(10, val));
        } catch (Exception e) {
            return 2;
        }
    }

    private void populateFrequencyTable() {
        tableScanContent.removeAllViews();

        for (int i = 0; i < OperationBand.BAND_FREQS.length; i++) {
            long freq = OperationBand.BAND_FREQS[i];
            String label = OperationBand.getBandName(i) + " " + formatFreq(freq);
            addScanRow(label, freq, true, 0, 0, 0, 0);
        }
    }

    private String formatFreq(long freqHz) {
        return String.format("%.3f", freqHz / 1_000_000f);
    }

    private void addScanRow(String label, long freq, boolean visible, int d, int c, int i, int total) {
        TableRow row = new TableRow(getContext());
        row.setPadding(0, 4, 0, 4);
        row.setVisibility(visible ? View.VISIBLE : View.GONE);
        row.setTag(freq); // store frequency for click handler

        // 1. Hide/Show Checkbox (leftmost)
        CheckBox cb = new CheckBox(getContext());
        cb.setChecked(visible);
        cb.setPadding(8, 0, 8, 0);
        cb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                row.setVisibility(cb.isChecked() ? View.VISIBLE : View.GONE);
                updateTotals();
            }
        });
        row.addView(cb);

        // 2. Frequency label (clickable to switch)
        TextView tvFreq = new TextView(getContext());
        tvFreq.setText(label);
        tvFreq.setGravity(android.view.Gravity.CENTER);
        tvFreq.setLayoutParams(new TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        tvFreq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToFrequency(freq);
            }
        });
        row.addView(tvFreq);

        // 3. D, C, I, Total columns
        int[] values = {d, c, i, total};
        for (int val : values) {
            TextView tv = new TextView(getContext());
            tv.setText(String.valueOf(val));
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setLayoutParams(new TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);
        }

        // 4. Switch button (Go)
        Button btnGo = new Button(getContext());
        btnGo.setText("Go");
        btnGo.setTextSize(10);
        btnGo.setLayoutParams(new TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btnGo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToFrequency(freq);
            }
        });
        row.addView(btnGo);

        tableScanContent.addView(row);
    }

    private void switchToFrequency(long freq) {
        if (mainViewModel != null) {
            // Switch rig frequency
            mainViewModel.setOperationBandFrequency(freq);
            // TODO: Navigate to Calling fragment if needed
            Toast.makeText(getContext(), "Switched to " + formatFreq(freq) + " MHz", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAllFrequencies(boolean show) {
        for (int j = 0; j < tableScanContent.getChildCount(); j++) {
            View row = tableScanContent.getChildAt(j);
            if (row instanceof TableRow) {
                row.setVisibility(show ? View.VISIBLE : View.GONE);
                // Also update checkbox state
                CheckBox cb = (CheckBox) ((TableRow) row).getChildAt(0);
                cb.setChecked(show);
            }
        }
        updateTotals();
    }

    private void updateTotals() {
        int d = 0, c = 0, itu = 0, total = 0;
        for (int j = 0; j < tableScanContent.getChildCount(); j++) {
            TableRow row = (TableRow) tableScanContent.getChildAt(j);
            if (row.getVisibility() == View.VISIBLE && row.getChildCount() >= 6) {
                TextView tvD = (TextView) row.getChildAt(2);
                TextView tvC = (TextView) row.getChildAt(3);
                TextView tvI = (TextView) row.getChildAt(4);
                TextView tvTotal = (TextView) row.getChildAt(5);
                d += parseInt(tvD.getText().toString());
                c += parseInt(tvC.getText().toString());
                itu += parseInt(tvI.getText().toString());
                total += parseInt(tvTotal.getText().toString());
            }
        }
        tvTotalD.setText(String.valueOf(d));
        tvTotalC.setText(String.valueOf(c));
        tvTotalI.setText(String.valueOf(itu));
        tvTotalAll.setText(String.valueOf(total));
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void startScan() {
        // TODO: Connect to actual scanning engine
        // For now, just simulate
        Toast.makeText(getContext(), "Scan started: " + dwellCycles + " cycles", Toast.LENGTH_SHORT).show();
    }

    private void stopScan() {
        Toast.makeText(getContext(), "Scan stopped", Toast.LENGTH_SHORT).show();
    }
}