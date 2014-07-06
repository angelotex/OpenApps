package com.dydxtech.openapps;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.apache.commons.codec.language.DoubleMetaphone;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Copyright RSenApps 2014
 * handles only speech recognition (listening, hotword detection)
 *
 * @author Ryan
 */
public class GoogleSpeechRecognizer extends com.dydxtech.openapps.SpeechRecognizer implements RecognitionListener {
    // Handler interface

    static final int MSG_RECOGNIZER_START_LISTENING = 1;
    static final int MSG_RECOGNIZER_CANCEL = 2;
    public static float lastVolume = 0;
    static int BEEP_STREAM = AudioManager.STREAM_SYSTEM;
    private static boolean optimizeEnglish = true;
    private static boolean useGetTasks = true;
    private static boolean isMuted = false;
    private static HashSet<String> blackListedApps;
    protected final Messenger mServerMessenger = new Messenger(
            new IncomingHandler(this));
    // Statuses
    protected boolean mIsListening;
    protected volatile boolean mIsCountDownOn;
    // in jelly bean if there is no speech for an extended period of time it
    // will shut off
    // thus we need something to restart speech recognizer after prolonged time
    protected CountDownTimer mNoSpeechCountDown = new CountDownTimer(5000, 5000) {

        @Override
        public void onTick(long millisUntilFinished) {
        }

        @Override
        public void onFinish() {
            mIsCountDownOn = false;
            Message message = Message.obtain(null, MSG_RECOGNIZER_CANCEL);
            try {
                mServerMessenger.send(message);
                message = Message.obtain(null, MSG_RECOGNIZER_START_LISTENING);
                mServerMessenger.send(message);
            } catch (RemoteException e) {

            }

        }
    };
    // Speech Recognition
    protected Intent mSpeechRecognizerIntent;
    protected SpeechRecognizer mSpeechRecognizer;
    // Audio Manager
    protected AudioManager mAudioManager;
    private Context context;
    private boolean listeningPaused = false;
    private DoubleMetaphone doubleMetaphone = new DoubleMetaphone();
    private List<String> hotwords = new ArrayList<String>();
    private int hotwordCount = 0;
    // callback
    private AudioUI uiReference;
    private SpeechRecognizerListener listener;

