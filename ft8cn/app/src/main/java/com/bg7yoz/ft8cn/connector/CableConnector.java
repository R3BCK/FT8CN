package com.bg7yoz.ft8cn.connector;

import android.content.Context;
import android.util.Log;

import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.rigs.BaseRig;
import com.bg7yoz.ft8cn.serialport.util.SerialInputOutputManager;

/**
 * Connector for wired (USB cable) connections to radios.
 * Extends BaseRigConnector.
 *
 * @author BG7YOZ
 * @date 2023-03-20
 */
public class CableConnector extends BaseRigConnector {
    private static final String TAG = "CableConnector";

    /**
     * Callback interface for receiving waveform data from rig over CAT.
     * Added 2023-08-16 by DS1UFX for (tr)uSDX audio-over-CAT support.
     */
    public interface OnCableDataReceived {
        void OnWaveReceived(int bufferLen, float[] buffer);
    }

    private final CableSerialPort cableSerialPort;
    private final BaseRig cableConnectedRig;
    private OnCableDataReceived onCableDataReceived;

    public CableConnector(Context context,
                          CableSerialPort.SerialPort serialPort,
                          int baudRate,
                          int controlMode,
                          BaseRig cableConnectedRig) {
        super(controlMode);
        this.cableConnectedRig = cableConnectedRig;
        cableSerialPort = new CableSerialPort(context, serialPort, baudRate, getOnConnectorStateChanged());

        cableSerialPort.ioListener = new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                if (getOnConnectReceiveData() != null) {
                    getOnConnectReceiveData().onData(data);
                }
            }

            @Override
            public void onRunError(Exception e) {
                Log.e(TAG, "CableConnector error: " + e.getMessage());
                if (getOnConnectorStateChanged() != null) {
                    getOnConnectorStateChanged().onRunError("Serial connection lost: " + e.getMessage());
                }
            }
        };
    }

    @Override
    public synchronized void sendData(byte[] data) {
        // ✅ ИСПРАВЛЕНО: убрана проверка isOpen(), так как метода нет в CableSerialPort
        // sendData() внутри сам обрабатывает состояние подключения
        if (cableSerialPort != null) {
            cableSerialPort.sendData(data);
        }
    }

    @Override
    public void setPttOn(boolean on) {
        // Handle only RTS and DTR modes for PTT control
        switch (getControlMode()) {
            case ControlMode.DTR:
                if (cableSerialPort != null) {
                    cableSerialPort.setDTR_On(on);
                }
                break;
            case ControlMode.RTS:
                if (cableSerialPort != null) {
                    cableSerialPort.setRTS_On(on);
                }
                break;
            // CAT mode: PTT sent via CAT command in sendData()
        }
    }

    @Override
    public void setPttOn(byte[] command) {
        // Send PTT as CAT command
        sendData(command);
    }

    // === (tr)uSDX audio-over-CAT support (2023-08-16 by DS1UFX) ===

    @Override
    public void sendWaveData(byte[] data) {
        sendData(data);
    }

    @Override
    public void receiveWaveData(float[] data) {
        if (onCableDataReceived != null) {
            onCableDataReceived.OnWaveReceived(data.length, data);
        }
    }

    public void setOnCableDataReceived(OnCableDataReceived onCableDataReceived) {
        this.onCableDataReceived = onCableDataReceived;
    }

    @Override
    public void connect() {
        super.connect();
        if (cableSerialPort != null) {
            cableSerialPort.connect();
        }
    }

    @Override
    public void disconnect() {
        if (cableConnectedRig != null) {
            cableConnectedRig.onDisconnecting();
        }
        super.disconnect();
        if (cableSerialPort != null) {
            cableSerialPort.disconnect();
        }
    }

    /**
     * Configure smart polling for the connected rig.
     * Call this after connection to enable 1-second interval and Transceive support.
     * @param rig The connected BaseRig instance
     */
    public void configureSmartPolling(BaseRig rig) {
        if (rig != null) {
            // Set 1-second polling interval
            rig.setPollIntervalMs(1000);

            // Enable Transceive mode for Icom rigs (if supported)
            if (rig instanceof com.bg7yoz.ft8cn.rigs.IcomRig) {
                rig.setTransceiveEnabled(true);
                rig.enableTransceiveMode();
                Log.d(TAG, "Smart polling: Transceive enabled for Icom rig");
            } else {
                rig.setTransceiveEnabled(false);
                Log.d(TAG, "Smart polling: Transceive not supported for this rig");
            }
        }
    }
}