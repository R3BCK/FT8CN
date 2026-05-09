package com.bg7yoz.ft8cn;
/**
 * MainViewModel class for FT8 signal decoding and related data.
 * Lives for the entire APP lifecycle.
 * @author BG7YOZ
 * @date 2022.8.22
 */

import static com.bg7yoz.ft8cn.GeneralVariables.getStringFromResource;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.RectF;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.rigs.IcomRigConstant;
import com.bg7yoz.ft8cn.rigs.OnConnectReceiveData;
import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.callsign.CallsignInfo;
import com.bg7yoz.ft8cn.callsign.OnAfterQueryCallsignLocation;
import com.bg7yoz.ft8cn.connector.BluetoothRigConnector;
import com.bg7yoz.ft8cn.connector.CableConnector;
import com.bg7yoz.ft8cn.connector.CableSerialPort;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.connector.FlexConnector;
import com.bg7yoz.ft8cn.connector.IComWifiConnector;
import com.bg7yoz.ft8cn.connector.X6100Connector;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.database.OnAfterQueryFollowCallsigns;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.flex.FlexRadio;
import com.bg7yoz.ft8cn.flex.RadioTcpClient;
import com.bg7yoz.ft8cn.ft8listener.FT8SignalListener;
import com.bg7yoz.ft8cn.ft8listener.OnFt8Listen;
import com.bg7yoz.ft8cn.ft8transmit.FT8TransmitSignal;
import com.bg7yoz.ft8cn.ft8transmit.OnDoTransmitted;
import com.bg7yoz.ft8cn.ft8transmit.OnTransmitSuccess;
import com.bg7yoz.ft8cn.html.LogHttpServer;
import android.database.Cursor;
import com.bg7yoz.ft8cn.icom.WifiRig;
import com.bg7yoz.ft8cn.log.QSLCallsignRecord;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.log.SWLQsoList;
import com.bg7yoz.ft8cn.log.ThirdPartyService;
import com.bg7yoz.ft8cn.rigs.BaseRig;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.rigs.ElecraftRig;
import com.bg7yoz.ft8cn.rigs.Flex6000Rig;
import com.bg7yoz.ft8cn.rigs.FlexNetworkRig;
import com.bg7yoz.ft8cn.rigs.GuoHeQ900Rig;
import com.bg7yoz.ft8cn.rigs.IcomRig;
import com.bg7yoz.ft8cn.rigs.InstructionSet;
import com.bg7yoz.ft8cn.rigs.KenwoodKT90Rig;
import com.bg7yoz.ft8cn.rigs.KenwoodTS2000Rig;
import com.bg7yoz.ft8cn.rigs.KenwoodTS570Rig;
import com.bg7yoz.ft8cn.rigs.KenwoodTS590Rig;
import com.bg7yoz.ft8cn.rigs.OnRigStateChanged;
import com.bg7yoz.ft8cn.rigs.TrUSDXRig;
import com.bg7yoz.ft8cn.rigs.Wolf_sdr_450Rig;
import com.bg7yoz.ft8cn.rigs.XieGu6100NetRig;
import com.bg7yoz.ft8cn.rigs.XieGu6100Rig;
import com.bg7yoz.ft8cn.rigs.XieGuRig;
import com.bg7yoz.ft8cn.rigs.Yaesu2Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu2_847Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu38Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu38_450Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu39Rig;
import com.bg7yoz.ft8cn.rigs.YaesuDX10Rig;
import com.bg7yoz.ft8cn.spectrum.SpectrumListener;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.ToastMessage;
import com.bg7yoz.ft8cn.wave.HamRecorder;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.x6100.X6100Radio;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainViewModel extends ViewModel {
    private static final String TAG = "ft8cn MainViewModel";
    public boolean configIsLoaded = false;

    private static MainViewModel viewModel = null;

    // NTP sync time tracking (event-driven, after transmit)
    private long lastNtpSyncTime = 0;
    private static final long NTP_SYNC_INTERVAL_MS = 5 * 60 * 1000L;

    // Battery low threshold for transmit blocking
    private static final int BATTERY_LOW_THRESHOLD_PERCENT = 1;

    // USB auto-connect receiver
    private BroadcastReceiver usbReceiver;

    public final ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
    public UtcTimer utcTimer;

    public DatabaseOpr databaseOpr;

    public MutableLiveData<Integer> mutable_Decoded_Counter = new MutableLiveData<>();
    public int currentDecodeCount = 0;
    public MutableLiveData<ArrayList<Ft8Message>> mutableFt8MessageList = new MutableLiveData<>();
    public MutableLiveData<Long> timerSec = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableIsRecording = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableHamRecordIsRunning = new MutableLiveData<>();
    public MutableLiveData<Float> mutableTimerOffset = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableIsDecoding = new MutableLiveData<>();
    public ArrayList<Ft8Message> currentMessages = null;

    public MutableLiveData<Boolean> mutableIsFlexRadio = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableIsXieguRadio = new MutableLiveData<>();

    // Rig connection status
    public MutableLiveData<String> rigStatusText = new MutableLiveData<>("Disconnected");

    // === Persistent spectrum state (survives fragment switches) ===
    public final List<RectF> persistentOccupiedZones = new ArrayList<>();
    public int persistentOccupiedZonesAge = 0;
    public static final int ZONE_PERSIST_CYCLES = 8;
    // ============================================================

    private final ExecutorService getQTHThreadPool = Executors.newCachedThreadPool();
    private final ExecutorService sendWaveDataThreadPool = Executors.newCachedThreadPool();
    private final GetQTHRunnable getQTHRunnable = new GetQTHRunnable(this);
    private final SendWaveDataRunnable sendWaveDataRunnable = new SendWaveDataRunnable();

    // Shared log generation variables
    public MutableLiveData<String> mutableShareInfo = new MutableLiveData<>("");
    public MutableLiveData<Integer> mutableSharePosition = new MutableLiveData<>(0);
    public MutableLiveData<Boolean> mutableShareRunning = new MutableLiveData<>(false);
    public MutableLiveData<Integer> mutableShareCount = new MutableLiveData<>(0);
    public MutableLiveData<Boolean> mutableImportShareRunning = new MutableLiveData<>(false);

    public HamRecorder hamRecorder;
    public FT8SignalListener ft8SignalListener;
    public FT8TransmitSignal ft8TransmitSignal;
    public SpectrumListener spectrumListener;
    public boolean markMessage = true;

    public OperationBand operationBand = null;

    private SWLQsoList swlQsoList = new SWLQsoList();

    public MutableLiveData<ArrayList<CableSerialPort.SerialPort>> mutableSerialPorts = new MutableLiveData<>();
    private ArrayList<CableSerialPort.SerialPort> serialPorts;
    public BaseRig baseRig;

    // === Transmission Watchdog ===
    private Handler transmissionWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable transmissionWatchdogRunnable;
    private static final long WATCHDOG_CHECK_INTERVAL_MS = 10000;
    // ===============================

    private final OnRigStateChanged onRigStateChanged = new OnRigStateChanged() {
        @Override
        public void onDisconnected() {
            ToastMessage.show(getStringFromResource(R.string.disconnect_rig));
            updateRigStatus();
        }

        @Override
        public void onConnected() {
            ToastMessage.show(getStringFromResource(R.string.connected_rig));
            updateRigStatus();
        }

        @Override
        public void onPttChanged(boolean isOn) {}

        @Override
        public void onFreqChanged(long freq) {
            ToastMessage.show(String.format(getStringFromResource(R.string.current_frequency)
                    , BaseRigOperation.getFrequencyAllInfo(freq)));
            GeneralVariables.band = freq;
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
            GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
            databaseOpr.getAllQSLCallsigns();

            // === TUNE on RADIO frequency change (with FT8-safe timing) ===
            if (GeneralVariables.sendTuneOnFreqChange && baseRig != null && baseRig.isConnected()) {
                scheduleTuneCommand();
            }
            // ===============================================================
        }

        @Override
        public void onRunError(String message) {
            ToastMessage.show(String.format(getStringFromResource(R.string.radio_communication_error), message));
        }
    };

    public MutableLiveData<Integer> mutableTransmitMessagesCount = new MutableLiveData<>();

    public boolean deNoise = false;

    public boolean logListShowCallsign = false;
    public String queryKey = "";
    public int queryFilter = 0;
    public MutableLiveData<Integer> mutableQueryFilter = new MutableLiveData<>();
    public ArrayList<QSLCallsignRecord> callsignRecords = new ArrayList<>();

    public final LogHttpServer httpServer;

    public static MainViewModel getInstance(ViewModelStoreOwner owner) {
        if (viewModel == null) {
            viewModel = new ViewModelProvider(owner).get(MainViewModel.class);
        }
        return viewModel;
    }

    public Ft8Message getFt8Message(int position) {
        return Objects.requireNonNull(ft8Messages.get(position));
    }

    private boolean isBatteryTooLow(Context context) {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    float batteryPct = (level / (float) scale) * 100f;
                    return batteryPct < BATTERY_LOW_THRESHOLD_PERCENT;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Battery check failed: " + e.getMessage());
        }
        return false;
    }

    public MainViewModel() {
        databaseOpr = DatabaseOpr.getInstance(GeneralVariables.getMainContext(), "data.db");
        mutableIsDecoding.postValue(false);

        // === Initialize HamRecorder FIRST ===
        hamRecorder = new HamRecorder(null);
        hamRecorder.startRecord();

        mutableIsFlexRadio.setValue(false);
        mutableIsXieguRadio.setValue(false);

        utcTimer = new UtcTimer(10, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {}
            @Override
            public void doOnSecTimer(long utc) {
                timerSec.postValue(utc);
                mutableIsRecording.postValue(hamRecorder.isRunning());
                mutableHamRecordIsRunning.postValue(hamRecorder.isRunning());
            }
        });
        utcTimer.start();

        UtcTimer.syncTime(null);
        lastNtpSyncTime = System.currentTimeMillis();

        mutableFt8MessageList.setValue(ft8Messages);

        // [CHANGED] Инициализируем библиотеку ПЕРЕД созданием FT8SignalListener
        // Загружаем ft8cn_dx или ft8cn_std в зависимости от настройки acceptDxCalls
        try {
            String libName = GeneralVariables.acceptDxCalls ? "ft8cn_dx" : "ft8cn_std";
            System.loadLibrary(libName);
            Log.d(TAG, "Loaded native library: " + libName);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load library: " + e.getMessage());
            // Fallback: пробуем загрузить стандартную библиотеку
            try {
                System.loadLibrary("ft8cn_std");
                Log.d(TAG, "Fallback: loaded ft8cn_std");
            } catch (UnsatisfiedLinkError e2) {
                Log.e(TAG, "Fallback failed: " + e2.getMessage());
                // Не выбрасываем исключение, чтобы приложение не упало сразу
            }
        }

        ft8SignalListener = new FT8SignalListener(databaseOpr, new OnFt8Listen() {
            @Override
            public void beforeListen(long utc) {
                mutableIsDecoding.postValue(true);
            }

            @Override
            public void afterDecode(long utc, float time_sec, int sequential,
                                    ArrayList<Ft8Message> messages, boolean isDeep) {
                if (messages.size() == 0) return;

                synchronized (ft8Messages) {
                    ft8Messages.addAll(messages);
                }
                GeneralVariables.deleteArrayListMore(ft8Messages);
                mutableFt8MessageList.postValue(ft8Messages);
                mutableTimerOffset.postValue(time_sec);

                findIncludedCallsigns(messages);

                if (!ft8TransmitSignal.isTransmitting()
                        && !isDeep
                        && (ft8SignalListener.timeSec + GeneralVariables.pttDelay
                        + GeneralVariables.transmitDelay <= 2000)) {
                    ft8TransmitSignal.parseMessageToFunction(messages);
                }

                currentMessages = messages;
                currentDecodeCount = isDeep ? currentDecodeCount + messages.size() : messages.size();
                mutableIsDecoding.postValue(false);

                getQTHRunnable.messages = messages;
                getQTHThreadPool.execute(getQTHRunnable);

                mutable_Decoded_Counter.postValue(currentDecodeCount);

                if (GeneralVariables.saveSWLMessage) {
                    databaseOpr.writeMessage(messages);
                }
                if (GeneralVariables.saveSWL_QSO) {
                    swlQsoList.findSwlQso(messages, ft8Messages, new SWLQsoList.OnFoundSwlQso() {
                        @Override
                        public void doFound(QSLRecord record) {
                            databaseOpr.addSWL_QSO(record);
                            ToastMessage.show(record.swlQSOInfo());
                        }
                    });
                }
                getCallsignAndGrid(messages);
            }
        });

        ft8SignalListener.setOnWaveDataListener(new FT8SignalListener.OnWaveDataListener() {
            @Override
            public void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone) {
                hamRecorder.getVoiceData(duration, afterDoneRemove, getVoiceDataDone);
            }
        });

        ft8SignalListener.startListen();

        // === Create SpectrumListener AFTER hamRecorder is running ===
        spectrumListener = new SpectrumListener(hamRecorder);

        ft8TransmitSignal = new FT8TransmitSignal(databaseOpr, new OnDoTransmitted() {
            private boolean needControlSco() {
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) return false;
                if (GeneralVariables.controlMode != ControlMode.CAT) return true;
                return baseRig != null && !baseRig.supportWaveOverCAT();
            }

            @Override
            public void onBeforeTransmit(Ft8Message message, int functionOder) {
                Log.d(TAG, "=== onBeforeTransmit DEBUG ===");
                Log.d(TAG, "controlMode=" + GeneralVariables.controlMode);
                Log.d(TAG, "needControlSco=" + needControlSco());
                Log.d(TAG, "supportTransmitOverCAT=" + supportTransmitOverCAT());

                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        Log.d(TAG, "Calling baseRig.setPTT(true)");
                        if (needControlSco()) stopSco();
                        baseRig.setPTT(true);
                    } else {
                        Log.e(TAG, "baseRig is NULL, cannot set PTT");
                    }
                } else {
                    Log.w(TAG, "PTT blocked: controlMode=" + GeneralVariables.controlMode);
                }
                if (isBatteryTooLow(GeneralVariables.getMainContext())) {
                    ToastMessage.show("Transmit blocked: Low battery < " + BATTERY_LOW_THRESHOLD_PERCENT + "%");
                    return;
                }
                if (GeneralVariables.connectMode == ConnectMode.USB_CABLE) {
                    if (baseRig != null && !baseRig.isConnected()) {
                        ToastMessage.show("Transmit blocked: USB device disconnected");
                        return;
                    }
                }
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        if (needControlSco()) stopSco();
                        baseRig.setPTT(true);
                    }
                }
                if (ft8TransmitSignal.isActivated()) {
                    GeneralVariables.transmitMessages.add(message);
                    mutableTransmitMessagesCount.postValue(1);
                }
            }

            @Override
            public void onAfterTransmit(Ft8Message message, int functionOder) {
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        baseRig.setPTT(false);
                        if (needControlSco()) startSco();
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastNtpSyncTime >= NTP_SYNC_INTERVAL_MS) {
                    UtcTimer.syncTime(null);
                    lastNtpSyncTime = now;
                }
            }

            @Override
            public void onTransmitByWifi(Ft8Message msg) {
                if (GeneralVariables.connectMode == ConnectMode.NETWORK && baseRig != null && baseRig.isConnected()) {
                    sendWaveDataRunnable.baseRig = baseRig;
                    sendWaveDataRunnable.message = msg;
                    sendWaveDataThreadPool.execute(sendWaveDataRunnable);
                }
            }

            @Override
            public boolean supportTransmitOverCAT() {
                if (GeneralVariables.controlMode != ControlMode.CAT) return false;
                if (baseRig == null) return false;
                return baseRig.isConnected() && baseRig.supportWaveOverCAT();
            }

            @Override
            public void onTransmitOverCAT(Ft8Message msg) {
                if (supportTransmitOverCAT()) {
                    sendWaveDataRunnable.baseRig = baseRig;
                    sendWaveDataRunnable.message = msg;
                    sendWaveDataThreadPool.execute(sendWaveDataRunnable);
                }
            }

        }, new OnTransmitSuccess() {
            @Override
            public void doAfterTransmit(QSLRecord qslRecord) {
                databaseOpr.addQSL_Callsign(qslRecord);
                new Thread(() -> {
                    if (GeneralVariables.enableCloudlog) ThirdPartyService.UploadToCloudLog(qslRecord);
                    if (GeneralVariables.enableQRZ) ThirdPartyService.UploadToQRZ(qslRecord);
                    if (GeneralVariables.enableHrdlog) ThirdPartyService.UploadToHrdlog(qslRecord);
                }).start();

                if (qslRecord.getToCallsign() != null) {
                    GeneralVariables.callsignDatabase.getCallsignInformation(qslRecord.getToCallsign()
                            , new OnAfterQueryCallsignLocation() {
                                @Override
                                public void doOnAfterQueryCallsignLocation(CallsignInfo callsignInfo) {
                                    GeneralVariables.addDxcc(callsignInfo.DXCC);
                                    GeneralVariables.addItuZone(callsignInfo.ITUZone);
                                    GeneralVariables.addCqZone(callsignInfo.CQZone);
                                }
                            });
                }
            }
        });

        int savedPort = LogHttpServer.DEFAULT_PORT;
        Cursor cursor = databaseOpr.getDb().rawQuery("SELECT Value FROM config WHERE KeyName='webPort'", null);
        if (cursor != null && cursor.moveToFirst()) {
            try {
                int port = Integer.parseInt(cursor.getString(0));
                if (port >= GeneralVariables.MIN_WEB_PORT && port <= GeneralVariables.MAX_WEB_PORT) {
                    savedPort = port;
                }
            } catch (Exception ignored) {}
            cursor.close();
        }
        GeneralVariables.webPort = savedPort;
        httpServer = new LogHttpServer(this, savedPort);
        try {
            httpServer.start();
            Log.i(TAG, "HTTP server started on port " + savedPort);
        } catch (IOException e) {
            Log.e(TAG, "http server error: " + e.getMessage());
        }

        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        GeneralVariables.getMainContext().registerReceiver(usbReceiver, usbFilter);

        updateRigStatus();

        // === Start transmission watchdog ===
        startTransmissionWatchdog();
        // ===================================
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // Stop watchdog to prevent memory leaks
        if (transmissionWatchdogHandler != null && transmissionWatchdogRunnable != null) {
            transmissionWatchdogHandler.removeCallbacks(transmissionWatchdogRunnable);
            Log.d(TAG, "Transmission watchdog stopped");
        }
        // Ensure recording is stopped and resources released
        if (hamRecorder != null) {
            hamRecorder.stopRecord();
            Log.d(TAG, "HamRecorder stopped in onCleared()");
        }
    }

    /**
     * Start aggressive watchdog to monitor and recover transmission state.
     * Checks every 10 seconds if transmit is activated but recording stopped.
     */
    private void startTransmissionWatchdog() {
        transmissionWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // Check 1: Transmit activated but recording not running
                    if (ft8TransmitSignal.isActivated() && !hamRecorder.isRunning()) {
                        Log.w(TAG, "WATCHDOG: Transmit active but hamRecorder.isRunning()=false! Recovering...");

                        // Try to restart HamRecorder
                        try {
                            hamRecorder.startRecord();
                            mutableIsRecording.postValue(true);
                            Log.d(TAG, "HamRecorder restarted by watchdog");
                            ToastMessage.show("Recording recovered");
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to restart HamRecorder: " + e.getMessage(), e);
                        }
                    }

                    // Check 2: Audio focus lost? (optional enhancement)
                    if (GeneralVariables.connectMode != ConnectMode.NETWORK && !hamRecorder.isRunning()) {
                        Log.d(TAG, "Watchdog: Mic mode but not recording - may need audio focus recovery");
                    }

                    // Check 3: Rig connected but PTT stuck? (optional)
                    if (baseRig != null && baseRig.isConnected() && baseRig.isPttOn()) {
                        // PTT has been on too long? Could indicate stuck state
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Watchdog error: " + e.getMessage(), e);
                }

                // Schedule next check - aggressive: every 10 seconds
                transmissionWatchdogHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS);
            }
        };
        transmissionWatchdogHandler.post(transmissionWatchdogRunnable);
        Log.d(TAG, "Aggressive transmission watchdog started (10s interval)");
    }

    public void setTransmitIsFreeText(boolean isFreeText) {
        if (ft8TransmitSignal != null) ft8TransmitSignal.setTransmitFreeText(isFreeText);
    }

    public boolean getTransitIsFreeText() {
        return ft8TransmitSignal != null && ft8TransmitSignal.isTransmitFreeText();
    }

    /**
     * Find messages that match my callsign or followed callsigns.
     * Add matching messages to transmit queue.
     */
    private synchronized void findIncludedCallsigns(ArrayList<Ft8Message> messages) {
        if (ft8TransmitSignal.isActivated() && ft8TransmitSignal.sequential != UtcTimer.getNowSequential()) return;
        int count = 0;
        for (Ft8Message msg : messages) {
            if (GeneralVariables.checkIsMyCallsign(msg.getCallsignFrom())
                    || GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())
                    || GeneralVariables.callsignInFollow(msg.getCallsignFrom())
                    || (GeneralVariables.callsignInFollow(msg.getCallsignTo()) && msg.getCallsignTo() != null)
                    || (GeneralVariables.autoFollowCQ && msg.checkIsCQ())) {
                msg.isQSL_Callsign = GeneralVariables.checkQSLCallsign(msg.getCallsignFrom());
                if (!GeneralVariables.checkIsExcludeCallsign(msg.callsignFrom)) {
                    count++;
                    GeneralVariables.transmitMessages.add(msg);
                }
            }
        }
        GeneralVariables.deleteArrayListMore(GeneralVariables.transmitMessages);
        mutableTransmitMessagesCount.postValue(count);
    }

    /**
     * Clear the transmit message queue.
     */
    public void clearTransmittingMessage() {
        GeneralVariables.transmitMessages.clear();
        mutableTransmitMessagesCount.postValue(0);
    }

    /**
     * Extract callsign and grid information from decoded messages.
     * Store in database and GeneralVariables cache.
     */
    private void getCallsignAndGrid(ArrayList<Ft8Message> messages) {
        for (Ft8Message msg : messages) {
            if (GeneralVariables.checkFun1(msg.extraInfo)) {
                if (!GeneralVariables.getCallsignHasGrid(msg.getCallsignFrom(), msg.maidenGrid)) {
                    databaseOpr.addCallsignQTH(msg.getCallsignFrom(), msg.maidenGrid);
                }
                GeneralVariables.addCallsignAndGrid(msg.getCallsignFrom(), msg.maidenGrid);
            }
        }
    }

    /**
     * Clear the FT8 message list (Calling history).
     * Used when changing frequency to avoid calling old callsigns.
     */
    public void clearFt8MessageList() {
        ft8Messages.clear();
        mutable_Decoded_Counter.postValue(ft8Messages.size());
        mutableFt8MessageList.postValue(ft8Messages);
    }

    /**
     * Delete a file by path.
     */
    public static void deleteFile(String fileName) {
        File file = new File(fileName);
        if (file.exists() && file.isFile()) file.delete();
    }

    /**
     * Add a callsign to the followed list.
     */
    public void addFollowCallsign(String callsign) {
        if (!GeneralVariables.followCallsign.contains(callsign)) {
            GeneralVariables.followCallsign.add(callsign);
            databaseOpr.addFollowCallsign(callsign);
        }
    }

    /**
     * Load followed callsigns from database.
     */
    public void getFollowCallsignsFromDataBase() {
        databaseOpr.getFollowCallsigns(new OnAfterQueryFollowCallsigns() {
            @Override
            public void doOnAfterQueryFollowCallsigns(ArrayList<String> callsigns) {
                for (String s : callsigns) {
                    if (!GeneralVariables.followCallsign.contains(s)) GeneralVariables.followCallsign.add(s);
                }
            }
        });
    }

    /**
     * Set the operation band on the connected rig.
     */
    public void setOperationBand() {
        Log.d(TAG, "=== setOperationBand DEBUG ===");
        Log.d(TAG, "controlMode=" + GeneralVariables.controlMode);
        Log.d(TAG, "connectMode=" + GeneralVariables.connectMode);
        Log.d(TAG, "baseRig=" + baseRig);
        Log.d(TAG, "isConnected=" + (baseRig != null && baseRig.isConnected()));
        Log.d(TAG, "supportWaveOverCAT=" + (baseRig != null ? baseRig.supportWaveOverCAT() : "N/A"));

        if (!isRigConnected()) {
            Log.e(TAG, "ABORT: rig not connected");
            return;
        }
        if (GeneralVariables.controlMode != ControlMode.CAT) {
            Log.w(TAG, "WARNING: controlMode is not CAT, freq commands may be ignored");
        }
        if (!isRigConnected()) return;
        baseRig.setUsbModeToRig();
        new Handler().postDelayed(() -> {
            baseRig.setFreq(GeneralVariables.band);
            baseRig.setFreqToRig();
        }, 800);
    }

    /**
     * Set the CI-V address for ICOM radios.
     */
    public void setCivAddress() {
        if (baseRig != null) baseRig.setCivAddress(GeneralVariables.civAddress);
    }

    /**
     * Set the control mode (VOX, CAT, RTS, DTR).
     */
    public void setControlMode() {
        if (baseRig != null) baseRig.setControlMode(GeneralVariables.controlMode);
    }

    /**
     * Connect to a rig via USB cable.
     */
    public void connectCableRig(Context context, CableSerialPort.SerialPort port) {
        if (ft8TransmitSignal != null && ft8TransmitSignal.isTransmitting()) {
            Log.i(TAG, "Interrupting transmit before connecting USB device");
            ft8TransmitSignal.setTransmitting(false);
            ToastMessage.show("Transmit stopped: connecting device");
        }
        if (GeneralVariables.controlMode == ControlMode.VOX) GeneralVariables.controlMode = ControlMode.CAT;
        connectRig();
        if (baseRig == null) return;
        baseRig.setControlMode(GeneralVariables.controlMode);

        CableConnector connector = new CableConnector(context, port, GeneralVariables.baudRate, GeneralVariables.controlMode, baseRig);

        connector.setOnConnectReceiveData(new OnConnectReceiveData() {
            @Override
            public void onData(byte[] data) {
                if (baseRig != null) {
                    baseRig.onReceiveData(data);
                }
            }
        });

        connector.setOnCableDataReceived(new CableConnector.OnCableDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                Log.i(TAG, "call hamRecorder.doOnWaveDataReceived");
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);
        connector.connect();
        new Handler().postDelayed(this::setOperationBand, 1000);
    }

    /**
     * Connect to a rig via Bluetooth.
     */
    public void connectBluetoothRig(Context context, BluetoothDevice device) {
        GeneralVariables.controlMode = ControlMode.CAT;
        connectRig();
        if (baseRig == null) return;
        baseRig.setControlMode(GeneralVariables.controlMode);
        BluetoothRigConnector connector = BluetoothRigConnector.getInstance(context, device.getAddress(), GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);
        new Handler().postDelayed(this::setOperationBand, 5000);
    }

    /**
     * Connect to a rig via WiFi (ICOM).
     */
    public void connectWifiRig(WifiRig wifiRig) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK && baseRig != null && baseRig.getConnector() != null) {
            baseRig.getConnector().disconnect();
        }
        GeneralVariables.controlMode = ControlMode.CAT;
        IComWifiConnector iComWifiConnector = new IComWifiConnector(GeneralVariables.controlMode, wifiRig);
        iComWifiConnector.setOnWifiDataReceived(new IComWifiConnector.OnWifiDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
            @Override
            public void OnCivReceived(byte[] data) {}
        });
        iComWifiConnector.connect();
        connectRig();
        baseRig.setControlMode(GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(iComWifiConnector);
        new Handler().postDelayed(this::setOperationBand, 1000);
    }

    /**
     * Connect to a FlexRadio rig.
     */
    public void connectFlexRadioRig(Context context, FlexRadio flexRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK && baseRig != null && baseRig.getConnector() != null) {
            baseRig.getConnector().disconnect();
        }
        GeneralVariables.controlMode = ControlMode.CAT;
        FlexConnector flexConnector = new FlexConnector(context, flexRadio, GeneralVariables.controlMode);
        flexConnector.setOnWaveDataReceived(new FlexConnector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        flexConnector.connect();
        connectRig();
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(flexConnector);
        new Handler().postDelayed(this::setOperationBand, 3000);
    }

    /**
     * Connect to a Xiegu X6100 rig.
     */
    public void connectXieguRadioRig(Context context, X6100Radio xieguRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK && baseRig != null && baseRig.getConnector() != null) {
            baseRig.getConnector().disconnect();
        }
        GeneralVariables.controlMode = ControlMode.CAT;
        X6100Connector xieguConnector = new X6100Connector(context, xieguRadio, GeneralVariables.controlMode);
        xieguConnector.setOnWaveDataReceived(new X6100Connector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        xieguConnector.connect();
        connectRig();
        xieguConnector.setBaseRig(baseRig);
        xieguRadio.setOnReceiveDataListener(new X6100Radio.OnReceiveDataListener() {
            @Override
            public void onDataReceive(byte[] data) {
                baseRig.onReceiveData(data);
            }
        });
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(xieguConnector);
        new Handler().postDelayed(this::setOperationBand, 3000);
    }

    /**
     * Initialize the rig instance based on instruction set.
     */
    private void connectRig() {
        baseRig = null;
        switch (GeneralVariables.instructionSet) {
            case InstructionSet.ICOM: baseRig = new IcomRig(GeneralVariables.civAddress, true); break;
            case InstructionSet.ICOM_756: baseRig = new IcomRig(GeneralVariables.civAddress, false); break;
            case InstructionSet.YAESU_2: baseRig = new Yaesu2Rig(); break;
            case InstructionSet.YAESU_847: baseRig = new Yaesu2_847Rig(); break;
            case InstructionSet.YAESU_3_9: baseRig = new Yaesu39Rig(false); break;
            case InstructionSet.YAESU_3_9_U_DIG: baseRig = new Yaesu39Rig(true); break;
            case InstructionSet.YAESU_3_8: baseRig = new Yaesu38Rig(); break;
            case InstructionSet.YAESU_3_450: baseRig = new Yaesu38_450Rig(); break;
            case InstructionSet.KENWOOD_TK90: baseRig = new KenwoodKT90Rig(); break;
            case InstructionSet.YAESU_DX10: baseRig = new YaesuDX10Rig(); break;
            case InstructionSet.KENWOOD_TS590: baseRig = new KenwoodTS590Rig(); break;
            case InstructionSet.GUOHE_Q900: baseRig = new GuoHeQ900Rig(); break;
            case InstructionSet.XIEGUG90S: baseRig = new XieGuRig(GeneralVariables.civAddress); break;
            case InstructionSet.ELECRAFT: baseRig = new ElecraftRig(); break;
            case InstructionSet.FLEX_CABLE: baseRig = new Flex6000Rig(); break;
            case InstructionSet.FLEX_NETWORK: baseRig = new FlexNetworkRig(); break;
            case InstructionSet.XIEGU_6100_FT8CNS:
                baseRig = GeneralVariables.connectMode == ConnectMode.NETWORK
                        ? new XieGu6100NetRig(GeneralVariables.civAddress)
                        : new XieGu6100Rig(GeneralVariables.civAddress);
                break;
            case InstructionSet.XIEGU_6100: baseRig = new XieGu6100Rig(GeneralVariables.civAddress); break;
            case InstructionSet.KENWOOD_TS2000: baseRig = new KenwoodTS2000Rig(); break;
            case InstructionSet.WOLF_SDR_DIGU: baseRig = new Wolf_sdr_450Rig(false); break;
            case InstructionSet.WOLF_SDR_USB: baseRig = new Wolf_sdr_450Rig(true); break;
            case InstructionSet.TRUSDX: baseRig = new TrUSDXRig(); break;
            case InstructionSet.KENWOOD_TS570: baseRig = new KenwoodTS570Rig(); break;
        }
        if ((GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK)
                || ((GeneralVariables.instructionSet == InstructionSet.ICOM
                || GeneralVariables.instructionSet == InstructionSet.XIEGU_6100
                || GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS)
                && GeneralVariables.connectMode == ConnectMode.NETWORK)) {
            hamRecorder.setDataFromLan();
        } else {
            if (GeneralVariables.controlMode != ControlMode.CAT || baseRig == null || !baseRig.supportWaveOverCAT()) {
                hamRecorder.setDataFromMic();
            } else {
                hamRecorder.setDataFromLan();
            }
        }
        mutableIsFlexRadio.postValue(GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK);
        mutableIsXieguRadio.postValue(GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS);
    }

    /**
     * Check if a rig is currently connected.
     */
    public boolean isRigConnected() {
        return baseRig != null && baseRig.isConnected();
    }

    /**
     * Update the rig connection status text for UI.
     */
    public void updateRigStatus() {
        if (GeneralVariables.controlMode == ControlMode.VOX) {
            rigStatusText.postValue("VOX Mode (Audio PTT)");
            return;
        }
        if (baseRig == null || !baseRig.isConnected()) {
            rigStatusText.postValue("Disconnected");
            return;
        }
        switch (GeneralVariables.connectMode) {
            case ConnectMode.USB_CABLE: rigStatusText.postValue("Connected: USB Cable"); break;
            case ConnectMode.BLUE_TOOTH: rigStatusText.postValue("Connected: Bluetooth"); break;
            case ConnectMode.NETWORK: rigStatusText.postValue("Connected: Network"); break;
            default: rigStatusText.postValue("Connected: CAT");
        }
    }

    /**
     * Get list of available USB serial ports.
     */
    public void getUsbDevice() {
        serialPorts = CableSerialPort.listSerialPorts(GeneralVariables.getMainContext());
        mutableSerialPorts.postValue(serialPorts);
    }

    /**
     * Start Bluetooth SCO for audio routing.
     */
    public void startSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
            return;
        }
        audioManager.setBluetoothScoOn(true);
        audioManager.startBluetoothSco();
        audioManager.setSpeakerphoneOn(false);
    }

    /**
     * Stop Bluetooth SCO.
     */
    public void stopSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);
        }
    }

    /**
     * Enable Bluetooth headset mode.
     */
    public void setBlueToothOn() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
            return;
        }
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setBluetoothScoOn(true);
        audioManager.stopBluetoothSco();
        audioManager.startBluetoothSco();
        audioManager.setSpeakerphoneOn(false);
        ToastMessage.show(getStringFromResource(R.string.bluetooth_headset_mode));
    }

    /**
     * Disable Bluetooth headset mode.
     */
    public void setBlueToothOff() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);
        }
        ToastMessage.show(getStringFromResource(R.string.bluetooth_Headset_mode_cancelled));
    }

    /**
     * Check if Bluetooth headset is connected.
     */
    @SuppressLint("MissingPermission")
    public boolean isBTConnected() {
        BluetoothAdapter blueAdapter = BluetoothAdapter.getDefaultAdapter();
        if (blueAdapter == null) return false;
        int headset = blueAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
        int a2dp = blueAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
        return headset == BluetoothAdapter.STATE_CONNECTED || a2dp == BluetoothAdapter.STATE_CONNECTED;
    }

    /**
     * Runnable to get QTH information for decoded messages.
     */
    private static class GetQTHRunnable implements Runnable {
        MainViewModel mainViewModel;
        ArrayList<Ft8Message> messages;
        public GetQTHRunnable(MainViewModel mainViewModel) { this.mainViewModel = mainViewModel; }
        @Override
        public void run() {
            CallsignDatabase.getMessagesLocation(GeneralVariables.callsignDatabase.getDb(), messages);
            mainViewModel.mutableFt8MessageList.postValue(mainViewModel.ft8Messages);
        }
    }

    /**
     * Runnable to send wave data to rig over CAT.
     */
    private static class SendWaveDataRunnable implements Runnable {
        BaseRig baseRig;
        Ft8Message message;
        @Override
        public void run() {
            if (baseRig != null && message != null) baseRig.sendWaveData(message);
        }
    }

    /**
     * Restart the HTTP server on a new port.
     */
    public void restartHttpServer(int newPort) {
        if (httpServer != null) httpServer.restartServer(newPort);
    }

    /**
     * Toggle rig connection based on current mode.
     */
    public void toggleRigConnection(Context context) {
        if (GeneralVariables.controlMode == ControlMode.VOX) {
            ToastMessage.show("VOX mode does not use CAT connection");
            return;
        }

        if (isRigConnected()) {
            if (baseRig != null && baseRig.getConnector() != null) {
                baseRig.getConnector().disconnect();
                baseRig = null;
                updateRigStatus();
                ToastMessage.show("Disconnected");
            }
            return;
        }

        switch (GeneralVariables.connectMode) {
            case ConnectMode.USB_CABLE:
                getUsbDevice();
                break;
            case ConnectMode.BLUE_TOOTH:
            case ConnectMode.NETWORK:
                ToastMessage.show("Open settings to select " +
                        (GeneralVariables.connectMode == ConnectMode.BLUE_TOOTH ? "Bluetooth" : "Network") +
                        " device");
                break;
        }
    }

    /**
     * Send immediate TUNE command to rig.
     */
    private void sendTuneCommand() {
        if (baseRig != null && baseRig.isConnected()) {
            baseRig.setTune(IcomRigConstant.TUNER_START);
            Log.d(TAG, "TUNE START command sent via baseRig.setTune()");
            ToastMessage.show("TUNE START command sent via baseRig.setTune()");
        } else {
            Log.w(TAG, "Cannot send TUNE: rig not connected");
            ToastMessage.show("Cannot send TUNE: rig not connected");
        }
    }

    /**
     * Schedule TUNE command to execute in the safe gap between FT8 transmission slots.
     * FT8 slots: ~0-13s, 15-28s, 30-43s, 45-58s of each minute (transmission windows)
     * Safe gaps: ~13-15s, 28-30s, 43-45s, 58-60s (quiet periods for tuning)
     * This avoids interfering with ongoing QSOs.
     * If clearCallHistOnFreqChange is enabled, also clears the transmit queue.
     */
    private void scheduleTuneCommand() {
        long nowSec = (System.currentTimeMillis() / 1000) % 60;
        int slotStart = ((int) nowSec / 15) * 15;
        int secondsIntoSlot = (int) nowSec - slotStart;

        long delayMs;
        if (secondsIntoSlot < 12) {
            delayMs = (13 - secondsIntoSlot) * 1000L + 300;
        } else {
            delayMs = 400;
        }
        delayMs = Math.max(delayMs, 300);
        delayMs = Math.min(delayMs, 15000);

        Log.d(TAG, "Scheduling TUNE: sec=" + nowSec + ", delay=" + delayMs + "ms");

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (baseRig != null && baseRig.isConnected()) {
                baseRig.setTune(IcomRigConstant.TUNER_START);

                // === Clear Calling history if enabled ===
                if (GeneralVariables.clearCallHistOnFreqChange) {
                    clearTransmittingMessage(); //Critical: НЕ ТРОГАТЬ! НЕ УДАЛЯТЬ, НЕ ИЗМЕНЯТЬ
                    ToastMessage.show("Calling history cleared");
                }
                // =======================================
            }
        }, delayMs);
    }

    // === Persistent occupied zones methods ===

    /**
     * Update persistent occupied zones from current decode results.
     * Call this when decoding finishes.
     */
    /**
     * Update persistent occupied zones from current decode results.
     * Deduplication removed: adds zones for every decoded signal.
     */
    public void updatePersistentOccupiedZones(List<Ft8Message> messages, int viewWidth, int viewHeight) {
        if (messages == null || viewWidth == 0) return;

        // Age existing zones
        persistentOccupiedZonesAge++;
        if (persistentOccupiedZonesAge >= ZONE_PERSIST_CYCLES) {
            persistentOccupiedZones.clear();
            persistentOccupiedZonesAge = 0;
        }

        // Add zones for ALL decoded messages (deduplication removed)
        for (Ft8Message msg : messages) {
            int freq = (int) msg.freq_hz;
            if (freq > 0 && freq < 3000) {
                float left = ((float)(freq - 25) / 3000f) * viewWidth;
                float right = ((float)(freq + 25) / 3000f) * viewWidth;
                left = Math.max(0, left);
                right = Math.min(viewWidth, right);

                // Directly add without overlap check
                persistentOccupiedZones.add(new RectF(left, 0, right, viewHeight * 0.35f));
            }
        }
    }

    /**
     * Helper: check if two rectangles overlap with tolerance
     */
    private boolean rectsOverlap(RectF a, RectF b, float tolerance) {
        return (a.left - tolerance <= b.right && a.right + tolerance >= b.left);
    }

    /**
     * Get copy of persistent zones for UI
     */
    public List<RectF> getPersistentOccupiedZones() {
        return new ArrayList<>(persistentOccupiedZones);
    }

    /**
     * Clear persistent zones (e.g., on band change)
     */
    public void clearPersistentOccupiedZones() {
        persistentOccupiedZones.clear();
        persistentOccupiedZonesAge = 0;
    }
    // =======================================
}