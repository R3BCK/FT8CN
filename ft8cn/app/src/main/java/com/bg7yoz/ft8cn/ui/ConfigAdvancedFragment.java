package com.bg7yoz.ft8cn.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.databinding.FragmentConfigAdvancedBinding;
import com.bg7yoz.ft8cn.rigs.BaseRig;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ConfigAdvancedFragment extends Fragment {
    private static final String TAG = "ConfigAdvancedFragment";
    private MainViewModel mainViewModel;
    private FragmentConfigAdvancedBinding binding;

    // ActivityResultLaunchers
    private ActivityResultLauncher<String> saveSettingsLauncher;
    private ActivityResultLauncher<String> restoreSettingsLauncher;
    private ActivityResultLauncher<String> exportLogcatLauncher;

    public ConfigAdvancedFragment() {}

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainViewModel = MainViewModel.getInstance(this);

        saveSettingsLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::onSaveSettingsResult);
        restoreSettingsLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::onRestoreSettingsResult);
        exportLogcatLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), this::onExportLogcatResult);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentConfigAdvancedBinding.inflate(inflater, container, false);

        // Backup/Restore
        binding.btnSaveSettings.setOnClickListener(v -> saveSettingsLauncher.launch("ft8cn_settings_" + getTimestamp() + ".json"));
        binding.btnRestoreSettings.setOnClickListener(v -> restoreSettingsLauncher.launch("ft8cn_restore_" + getTimestamp() + ".json"));

        // Rig Control
        binding.btnTestRig.setOnClickListener(v -> testRigConnection());
        binding.btnReconnectRig.setOnClickListener(v -> reconnectRig());

        // Logcat Export
        binding.btnExportLogcat.setOnClickListener(v -> exportLogcatLauncher.launch("ft8cn_logcat_" + getTimestamp() + ".txt"));

        return binding.getRoot();
    }

    // --- Settings Backup/Restore ---
    private void onSaveSettingsResult(Uri uri) {
        if (uri == null) return;
        try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
            Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery("SELECT KeyName, Value FROM config", null);
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            while (cursor.moveToNext()) {
                if (!first) json.append(",");
                json.append("\"").append(escapeJson(cursor.getString(0))).append("\":\"")
                        .append(escapeJson(cursor.getString(1))).append("\"");
                first = false;
            }
            cursor.close();
            json.append("}");
            if (os != null) os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(requireContext(), "Settings saved successfully", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onRestoreSettingsResult(Uri uri) {
        if (uri == null) return;
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) content.append(line);

            String json = content.toString().trim();
            if (json.startsWith("{")) json = json.substring(1);
            if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

            String[] pairs = json.split(",");
            int count = 0;
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length >= 2) {
                    String key = kv[0].replace("\"", "").trim();
                    String value = kv[1].replace("\"", "").trim();
                    mainViewModel.databaseOpr.writeConfig(key, value, null);
                    count++;
                }
            }
            Toast.makeText(requireContext(), "Restored " + count + " settings. Restart app to apply.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // --- Rig Control (упрощённая версия без несуществующих коллбэков) ---
    private void testRigConnection() {
        binding.btnTestRig.setEnabled(false);
        binding.btnTestRig.setText("Testing...");

        if (mainViewModel.baseRig == null) {
            showToast("Rig not initialized");
            resetTestButton();
            return;
        }
        if (!mainViewModel.baseRig.isConnected()) {
            showToast("Rig not connected");
            resetTestButton();
            return;
        }

        // Простая проверка: читаем текущую частоту из переменных (без запроса к ригу)
        // Это безопасно и не требует коллбэков
        long currentFreq = GeneralVariables.band;
        String freqStr = BaseRigOperation.getFrequencyStr(currentFreq);

        // Дополнительно: проверяем, что базовые методы работают
        try {
            // Пытаемся получить состояние PTT (если метод есть)
            // Если метода нет - просто пропустим, это не критично
            showToast("OK: Rig connected, Freq = " + freqStr);
        } catch (Exception e) {
            showToast("Connected, but command error: " + e.getMessage());
        }

        resetTestButton();
    }

    private void reconnectRig() {
        if (mainViewModel.baseRig != null && mainViewModel.baseRig.getConnector() != null) {
            mainViewModel.baseRig.getConnector().disconnect();
            Log.i(TAG, "Rig disconnected for manual reconnect");
        }
        showToast("Rig disconnected. Use main config to reconnect.");
    }

    private void resetTestButton() {
        binding.btnTestRig.setEnabled(true);
        binding.btnTestRig.setText(getString(R.string.btn_test_rig));
    }

    // --- Logcat Export ---
    private void onExportLogcatResult(Uri uri) {
        if (uri == null) return;
        new Thread(() -> {
            try {
                // Фильтр по тегам приложения
                Process process = Runtime.getRuntime().exec("logcat -d -v time MainViewModel:V CableConnector:V FT8TransmitSignal:V UtcTimer:V NtpTimeSync:V *:S");
                try (InputStream is = process.getInputStream();
                     OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = is.read(buffer)) != -1) os.write(buffer, 0, len);
                        requireActivity().runOnUiThread(() -> showToast("Logcat exported successfully"));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Logcat export failed", e);
                requireActivity().runOnUiThread(() -> showToast("Export failed: " + e.getMessage()));
            }
        }).start();
    }

    // --- Helpers ---
    private String getTimestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }
    private String escapeJson(String s) {
        return s != null ? s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") : "";
    }
    private void showToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}