    // private static int lastVolume = 1;
    public GoogleSpeechRecognizer(Context context, AudioUI uiReference) {
        lastVolume = 0;
        doubleMetaphone.setMaxCodeLen(1000);
        String hot_phrase = PreferenceManager.getDefaultSharedPreferences(context).getString("hot_phrase", context.getResources().getString(R.string.hot_phrase));
        Log.d("hot_phrase", hot_phrase);

        for (String hotword : hot_phrase.split(",")) {
            hotwords.add(hotword.trim());
            hotwordCount++;
        }

        this.context = context;
        this.uiReference = uiReference;
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo("com.google.android.googlequicksearchbox", 0);
            if (pInfo.versionCode >= 300302160) {
                BEEP_STREAM = AudioManager.STREAM_MUSIC;
            }
        } catch (NameNotFoundException e) {
        }
        initialize();
    }

    public GoogleSpeechRecognizer(Context context, SpeechRecognizerListener listener) {
        this.context = context;
        this.listener = listener;
        initialize();
    }

    private void initialize() {

        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(context);

        blackListedApps = (HashSet<String>) prefs.getStringSet("black_listed_apps", new HashSet<String>());
        optimizeEnglish = prefs.getBoolean("optimizeEnglish", true);
        useGetTasks = prefs.getBoolean("use_gettasks", true);
        mAudioManager = (AudioManager) context
                .getSystemService(Context.AUDIO_SERVICE);
        mSpeechRecognizerIntent = new Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        mSpeechRecognizerIntent.putExtra(
                RecognizerIntent.EXTRA_CALLING_PACKAGE, context
                        .getApplicationContext().getPackageName()
        );
        mSpeechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        mSpeechRecognizer.setRecognitionListener(this);
        listenForHotword();
    }

    public void listenForHotword() {

        listeningPaused = false;
        startListening();
    }

    public void stopListening() {

        if (isMuted) {
            isMuted = false;
            try {
                mAudioManager.setStreamMute(BEEP_STREAM, false);
                mAudioManager.setStreamMute(BEEP_STREAM, false);
            } catch (Exception e) {
            }

        }
        // mAudioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, lastVolume,
        // 0);
        listeningPaused = true;
        mSpeechRecognizer.cancel();
        mNoSpeechCountDown.cancel();
    }

    public void startListening() {

        mIsCountDownOn = false;
        listeningPaused = false;
        Message message = Message.obtain(null, MSG_RECOGNIZER_CANCEL);
        try {
            mServerMessenger.send(message);
            message = Message.obtain(null, MSG_RECOGNIZER_START_LISTENING);
            mServerMessenger.send(message);
        } catch (RemoteException e) {

        }

    }

    public void stop() {

        try {
            if (isMuted) {
                isMuted = false;
                mAudioManager.setStreamMute(BEEP_STREAM, false);
                mAudioManager.setStreamMute(BEEP_STREAM, false);

            }
            // mAudioManager.setStreamVolume(AudioManager.STREAM_SYSTEM,
            // lastVolume, 0);
            mSpeechRecognizer.destroy();
        } catch (Exception e) {

        }
        mNoSpeechCountDown.cancel();

    }

    @Override
    public void onResults(Bundle results) {
        receiveResults(results);
    }

    public boolean checkIfAppBlacklisted() {
        if (!ScreenReceiver.isScreenOn) {
            return false;
        }
        if (KeyguardReceiver.keyguardEnabled) {
            return false;
        }
        ActivityManager am = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);

        // get the info from the currently running task
        List<ActivityManager.RunningTaskInfo> taskInfo = am.getRunningTasks(1);

        ComponentName componentInfo = taskInfo.get(0).topActivity;
        componentInfo.getPackageName();
        if ((componentInfo.getPackageName().equals(context.getPackageName()) && useGetTasks) || blackListedApps.contains(componentInfo.getPackageName()) || CheckIfAppBlackListedService.checkIfBlacklistedBecauseOfMic(context, componentInfo.getPackageName())) {

            Intent i = new Intent(context, MyService.class);
            i.setAction("GNACTIVATED");
            context.startService(i);
            i = new Intent(context, CheckIfAppBlackListedService.class);
            context.startService(i);
            return true;
        }
        return false;
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        receiveResults(partialResults);
    }

    /**
     * common method to process any results bundle from
     * {@link com.dydxtech.openapps.GoogleSpeechRecognizer}
     */
    private void receiveResults(Bundle results) {
        if (isMuted) {
            isMuted = false;
            mAudioManager.setStreamMute(BEEP_STREAM, false);
            mAudioManager.setStreamMute(BEEP_STREAM, false);
        }
        if ((results != null)
                && results.containsKey(SpeechRecognizer.RESULTS_RECOGNITION)) {
            List<String> heard = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (heard == null) {

                startListening();
                return;
            }
            receiveWhatWasHeard(heard);
        } else {
            startListening();
        }
    }

    private void receiveWhatWasHeard(List<String> heard) {
        if (listener != null) {
            if (!listener.onHeard(heard)) {
                startListening();

            }

            return;
        }
        /*
         * if
		 * (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
		 * "speech_debug", false)) { // DEBUG: String text = ""; for (String
		 * string : heard) { text += string + "\n"; } Toast.makeText(context,
		 * text, Toast.LENGTH_LONG).show(); // DEBUG end }
		 */

        // find the target word
        for (String possible : heard) {
            String possibleEncoded = possible;
            if (optimizeEnglish) {
                possibleEncoded = doubleMetaphone.encode(possible);
            }
            int hotwordIndex = 1;
            for (String hotword : hotwords) {
                String hotwordEncoded = hotword;
                if (optimizeEnglish) {
                    hotwordEncoded = doubleMetaphone.encode(hotword);
                }
                try {
                    if (possibleEncoded.toLowerCase().contains(
                            hotwordEncoded.toLowerCase())) {
                        if (hotwordIndex <= hotwordCount) {
                            uiReference.HotwordHeard();
                        } else {
                            startListening();
                        }
                        return;
                    }
                } catch (Exception e) {
                }
                hotwordIndex++;
            }

        }

        // quietly start again
        startListening();

    }

    @Override
    public void onError(int errorCode) {

        if (!listeningPaused) // prevent restarting if shouldn't be listening
        {
            mIsCountDownOn = false;
            Message message = Message.obtain(null, MSG_RECOGNIZER_CANCEL);
            try {
                mServerMessenger.send(message);
                message = Message.obtain(null, MSG_RECOGNIZER_START_LISTENING);
                mServerMessenger.send(message);
            } catch (RemoteException e) {

            }
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            if (isMuted) {
                isMuted = false;
                mAudioManager.setStreamMute(BEEP_STREAM, false);
                mAudioManager.setStreamMute(BEEP_STREAM, false);

            }
            mIsCountDownOn = true;
            mNoSpeechCountDown.start();

        }

    }

    @Override
    public void onEndOfSpeech() {

        // Log.d("SpeechRecognizer", "End of speech");
    }

    /**
     * @see android.speech.RecognitionListener#onBeginningOfSpeech()
     */
    @Override
    public void onBeginningOfSpeech() {

        // Log.d("SpeechRecognizer", "Beginning of speech");
        // speech input will be processed, so there is no need for count down
        // anymore

        if (mIsCountDownOn) {
            mIsCountDownOn = false;
            mNoSpeechCountDown.cancel();
        }
        //Log.d(TAG, "onBeginingOfSpeech"); //$NON-NLS-1$
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        lastVolume = rmsdB;
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // Log.d("SpeechRecognizer", "Event: " + eventType);

    }

    // way to turn on/off speech recognizer without beep (hotword recognition)
    protected static class IncomingHandler extends Handler

    {
        private WeakReference<GoogleSpeechRecognizer> mtarget;

        IncomingHandler(GoogleSpeechRecognizer target) {
            mtarget = new WeakReference<GoogleSpeechRecognizer>(target);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                final GoogleSpeechRecognizer target = mtarget.get();
                switch (msg.what) {
                    case MSG_RECOGNIZER_START_LISTENING:
                        if (target.mAudioManager.isMusicActive()) {
                            Intent i = new Intent(target.context, MyService.class);
                            i.setAction("GNACTIVATED");
                            target.context.startService(i);
                            i = new Intent(target.context,
                                    CheckIfMusicPlayingService.class);
                            target.context.startService(i);
                            return;
                        }

                        if (target.checkIfAppBlacklisted()) {
                            return;
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                            // turn off beep sound
                            // MySpeechRecognizer.lastVolume =
                            // target.mAudioManager.getStreamVolume(AudioManager.STREAM_SYSTEM);
                            // target.mAudioManager.setStreamVolume(AudioManager.STREAM_SYSTEM,
                            // 0, 0);
                            if (!GoogleSpeechRecognizer.isMuted) {
                                isMuted = true;
                                target.mAudioManager.setStreamMute(
                                        BEEP_STREAM, true);

                            }
                        }
                        if (!target.mIsListening) {
                            target.mSpeechRecognizer
                                    .startListening(target.mSpeechRecognizerIntent);
                            target.mIsListening = true;
                            //Log.d(TAG, "message start listening"); //$NON-NLS-1$
                        }

                        break;

                    case MSG_RECOGNIZER_CANCEL:
                        target.mSpeechRecognizer.cancel();
                        target.mIsListening = false;
                        //Log.d(TAG, "message canceled recognizer"); //$NON-NLS-1$
                        break;

                }

            } catch (Exception e) {

            }
        }

    }
}
