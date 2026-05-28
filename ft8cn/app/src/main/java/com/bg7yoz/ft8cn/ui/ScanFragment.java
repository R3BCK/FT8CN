// [MOD] ScanFragment.java - Full version with dynamic frequency counting via Map
// Changes marked with: // [MOD] ... // [/MOD]
// Old code preserved with: // [OLD] ... // [/OLD]

package com.bg7yoz.ft8cn.ui;

import android.content.Context;
import android.content.SharedPreferences;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.callsign.CallsignInfo;
import com.bg7yoz.ft8cn.callsign.OnAfterQueryCallsignLocation;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.rigs.IcomRigConstant;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ScanFragment extends Fragment {
    private static final String TAG = "ScanFragment";

    // === [STATE] Preferences keys ===
    private static final String PREFS_SCAN = "scan_fragment_prefs";
    private static final String KEY_SELECTED_FREQS = "selected_frequencies";
    private static final String KEY_CYCLES = "scan_cycles";
    // [MOD] Updated comment to reflect RFfreq naming
    // [OLD]
    // private static final String KEY_COUNTERS = "row_counters"; // Format: "freq:tot,d,c,itu,new;..."
    // [/OLD]
    // [NEW]
    private static final String KEY_COUNTERS = "row_counters"; // Format: "RFfreq:tot,d,c,itu,new;..."
    // [/MOD]

    private MainViewModel mainViewModel;
    private NavController navController;
    private Button btnStartStop;
    private ImageView btnClearTable;
    private EditText etScanCycles;
    private LinearLayout containerScanContent;
    private TextView tvTotalAll, tvTotalNew, tvUtcTime, tvUtcDelay, tvRfFreq, tvConnStatus, tvRigType;
    private CheckBox cbHeaderHide, cbHeaderSelect;

    private Handler scanHandler, utcDelayHandler;
    private boolean isScanning = false;
    private int scanCycles = 4;
    private int currentFreqIndex = 0;
    private boolean isTuneSent = false;

    // [AUDIO] Flag to track if we paused the global listener
    private boolean wasListenerPaused = false;

    // [SYNC] Lock for synchronizing frequency switching and decoding
    private final Object freqSwitchLock = new Object();

    // [SYNC] Lists for slot-synchronized frequency selection
    private final List<Long> pendingScanFrequencies = new ArrayList<>();
    private final List<Long> activeScanFrequencies = new ArrayList<>();
    private final Object scanListLock = new Object();

    // Data structures for counting
    private final Set<String> scannedCallsigns = new HashSet<>();
    private final Set<String> scannedDxcc = new HashSet<>();
    private final Set<Integer> scannedCq = new HashSet<>();
    private final Set<Integer> scannedItu = new HashSet<>();
    private int totalStations = 0;
    private int newStations = 0;

    // [MOD] Dynamic per-frequency counters via Map (no hardcoded array size)
    private final Map<Long, Integer> freqTotals = new HashMap<>();
    private final Map<Long, Integer> freqNew = new HashMap<>();
    // [/MOD]

    private Observer<ArrayList<Ft8Message>> scanMessageObserver;
    private Observer<Long> scanTimerObserver;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mainViewModel = MainViewModel.getInstance(this);

        NavHostFragment navHostFragment = (NavHostFragment) requireActivity()
                .getSupportFragmentManager()
                .findFragmentById(R.id.fragmentContainerView);
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }

        scanHandler = new Handler(Looper.getMainLooper());
        utcDelayHandler = new Handler(Looper.getMainLooper());

        // Bind UI components
        btnStartStop = view.findViewById(R.id.btnScanStartStop);
        btnClearTable = view.findViewById(R.id.btnClearTable);
        etScanCycles = view.findViewById(R.id.etDwellCycles);
        containerScanContent = view.findViewById(R.id.containerScanContent);
        tvTotalAll = view.findViewById(R.id.tvTotalAll);
        tvTotalNew = view.findViewById(R.id.tvTotalNew);
        tvUtcTime = view.findViewById(R.id.tvUtcTime);
        tvUtcDelay = view.findViewById(R.id.tvUtcDelay);
        tvRfFreq = view.findViewById(R.id.tvRfFreq);
        tvConnStatus = view.findViewById(R.id.tvConnStatus);
        tvRigType = view.findViewById(R.id.tvRigType);
        cbHeaderHide = view.findViewById(R.id.cbHeaderHide);
        cbHeaderSelect = view.findViewById(R.id.cbHeaderSelect);

        // === [MOD] Disable header GO button programmatically (safety) ===
        // [OLD]
        // (no code for btnHeaderGo in original)
        // [/OLD]
        // [NEW]
        Button btnHeaderGo = view.findViewById(R.id.btnHeaderGo);
        if (btnHeaderGo != null) {
            btnHeaderGo.setEnabled(false);
            btnHeaderGo.setClickable(false);
            btnHeaderGo.setFocusable(false);
            //Log.d(TAG, "Header GO button disabled programmatically");
        }
        // [/MOD]

        // === [AUDIO] Pause global listener when entering Scan ===
        // This prevents crashes caused by concurrent audio processing
        pauseGlobalListenerIfNeeded();

        // === START/STOP BUTTON ===
        btnStartStop.setOnClickListener(v -> {
            synchronized (freqSwitchLock) {
                if (!isScanning) {
                    if (mainViewModel == null || mainViewModel.baseRig == null || !mainViewModel.baseRig.isConnected()) {
                        Toast.makeText(getContext(), "Rig not connected", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    isScanning = true;
                    btnStartStop.setText("Stop");
                    scanCycles = parseScanCycles();
                    startRealScan();
                    saveScanState();
                } else {
                    isScanning = false;
                    btnStartStop.setText("Start");
                    stopScan();
                    saveScanState();
                }
            }
        });

        // === CLEAR BUTTON: Reset counters ONLY, do NOT remove rows ===
        btnClearTable.setOnClickListener(v -> {
            resetRowCounters();
            totalStations = 0;
            newStations = 0;
            // [MOD] Reset dynamic per-frequency counters
            freqTotals.clear();
            freqNew.clear();
            // [/MOD]
            updateTotals();
            scannedCallsigns.clear();
            scannedDxcc.clear();
            scannedCq.clear();
            scannedItu.clear();
            saveScanState();
            Toast.makeText(getContext(), "Counters reset", Toast.LENGTH_SHORT).show();
        });

        // Header checkboxes
        cbHeaderHide.setOnClickListener(v -> {
            showAllFrequencies(cbHeaderHide.isChecked());
            updatePendingScanList();
            saveScanState();
        });
        cbHeaderSelect.setOnClickListener(v -> {
            selectAllFrequencies(cbHeaderSelect.isChecked());
            updatePendingScanList();
            saveScanState();
        });

        // Cycles input
        etScanCycles.setOnEditorActionListener((v, actionId, event) -> {
            scanCycles = parseScanCycles();
            saveScanState();
            return true;
        });

        // UTC time display
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), aLong -> {
            if (tvUtcTime != null) tvUtcTime.setText(UtcTimer.getTimeStr(aLong));
        });

        // UTC delay display
        utcDelayHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tvUtcDelay != null && getActivity() != null) {
                    tvUtcDelay.setText(String.format(Locale.US, "%+d", UtcTimer.delay));
                    utcDelayHandler.postDelayed(this, 500);
                }
            }
        }, 500);

        updateRigStatus();
        // [MOD] Use raw long value for UI, no formatFreq conversion
        // [OLD]
        // tvRfFreq.setText(formatFreq(GeneralVariables.band));
        // [/OLD]
        // [NEW]
        tvRfFreq.setText(String.valueOf(GeneralVariables.band));
        // [/MOD]

        // [FIX] Populate table FIRST, then restore state
        populateFrequencyTable();
        restoreScanState(); // Restore checkboxes AND counters AFTER table is populated

        updateTotals();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Ensure listener is paused when Scan is visible
        pauseGlobalListenerIfNeeded();
    }

    @Override
    public void onPause() {
        super.onPause();
        saveScanState();
        // Optionally resume listener if moving to Decode
        // resumeGlobalListenerIfNeeded();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        saveScanState();

        // Stop scanning if active
        if (isScanning) {
            isScanning = false;
            stopScan();
        }

        // Resume global listener for other fragments (like Decode)
        resumeGlobalListenerIfNeeded();

        synchronized (freqSwitchLock) {
            if (scanMessageObserver != null && mainViewModel != null) {
                try {
                    mainViewModel.mutableFt8MessageList.removeObserver(scanMessageObserver);
                } catch (Exception ignored) {}
                scanMessageObserver = null;
            }
            if (scanHandler != null) scanHandler.removeCallbacksAndMessages(null);
        }
    }

    // === [AUDIO] Manage global FT8SignalListener ===

    private void pauseGlobalListenerIfNeeded() {
        if (mainViewModel != null && mainViewModel.ft8SignalListener != null) {
            // Stop the listener to prevent audio buffer conflicts and native crashes
            if (mainViewModel.ft8SignalListener.isListening()) {
                mainViewModel.ft8SignalListener.stopListen();
                wasListenerPaused = true;
                Log.d(TAG, "Global listener paused for Scan mode");
            }
        }
    }

    private void resumeGlobalListenerIfNeeded() {
        if (wasListenerPaused && mainViewModel != null && mainViewModel.ft8SignalListener != null) {
            // Resume the listener for Decode mode
            mainViewModel.ft8SignalListener.startListen();
            wasListenerPaused = false;
            Log.d(TAG, "Global listener resumed after Scan");
        }
    }

    // === [STATE] State management ===

    private SharedPreferences getPrefs() {
        if (getContext() == null) return null;
        return getContext().getSharedPreferences(PREFS_SCAN, Context.MODE_PRIVATE);
    }

    private void saveScanState() {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;

        SharedPreferences.Editor editor = prefs.edit();

        // Save selected frequencies (checkboxes)
        List<Long> selected = getSelectedFrequencies();
        StringBuilder sb = new StringBuilder();
        for (Long f : selected) {
            if (sb.length() > 0) sb.append(",");
            sb.append(f);
        }
        editor.putString(KEY_SELECTED_FREQS, sb.toString());
        editor.putInt(KEY_CYCLES, scanCycles);

        // Save row counters: "RFfreq:tot,d,c,itu,new;RFfreq:tot,d,c,itu,new;..."
        StringBuilder countersSb = new StringBuilder();
        if (containerScanContent != null) {
            for (int i = 0; i < containerScanContent.getChildCount(); i++) {
                View row = containerScanContent.getChildAt(i);
                // [MOD] Rename freq -> RFfreq for clarity
                // [OLD]
                // Long freq = (Long) row.getTag();
                // if (freq != null) {
                // [/OLD]
                // [NEW]
                Long RFfreq = (Long) row.getTag();
                if (RFfreq != null) {
                    // [/MOD]
                    TextView tvTot = row.findViewById(R.id.tvRowTot);
                    TextView tvD = row.findViewById(R.id.tvRowD);
                    TextView tvC = row.findViewById(R.id.tvRowC);
                    TextView tvI = row.findViewById(R.id.tvRowI);
                    TextView tvNew = row.findViewById(R.id.tvRowNew);

                    int tot = parseInt(tvTot != null ? tvTot.getText().toString() : "0");
                    int d = parseInt(tvD != null ? tvD.getText().toString() : "0");
                    int c = parseInt(tvC != null ? tvC.getText().toString() : "0");
                    int itu = parseInt(tvI != null ? tvI.getText().toString() : "0");
                    int newC = parseInt(tvNew != null ? tvNew.getText().toString() : "0");

                    if (countersSb.length() > 0) countersSb.append(";");
                    // [MOD] Use RFfreq variable
                    // [OLD]
                    // countersSb.append(freq).append(":")
                    // [/OLD]
                    // [NEW]
                    countersSb.append(RFfreq).append(":")
                            // [/MOD]
                            .append(tot).append(",").append(d).append(",")
                            .append(c).append(",").append(itu).append(",").append(newC);
                }
            }
        }
        editor.putString(KEY_COUNTERS, countersSb.toString());

        editor.apply();
    }

    private void restoreScanState() {
        if (containerScanContent == null || containerScanContent.getChildCount() == 0) {
            Log.d(TAG, "restoreScanState: table not ready, skipping");
            return;
        }

        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;

        // Restore selected frequencies (checkboxes)
        String savedFreqs = prefs.getString(KEY_SELECTED_FREQS, "");
        if (!savedFreqs.isEmpty()) {
            String[] parts = savedFreqs.split(",");
            Set<Long> toSelect = new HashSet<>();
            for (String part : parts) {
                try {
                    toSelect.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException e) { /* ignore */ }
            }
            // Apply to UI
            for (int i = 0; i < containerScanContent.getChildCount(); i++) {
                View row = containerScanContent.getChildAt(i);
                Long rowFreq = (Long) row.getTag();
                if (rowFreq != null && toSelect.contains(rowFreq)) {
                    CheckBox cb = row.findViewById(R.id.cbRowSelect);
                    if (cb != null) cb.setChecked(true);
                }
            }
            updatePendingScanList();
            initScanLists();
        }

        // Restore cycles
        scanCycles = prefs.getInt(KEY_CYCLES, 4);
        if (etScanCycles != null) {
            etScanCycles.setText(String.valueOf(scanCycles));
        }

        // Restore row counters
        String savedCounters = prefs.getString(KEY_COUNTERS, "");
        if (!savedCounters.isEmpty()) {
            String[] rows = savedCounters.split(";");
            for (String rowStr : rows) {
                String[] parts = rowStr.split(":");
                if (parts.length != 2) continue;
                try {
                    // [MOD] Rename freq -> RFfreq
                    // [OLD]
                    // long freq = Long.parseLong(parts[0]);
                    // [/OLD]
                    // [NEW]
                    long RFfreq = Long.parseLong(parts[0]);
                    // [/MOD]
                    String[] counts = parts[1].split(",");
                    if (counts.length != 5) continue;

                    // Find the row with this frequency and update counters
                    for (int i = 0; i < containerScanContent.getChildCount(); i++) {
                        View row = containerScanContent.getChildAt(i);
                        Long rowFreq = (Long) row.getTag();
                        // [MOD] Compare with RFfreq
                        // [OLD]
                        // if (rowFreq != null && rowFreq == freq) {
                        // [/OLD]
                        // [NEW]
                        if (rowFreq != null && rowFreq == RFfreq) {
                            // [/MOD]
                            TextView tvTot = row.findViewById(R.id.tvRowTot);
                            TextView tvD = row.findViewById(R.id.tvRowD);
                            TextView tvC = row.findViewById(R.id.tvRowC);
                            TextView tvI = row.findViewById(R.id.tvRowI);
                            TextView tvNew = row.findViewById(R.id.tvRowNew);

                            if (tvTot != null) tvTot.setText(counts[0]);
                            if (tvD != null) tvD.setText(counts[1]);
                            if (tvC != null) tvC.setText(counts[2]);
                            if (tvI != null) tvI.setText(counts[3]);
                            if (tvNew != null) tvNew.setText(counts[4]);
                            // [MOD] Also restore to dynamic per-frequency maps
                            freqTotals.put(RFfreq, parseInt(counts[0]));
                            freqNew.put(RFfreq, parseInt(counts[4]));
                            // [/MOD]
                            break;
                        }
                    }
                } catch (NumberFormatException e) { /* ignore */ }
            }
        }
    }

    // [NEW] Reset only numeric counters in rows, keep checkboxes and rows
    private void resetRowCounters() {
        if (containerScanContent == null) return;
        for (int i = 0; i < containerScanContent.getChildCount(); i++) {
            View row = containerScanContent.getChildAt(i);
            TextView tvTot = row.findViewById(R.id.tvRowTot);
            TextView tvD = row.findViewById(R.id.tvRowD);
            TextView tvC = row.findViewById(R.id.tvRowC);
            TextView tvI = row.findViewById(R.id.tvRowI);
            TextView tvNew = row.findViewById(R.id.tvRowNew);

            if (tvTot != null) tvTot.setText("0");
            if (tvD != null) tvD.setText("0");
            if (tvC != null) tvC.setText("0");
            if (tvI != null) tvI.setText("0");
            if (tvNew != null) tvNew.setText("0");
        }
        // [MOD] Reset dynamic per-frequency maps
        freqTotals.clear();
        freqNew.clear();
        // [/MOD]
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    private void updateRigStatus() {
        if (tvConnStatus == null || tvRigType == null) return;
        if (mainViewModel != null && mainViewModel.baseRig != null) {
            try {
                boolean connected = mainViewModel.baseRig.isConnected();
                if (connected) {
                    tvConnStatus.setText("Connected");
                    tvConnStatus.setTextColor(getResources().getColor(R.color.is_qsl_text_color, null));
                    tvRigType.setText(mainViewModel.baseRig.getClass().getSimpleName());
                } else {
                    tvConnStatus.setText("Disconnected");
                    tvConnStatus.setTextColor(getResources().getColor(R.color.text_view_error_color, null));
                    tvRigType.setText("No Rig");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking rig status: " + e.getMessage());
                tvConnStatus.setText("Error");
            }
        } else {
            tvConnStatus.setText("Disconnected");
            tvConnStatus.setTextColor(getResources().getColor(R.color.text_view_error_color, null));
            tvRigType.setText("No Rig");
        }
    }

    private int parseScanCycles() {
        try { return Math.max(1, Math.min(10, Integer.parseInt(etScanCycles.getText().toString().trim()))); }
        catch (Exception e) { return 4; }
    }

    private void populateFrequencyTable() {
        if (containerScanContent == null) return;
        containerScanContent.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int i = 0; i < 20; i++) {
            try {
                // [MOD] Rename freq -> RFfreq
                // [OLD]
                // long freq = OperationBand.getBandFreq(i);
                // if (freq <= 0) continue;
                // View rowView = inflater.inflate(R.layout.item_scan_row, containerScanContent, false);
                // bindRowData(rowView, formatFreq(freq), freq, true, true, 0, 0, 0, 0, 0);
                // [/OLD]
                // [NEW]
                long RFfreq = OperationBand.getBandFreq(i);
                if (RFfreq <= 0) continue;
                View rowView = inflater.inflate(R.layout.item_scan_row, containerScanContent, false);
                // Use raw long value for label, no formatFreq conversion
                bindRowData(rowView, String.valueOf(RFfreq), RFfreq, true, true, 0, 0, 0, 0, 0);
                // [/MOD]
                containerScanContent.addView(rowView);
            } catch (Exception e) { break; }
        }
        initScanLists();
    }

    private void initScanLists() {
        List<Long> initial = getSelectedFrequencies();
        synchronized (scanListLock) {
            pendingScanFrequencies.clear();
            pendingScanFrequencies.addAll(initial);
            activeScanFrequencies.clear();
            activeScanFrequencies.addAll(initial);
        }
    }

    private void updatePendingScanList() {
        List<Long> newList = getSelectedFrequencies();
        synchronized (scanListLock) {
            pendingScanFrequencies.clear();
            pendingScanFrequencies.addAll(newList);
        }
    }

    private void applyPendingScanList() {
        synchronized (scanListLock) {
            activeScanFrequencies.clear();
            activeScanFrequencies.addAll(pendingScanFrequencies);
        }
    }

    private void bindRowData(View rowView, String label, long RFfreq, boolean visible, boolean selected,
                             int tot, int d, int c, int itu, int newCount) {
        rowView.setVisibility(visible ? View.VISIBLE : View.GONE);
        // [MOD] Tag with RFfreq
        // [OLD]
        // rowView.setTag(freq);
        // [/OLD]
        // [NEW]
        rowView.setTag(RFfreq);
        // [/MOD]

        CheckBox cbHide = rowView.findViewById(R.id.cbRowHide);
        cbHide.setChecked(visible);
        cbHide.setOnClickListener(v -> {
            boolean isChecked = cbHide.isChecked();
            rowView.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (!isChecked) {
                CheckBox cbSelect = rowView.findViewById(R.id.cbRowSelect);
                cbSelect.setChecked(false);
            }
            updatePendingScanList();
            saveScanState();
        });

        CheckBox cbSelect = rowView.findViewById(R.id.cbRowSelect);
        cbSelect.setChecked(selected);
        cbSelect.setOnClickListener(v -> {
            updatePendingScanList();
            saveScanState();
        });

        TextView tvFreq = rowView.findViewById(R.id.tvRowFreq);
        tvFreq.setText(label);

        ((TextView) rowView.findViewById(R.id.tvRowTot)).setText(String.valueOf(tot));
        ((TextView) rowView.findViewById(R.id.tvRowD)).setText(String.valueOf(d));
        ((TextView) rowView.findViewById(R.id.tvRowC)).setText(String.valueOf(c));
        ((TextView) rowView.findViewById(R.id.tvRowI)).setText(String.valueOf(itu));
        ((TextView) rowView.findViewById(R.id.tvRowNew)).setText(String.valueOf(newCount));

        // === [MOD] Rename Go -> Switch button (safeguard) ===
        // [OLD]
        // Button btnGo = rowView.findViewById(R.id.btnRowGo);
        // btnGo.setOnClickListener(v -> {
        //     safeSwitchToFrequencyAndNavigate(freq);
        // });
        // [/OLD]
        // [NEW]
        Button btnGo = rowView.findViewById(R.id.btnRowGo);
        // Safeguard: rename if text is still "Go" (in case XML not updated)
        // [MOD] Log with RFfreq
        // [OLD]
        // if ("Go".equals(btnGo.getText().toString())) {
        //     btnGo.setText("Switch");
        //     Log.d(TAG, "Renamed row button Go->Switch for freq=" + freq);
        // }
        // btnGo.setOnClickListener(v -> {
        //     safeSwitchToFrequencyAndNavigate(freq);
        // });
        // [/OLD]
        // [NEW]
        if ("Go".equals(btnGo.getText().toString())) {
            btnGo.setText("Switch");
            Log.d(TAG, "Renamed row button Go->Switch for RFfreq=" + RFfreq);
        }
        btnGo.setOnClickListener(v -> {
            safeSwitchToFrequencyAndNavigate(RFfreq);
        });
        // [/MOD]
    }

    // [MOD] formatFreq method is no longer used for logic, only for potential UI fallback
    // Keeping it commented to avoid accidental use
    // [OLD]
    // private String formatFreq(long freqHz) { return String.format("%.3f MHz", freqHz / 1_000_000f); }
    // [/OLD]
    // [NEW]
    // private String formatFreq(long freqHz) { return String.format("%.3f MHz", freqHz / 1_000_000f); }
    // [/MOD]

    private void switchToFrequency(long RFfreq) {
        synchronized (freqSwitchLock) {
            if (mainViewModel != null && mainViewModel.baseRig != null && mainViewModel.baseRig.isConnected()) {
                try {
                    // === [MOD] LogCat: Final check before sending command to Rig ===
                    // [OLD]
                    // GeneralVariables.band = freq;
                    // Log.d(TAG, "switchToFrequency: FINAL COMMAND TO RIG -> freq=" + freq);
                    // [/OLD]
                    // [NEW]
                    Log.d(TAG, "switchToFrequency: FINAL COMMAND TO RIG -> RFfreq=" + RFfreq);
                    // [/MOD]

                    GeneralVariables.band = RFfreq;
                    GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(RFfreq);
                    GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
                    mainViewModel.setOperationBand();
                    // [MOD] Use raw long for UI
                    // [OLD]
                    // if (tvRfFreq != null) tvRfFreq.setText(formatFreq(freq));
                    // Toast.makeText(getContext(), "Switched to " + formatFreq(freq), Toast.LENGTH_SHORT).show();
                    // [/OLD]
                    // [NEW]
                    if (tvRfFreq != null) tvRfFreq.setText(String.valueOf(RFfreq));
                    updateRigStatus();
                    Toast.makeText(getContext(), "Switched to " + RFfreq, Toast.LENGTH_SHORT).show();
                    // [/MOD]
                } catch (Exception e) {
                    Log.e(TAG, "Error switching frequency: " + e.getMessage());
                }
            } else {
                Log.e(TAG, "Rig not connected, cannot switch frequency");
            }
        }
    }

    private void safeSwitchToFrequencyAndNavigate(long RFfreq) {
        // === [MOD] LogCat: вывод частоты при запросе переключения ===
        // [OLD]
        // if (isScanning) {
        // Log.d(TAG, ">>> ENTRY safeSwitchToFrequencyAndNavigate: freq=" + freq);
        // [/OLD]
        // [NEW]
        Log.d(TAG, ">>> ENTRY safeSwitchToFrequencyAndNavigate: RFfreq=" + RFfreq);
        // [/MOD]

        if (isScanning) {
            isScanning = false;
            if (btnStartStop != null) btnStartStop.setText("Start");
            stopScan();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // [MOD] Pass RFfreq
                // [OLD]
                // switchToFrequency(freq);
                // [/OLD]
                // [NEW]
                switchToFrequency(RFfreq);
                // [/MOD]
                if (navController != null) navController.navigate(R.id.menu_nav_mycalling);
            }, 100);
        } else {
            // [MOD] Pass RFfreq
            // [OLD]
            // switchToFrequency(freq);
            // [/OLD]
            // [NEW]
            switchToFrequency(RFfreq);
            // [/MOD]
            if (navController != null) navController.navigate(R.id.menu_nav_mycalling);
        }
    }

    private void startRealScan() {
        if (containerScanContent == null) return;
        if (mainViewModel == null || mainViewModel.ft8SignalListener == null) {
            Log.e(TAG, "Cannot start scan: listener not ready");
            return;
        }

        // Ensure listener is stopped to prevent native crashes
        pauseGlobalListenerIfNeeded();

        currentFreqIndex = 0;
        isTuneSent = false;
        scannedCallsigns.clear();
        scannedDxcc.clear();
        scannedCq.clear();
        scannedItu.clear();
        totalStations = 0;
        newStations = 0;
        // [MOD] Reset dynamic per-frequency counters
        freqTotals.clear();
        freqNew.clear();
        // [/MOD]

        List<Long> initialFreqs;
        synchronized (scanListLock) {
            initialFreqs = new ArrayList<>(activeScanFrequencies);
        }
        if (initialFreqs.isEmpty()) {
            Toast.makeText(getContext(), "No frequencies selected", Toast.LENGTH_SHORT).show();
            isScanning = false;
            if (btnStartStop != null) btnStartStop.setText("Start");
            return;
        }

        // === [MOD] Log the content of the initial frequency table ===
        // [OLD]
        // if (initialFreqs.isEmpty()) { ... return; }
        // scanMessageObserver = messages -> { ... };
        // scanNextFrequency(initialFreqs);
        // [/OLD]
        // [NEW]
        Log.d(TAG, "startRealScan: === STARTING SCAN ===");
        Log.d(TAG, "startRealScan: Total frequencies in list: " + initialFreqs.size());
        for (int i = 0; i < initialFreqs.size(); i++) {
            Long f = initialFreqs.get(i);
            Log.d(TAG, "startRealScan: initialFreqs[" + i + "] = " + f);
        }
        Log.d(TAG, "startRealScan: ===========================");
        // [/MOD]

        scanMessageObserver = messages -> {
            if (!isScanning || messages == null) return;
            try {
                for (Ft8Message msg : messages) {
                    String from = msg.getCallsignFrom();
                    if (from != null && !from.isEmpty()) {
                        processDecodedMessage(msg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing scan messages: " + e.getMessage());
            }
        };

        scanNextFrequency(initialFreqs);
    }

    private ArrayList<Long> getSelectedFrequencies() {
        ArrayList<Long> freqs = new ArrayList<>();
        if (containerScanContent == null) return freqs;
        for (int i = 0; i < containerScanContent.getChildCount(); i++) {
            View row = containerScanContent.getChildAt(i);
            CheckBox cb = row.findViewById(R.id.cbRowSelect);
            if (cb.isChecked() && row.getVisibility() == View.VISIBLE) {
                Long f = (Long) row.getTag();
                if (f != null) freqs.add(f);
            }
        }
        return freqs;
    }

    private void scanNextFrequency(List<Long> currentList) {
        synchronized (freqSwitchLock) {
            if (!isScanning || mainViewModel == null) {
                stopScan();
                Toast.makeText(getContext(), "Scan complete", Toast.LENGTH_SHORT).show();
                return;
            }

            if (currentFreqIndex >= currentList.size()) {
                stopScan();
                Toast.makeText(getContext(), "Scan complete", Toast.LENGTH_SHORT).show();
                return;
            }

            // [MOD] Rename freq -> RFfreq
            // [OLD]
            // long freq = currentList.get(currentFreqIndex);
            // Log.d(TAG, "scanNextFrequency: Selected RF freq=" + freq);
            // [/OLD]
            // [NEW]
            long RFfreq = currentList.get(currentFreqIndex);
            Log.d(TAG, "scanNextFrequency: Selected RF freq=" + RFfreq);
            // [/MOD]

            // === FIX: Ensure rig is connected before sending commands ===
            if (mainViewModel.baseRig == null || !mainViewModel.baseRig.isConnected()) {
                Log.e(TAG, "Rig disconnected during scan");
                Toast.makeText(getContext(), "Rig disconnected", Toast.LENGTH_SHORT).show();
                stopScan();
                return;
            }

            try {
                // [MOD] Use RFfreq
                // [OLD]
                // GeneralVariables.band = freq;
                // GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
                // Log.d(TAG, "scanNextFrequency: Calculated bandListIndex=" + GeneralVariables.bandListIndex);
                // mainViewModel.setOperationBand();
                // if (tvRfFreq != null) tvRfFreq.setText(formatFreq(freq));
                // [/OLD]
                // [NEW]
                GeneralVariables.band = RFfreq;
                GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(RFfreq);
                Log.d(TAG, "scanNextFrequency: Calculated bandListIndex=" + GeneralVariables.bandListIndex);
                mainViewModel.setOperationBand();
                if (tvRfFreq != null) tvRfFreq.setText(String.valueOf(RFfreq));
                // [/MOD]
            } catch (Exception e) {
                Log.e(TAG, "Error setting band: " + e.getMessage());
                stopScan();
                return;
            }

            // Send TUNE once per frequency
            isTuneSent = false;
            try {
                if (GeneralVariables.sendTuneOnFreqChange && mainViewModel.baseRig != null &&
                        mainViewModel.baseRig.isConnected() && !isTuneSent) {
                    mainViewModel.baseRig.setTune(IcomRigConstant.TUNER_START);
                    isTuneSent = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending tune: " + e.getMessage());
            }

            if (scanMessageObserver != null) {
                mainViewModel.mutableFt8MessageList.observe(getViewLifecycleOwner(), scanMessageObserver);
            }

            // === [MOD] Add delay after setOperationBand for radio tuning ===
            // [OLD]
            // listenForMessages(freq, scanCycles, currentList);
            // Log.d(TAG, "scanNextFrequency: Waiting 1000ms for radio to tune to " + freq);
            // Log.d(TAG, "scanNextFrequency: Starting listen cycle for " + freq);
            // [/OLD]
            // [NEW]
            Log.d(TAG, "scanNextFrequency: Waiting 1000ms for radio to tune to " + RFfreq);
            scanHandler.postDelayed(() -> {
                if (isScanning) {
                    Log.d(TAG, "scanNextFrequency: Starting listen cycle for " + RFfreq);
                    // [MOD] Pass RFfreq to listenForMessages
                    // [OLD]
                    // listenForMessages(freq, scanCycles, currentList);
                    // [/OLD]
                    // [NEW]
                    listenForMessages(RFfreq, scanCycles, currentList);
                    // [/MOD]
                }
            }, 1000);
            // [/MOD]
        }
    }

    // [MOD] Listen for messages using UtcTimer events, no polling, no delay math
    // [OLD]
    // private void listenForMessages(long RFfreq, int cycles, List<Long> currentList) {
    //     if (scanHandler == null) return;
    //     final int[] done = {0};
    //     final long[] last = {UtcTimer.getNowSequential()};
    //     Runnable r = new Runnable() {
    //         @Override
    //         public void run() {
    //             synchronized (freqSwitchLock) {
    //                 if (!isScanning) {
    //                     if (scanMessageObserver != null && mainViewModel != null)
    //                         mainViewModel.mutableFt8MessageList.removeObserver(scanMessageObserver);
    //                     return;
    //                 }
    //             }
    //             long seq = UtcTimer.getNowSequential();
    //             if (seq != last[0]) {
    //                 last[0] = seq;
    //                 done[0]++;
    //                 applyPendingScanList();
    //                 if (done[0] >= cycles) {
    //                     if (scanMessageObserver != null && mainViewModel != null)
    //                         mainViewModel.mutableFt8MessageList.removeObserver(scanMessageObserver);
    //                     currentFreqIndex++;
    //                     scanHandler.postDelayed(() -> scanNextFrequency(currentList), 2000);
    //                     return;
    //                 }
    //             }
    //             scanHandler.postDelayed(this, 1000);
    //         }
    //     };
    //     scanHandler.post(r);
    // }
    // [/OLD]

    // [NEW]
    private void listenForMessages(long RFfreq, int cycles, List<Long> currentList) {
        if (scanHandler == null || !isScanning) return;

        final int[] slotsProcessed = {0};
        final int startSequential = UtcTimer.getNowSequential();

        // Use class field to satisfy Java initialization rules
        scanTimerObserver = utcMillis -> {
            if (!isScanning) {
                mainViewModel.timerSec.removeObserver(scanTimerObserver);
                return;
            }

            // Use UtcTimer.sequential() to detect slot boundary
            int currentSequential = UtcTimer.sequential(utcMillis);
            int expectedSequential = (startSequential + slotsProcessed[0]) % 2;

            // Skip until slot boundary is crossed
            if (currentSequential != expectedSequential) return;

            slotsProcessed[0]++;
            Log.d(TAG, "listenForMessages: Slot " + slotsProcessed[0] + "/" + cycles +
                    " completed on " + RFfreq);

            if (slotsProcessed[0] >= cycles) {
                // Finished listening on this frequency
                mainViewModel.timerSec.removeObserver(scanTimerObserver);

                if (scanMessageObserver != null) {
                    mainViewModel.mutableFt8MessageList.removeObserver(scanMessageObserver);
                }

                // === SWITCH TO NEXT FREQUENCY IMMEDIATELY ===
                // No delay calculation needed for receive frequency switching
                Log.d(TAG, "listenForMessages: Switching to next frequency now");

                currentFreqIndex++;
                scanNextFrequency(currentList);
                // Direct call, no postDelayed, no math
            }
            // If cycles not finished -> just wait for next slot event
            // Message decoding runs in parallel via scanMessageObserver
        };

        mainViewModel.timerSec.observe(getViewLifecycleOwner(), scanTimerObserver);
        Log.d(TAG, "listenForMessages: Started on " + RFfreq + " for " + cycles + " slots");
    }
    // [/MOD]

    private void processDecodedMessage(Ft8Message msg) {
        String callsign = msg.getCallsignFrom();
        if (callsign == null || callsign.isEmpty()) return;

        totalStations++;

        // [MOD] Use dynamic per-frequency counters via Map (no hardcoded index bounds)
        long currentRfFreq = GeneralVariables.band;
        freqTotals.put(currentRfFreq, freqTotals.getOrDefault(currentRfFreq, 0) + 1);
        // [/MOD]

        if (!scannedCallsigns.contains(callsign)) {
            scannedCallsigns.add(callsign);
            newStations++;
            // [MOD] Also increment per-frequency new counter via Map
            freqNew.put(currentRfFreq, freqNew.getOrDefault(currentRfFreq, 0) + 1);
            // [/MOD]

            try {
                if (mainViewModel != null && GeneralVariables.callsignDatabase != null) {
                    GeneralVariables.callsignDatabase.getCallsignInformation(callsign, new OnAfterQueryCallsignLocation() {
                        @Override
                        public void doOnAfterQueryCallsignLocation(CallsignInfo info) {
                            if (info == null) return;
                            if (info.DXCC != null && !info.DXCC.isEmpty() && !scannedDxcc.contains(info.DXCC)) {
                                scannedDxcc.add(info.DXCC);
                                GeneralVariables.addDxcc(info.DXCC);
                            }
                            if (info.CQZone > 0 && !scannedCq.contains(info.CQZone)) {
                                scannedCq.add(info.CQZone);
                                GeneralVariables.addCqZone(info.CQZone);
                            }
                            if (info.ITUZone > 0 && !scannedItu.contains(info.ITUZone)) {
                                scannedItu.add(info.ITUZone);
                                GeneralVariables.addItuZone(info.ITUZone);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying callsign info: " + e.getMessage());
            }
        }

        // [MOD] Update row using RFfreq for accurate per-frequency stats (no index dependency)
        updateRowForFrequency(currentRfFreq);
        // [/MOD]
        updateTotals();
    }

    // [MOD] Updated to use RFfreq key for direct row lookup via Map
    // [OLD]
    // private void updateRowForFrequency(int total, int newCount) {
    //     if (containerScanContent == null) return;
    //     long currentRfFreq = GeneralVariables.band;
    //     for (int i = 0; i < containerScanContent.getChildCount(); i++) {
    //         View row = containerScanContent.getChildAt(i);
    //         Long rowRfFreq = (Long) row.getTag();
    //         if (rowRfFreq != null && rowRfFreq == currentRfFreq) {
    //             TextView tvTot = row.findViewById(R.id.tvRowTot);
    //             TextView tvNew = row.findViewById(R.id.tvRowNew);
    //             if (tvTot != null) tvTot.setText(String.valueOf(total));
    //             if (tvNew != null) tvNew.setText(String.valueOf(newCount));
    //             break;
    //         }
    //     }
    // }
    // [/OLD]
    // [NEW]
    private void updateRowForFrequency(long RFfreq) {
        if (containerScanContent == null) return;
        for (int i = 0; i < containerScanContent.getChildCount(); i++) {
            View row = containerScanContent.getChildAt(i);
            Long rowRfFreq = (Long) row.getTag();
            if (rowRfFreq != null && rowRfFreq == RFfreq) {
                TextView tvTot = row.findViewById(R.id.tvRowTot);
                TextView tvNew = row.findViewById(R.id.tvRowNew);
                if (tvTot != null) tvTot.setText(String.valueOf(freqTotals.getOrDefault(RFfreq, 0)));
                if (tvNew != null) tvNew.setText(String.valueOf(freqNew.getOrDefault(RFfreq, 0)));
                break;
            }
        }
    }
    // [/MOD]

    private void stopScan() {
        synchronized (freqSwitchLock) {
            if (!isScanning) return;
            isScanning = false;
            if (scanMessageObserver != null && mainViewModel != null) {
                try {
                    mainViewModel.mutableFt8MessageList.removeObserver(scanMessageObserver);
                } catch (Exception ignored) {}
                scanMessageObserver = null;
            }
            if (scanHandler != null) scanHandler.removeCallbacksAndMessages(null);
            if (btnStartStop != null) btnStartStop.setText("Start");

            // Optionally resume listener if we want to go back to Decode
            // resumeGlobalListenerIfNeeded();
        }
    }

    private void showAllFrequencies(boolean show) {
        if (containerScanContent == null) return;
        for (int j = 0; j < containerScanContent.getChildCount(); j++) {
            View row = containerScanContent.getChildAt(j);
            CheckBox cbHide = row.findViewById(R.id.cbRowHide);
            CheckBox cbSelect = row.findViewById(R.id.cbRowSelect);
            cbHide.setChecked(show);
            row.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) cbSelect.setChecked(false);
        }
    }

    private void selectAllFrequencies(boolean select) {
        if (containerScanContent == null) return;
        for (int j = 0; j < containerScanContent.getChildCount(); j++) {
            CheckBox cb = containerScanContent.getChildAt(j).findViewById(R.id.cbRowSelect);
            cb.setChecked(select);
        }
    }

    private void updateTotals() {
        // [MOD] Sum dynamic per-frequency counters from Maps for accurate totals
        int sumTot = 0;
        int sumNew = 0;
        for (int count : freqTotals.values()) {
            sumTot += count;
        }
        for (int count : freqNew.values()) {
            sumNew += count;
        }
        if (tvTotalAll != null) tvTotalAll.setText(String.valueOf(sumTot));
        if (tvTotalNew != null) tvTotalNew.setText(String.valueOf(sumNew));
        // [/MOD]
    }
}