package com.bg7yoz.ft8cn.ft8listener;
/**
 * Class for listening to audio. Listening cycle is controlled by UtcTimer clock,
 * audio data is read through OnWaveDataListener interface.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.wave.WaveFileReader;
import com.bg7yoz.ft8cn.wave.WaveFileWriter;

import java.util.ArrayList;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";
    private final UtcTimer utcTimer;
    //private HamRecorder hamRecorder;
    private final OnFt8Listen onFt8Listen; // Event triggered when listening starts and after decoding ends
    //private long band;
    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>(); // Decoding duration
    public long timeSec = 0; // Decoding duration

    private OnWaveDataListener onWaveDataListener;


    private DatabaseOpr db;

    private final A91List a91List = new A91List(); // a91 list


    static {
        System.loadLibrary("ft8cn");
    }

    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        //this.hamRecorder = hamRecorder;
        this.onFt8Listen = onFt8Listen;
        this.db = db;

        // Create action trigger, synchronized with UTC time, 15 seconds per cycle.
        // DoOnSecTimer is the event triggered at the start of each cycle.
        // FT8Common.FT8_SLOT_TIME_M is 15 seconds
        utcTimer = new UtcTimer(FT8Common.FT8_SLOT_TIME_M, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) { // Clock info when not triggering
            }

            @Override
            public void doOnSecTimer(long utc) { // Triggered when specified interval is reached
                //Log.d(TAG, String.format("Trigger recording, %d", utc));
                runRecorde(utc);
            }
        });
    }

    public void startListen() {
        utcTimer.start();
    }

    public void stopListen() {
        utcTimer.stop();
    }

    public boolean isListening() {
        return utcTimer.isRunning();
    }

    /**
     * Get the current time offset, including total clock offset and this instance's offset
     *
     * @return int
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * Record audio. Recording runs in background with multi-threading,
     * automatically generates a temporary Wav format file.
     * Two callback functions for start and end of recording.
     * When recording ends, activate the decoder.
     *
     * @param utc Current UTC time for decoding
     */
    private void runRecorde(long utc) {
        //Log.d(TAG, "Starting recording...");

        if (onWaveDataListener != null) {
            onWaveDataListener.getVoiceData(FT8Common.FT8_SLOT_TIME_MILLISECOND, true
                    , new OnGetVoiceDataDone() {
                        @Override
                        public void onGetDone(float[] data) {
                            //Log.d(TAG, String.format("Starting decode...###, data length: %d", data.length));
                            decodeFt8(utc, data);
                        }
                    });
        }
    }

    public void decodeFt8(long utc, float[] voiceData) {

        // Test code below-------------------------
//        String fileName = getCacheFileName("test_01.wav");
//        Log.e(TAG, "onClick: fileName:" + fileName);
//        WaveFileReader reader = new WaveFileReader(fileName);
//        int data[][] = reader.getData();
        //----------------------------------------------------------

        new Thread(new Runnable() {
            @Override
            public void run() {
                long time = System.currentTimeMillis();
                if (onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }

//                float[] tempData = ints2floats(data);


                // Read audio data and perform preprocessing
                // Note: decoding must complete within one cycle, otherwise new decoding will start
                long ft8Decoder = InitDecoder(utc, FT8Common.SAMPLE_RATE
                        , voiceData.length, true);
//                        , tempData.length, true);
                DecoderMonitorPressFloat(voiceData, ft8Decoder); // Read audio data
//                DecoderMonitorPressFloat(tempData, ft8Decoder); // Read audio data


                ArrayList<Ft8Message> allMsg = new ArrayList<>();
//                ArrayList<Ft8Message> msgs = runDecode(utc, voiceData,false);
                ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false);
                addMsgToList(allMsg, msgs);
                timeSec = System.currentTimeMillis() - time;
                decodeTimeSec.postValue(timeSec); // Decoding duration
                if (onFt8Listen != null) {
                    onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, false);
                }


                if (GeneralVariables.deepDecodeMode) { // Enter deep decode mode
                    //float[] newSignal=tempData;
                    msgs = runDecode(ft8Decoder, utc, true);
                    addMsgToList(allMsg, msgs);
                    timeSec = System.currentTimeMillis() - time;
                    decodeTimeSec.postValue(timeSec); // Decoding duration
                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                    }

                    do {
                        if (timeSec > FT8Common.DEEP_DECODE_TIMEOUT) break; // Timeout check: if exceeded certain time (7 seconds), stop subtracting signals
                        // Subtract decoded signal
                        ReBuildSignal.subtractSignal(ft8Decoder, a91List);

                        // Decode again
                        msgs = runDecode(ft8Decoder, utc, true);
                        addMsgToList(allMsg, msgs);
                        timeSec = System.currentTimeMillis() - time;
                        decodeTimeSec.postValue(timeSec); // Decoding duration
                        if (onFt8Listen != null) {
                            onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                        }

                    } while (msgs.size() > 0);

                }
                // Moved to finalize() method
                DeleteDecoder(ft8Decoder);

                //Log.d(TAG, String.format("Decoding time: %d ms", System.currentTimeMillis() - time));

            }
        }).start();
    }


    private ArrayList<Ft8Message> runDecode(long ft8Decoder, long utc, boolean isDeep) {
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        Ft8Message ft8Message = new Ft8Message(FT8Common.FT8_MODE);

        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        a91List.clear();

        setDecodeMode(ft8Decoder, isDeep); // Set iteration count, isDeep==true increases iterations

        int num_candidates = DecoderFt8FindSync(ft8Decoder); // Max 120 candidates
        //long startTime = System.currentTimeMillis();
        for (int idx = 0; idx < num_candidates; ++idx) {
            //todo Should implement timeout calculation
            try { // Add decoding failure protection
                if (DecoderFt8Analysis(idx, ft8Decoder, ft8Message)) {

                    if (ft8Message.isValid) {
                        Ft8Message msg = new Ft8Message(ft8Message); // Using msg here because some hashed callsigns may replace <...>
                        byte[] a91 = DecoderGetA91(ft8Decoder);
                        a91List.add(a91, ft8Message.freq_hz, ft8Message.time_sec);

                        if (checkMessageSame(ft8Messages, msg)) {
                            continue;
                        }

                        msg.isWeakSignal = isDeep; // Whether this is a weak signal
                        ft8Messages.add(msg);

                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "run: " + e.getMessage());
            }

        }


        return ft8Messages;
    }

    /**
     * Calculate average time offset value
     *
     * @param messages Message list
     * @return Offset value
     */
    private float averageOffset(ArrayList<Ft8Message> messages) {
        if (messages.size() == 0) return 0f;
        float dt = 0;
        //int dtAverage = 0;
        for (Ft8Message msg : messages) {
            dt += msg.time_sec;
        }
        return dt / messages.size();
    }

    /**
     * Add messages to the list
     *
     * @param allMsg Message list
     * @param newMsg New messages
     */
    private void addMsgToList(ArrayList<Ft8Message> allMsg, ArrayList<Ft8Message> newMsg) {
        for (int i = newMsg.size() - 1; i >= 0; i--) {
            if (checkMessageSame(allMsg, newMsg.get(i))) {
                newMsg.remove(i);
            } else {
                allMsg.add(newMsg.get(i));
            }
        }
    }

    /**
     * Check if duplicate content exists in message list
     *
     * @param ft8Messages Message list
     * @param ft8Message  Message
     * @return boolean
     */
    private boolean checkMessageSame(ArrayList<Ft8Message> ft8Messages, Ft8Message ft8Message) {
        for (Ft8Message msg : ft8Messages) {
            if (msg.getMessageText().equals(ft8Message.getMessageText())) {
                if (msg.snr < ft8Message.snr) {
                    msg.snr = ft8Message.snr;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void finalize() throws Throwable {
        //DeleteDecoder(ft8Decoder);
        super.finalize();
    }

    public OnWaveDataListener getOnWaveDataListener() {
        return onWaveDataListener;
    }

    public void setOnWaveDataListener(OnWaveDataListener onWaveDataListener) {
        this.onWaveDataListener = onWaveDataListener;
    }


    public String getCacheFileName(String fileName) {
        return GeneralVariables.getMainContext().getCacheDir() + "/" + fileName;
    }

    public float[] ints2floats(int data[][]) {
        float temp[] = new float[data[0].length];
        for (int i = 0; i < data[0].length; i++) {
            temp[i] = data[0][i] / 32768.0f;
        }
        return temp;
    }

    public int[] floats2ints(float data[]) {
        int temp[] = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            temp[i] = (int) (data[i] * 32767.0f);
        }
        return temp;
    }

    /**
     * First step of decoding: initialize decoder and get decoder address.
     *
     * @param utcTime     UTC time
     * @param sampleRat   Sample rate, 12000
     * @param num_samples Length of buffer data
     * @param isFt8       Whether this is FT8 signal
     * @return Returns decoder address
     */
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);

    /**
     * Second step of decoding: read Wav data.
     *
     * @param buffer  Wav data buffer
     * @param decoder Address of decoder data
     */
    public native void DecoderMonitorPress(int[] buffer, long decoder);

    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);


    /**
     * Third step of decoding: synchronize data.
     *
     * @param decoder Decoder address
     * @return Number of synchronized signals
     */
    public native int DecoderFt8FindSync(long decoder);

    /**
     * Fourth step of decoding: analyze messages. (Needs to be in a loop)
     *
     * @param idx        Index of synchronized signal
     * @param decoder    Decoder address
     * @param ft8Message Decoded message
     * @return boolean
     */
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);

    /**
     * Final step of decoding: delete decoder data
     *
     * @param decoder Address of decoder data
     */
    public native void DeleteDecoder(long decoder);

    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);

    public native byte[] DecoderGetA91(long decoder); // Get a91 data for current message

    public native void setDecodeMode(long decoder, boolean isDeep); // Set decoding mode: isDeep=true for multiple iterations, =false for fast iteration
}