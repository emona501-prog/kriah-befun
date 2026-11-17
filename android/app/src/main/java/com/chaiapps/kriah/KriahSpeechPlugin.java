package com.chaiapps.kriah;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;

/**
 * קוראים בכיף – זיהוי דיבור של אנדרואיד עצמו (במקום של הדפדפן).
 * אירועים ל-JS: "result" {matches, final, sid}, "state" {state, sid}, "error" {code, sid}, "rms" {db, sid}
 */
@CapacitorPlugin(
    name = "KriahSpeech",
    permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "mic") }
)
public class KriahSpeechPlugin extends Plugin {
    private SpeechRecognizer sr = null;
    private int sid = 0;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastRms = 0;

    @PluginMethod
    public void available(PluginCall call) {
        JSObject r = new JSObject();
        r.put("available", SpeechRecognizer.isRecognitionAvailable(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void ensurePermission(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
        } else {
            requestPermissionForAlias("mic", call, "permCb");
        }
    }

    @PermissionCallback
    private void permCb(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("mic") == PermissionState.GRANTED);
        call.resolve(r);
    }

    @PluginMethod
    public void start(PluginCall call) {
        final String lang = call.getString("language", "he-IL");
        final int maxResults = call.getInt("maxResults", 5);
        final long silenceMs = call.getInt("silenceMs", 1800);
        // מילים צפויות (למשל כל הצבעים בשלב) – עוזר למנוע לזהות מילים קצרות של ילדים (אנדרואיד 13+)
        final ArrayList<String> hints = new ArrayList<>();
        try {
            JSArray h = call.getArray("hints");
            if (h != null) for (int k = 0; k < h.length(); k++) hints.add(h.getString(k));
        } catch (Exception e) { }
        final int id = ++sid;
        main.post(() -> {
            try {
                destroy();
                sr = SpeechRecognizer.createSpeechRecognizer(getContext());
                sr.setRecognitionListener(new Listener(id));
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults);
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs);
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs);
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L);
                if (!hints.isEmpty() && Build.VERSION.SDK_INT >= 33) {
                    i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, hints);
                }
                sr.startListening(i);
                emitState(id, "listening");
            } catch (Exception e) {
                emitError(id, -1);
                emitState(id, "stopped");
            }
        });
        JSObject r = new JSObject();
        r.put("sid", id);
        call.resolve(r);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        main.post(() -> {
            try { if (sr != null) sr.stopListening(); } catch (Exception e) { }
        });
        call.resolve();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        final int id = sid;
        sid++;
        main.post(() -> { destroy(); emitState(id, "stopped"); });
        call.resolve();
    }

    private void destroy() {
        if (sr != null) {
            try { sr.cancel(); } catch (Exception e) { }
            try { sr.destroy(); } catch (Exception e) { }
            sr = null;
        }
    }

    private void emitState(int id, String state) {
        JSObject o = new JSObject();
        o.put("state", state);
        o.put("sid", id);
        notifyListeners("state", o);
    }

    private void emitError(int id, int code) {
        JSObject o = new JSObject();
        o.put("code", code);
        o.put("sid", id);
        notifyListeners("error", o);
    }

    private void emitMatches(int id, Bundle b, boolean fin) {
        if (b == null) return;
        ArrayList<String> m = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (m == null || m.isEmpty()) return;
        try {
            JSArray arr = new JSArray();
            for (String x : m) arr.put(x);
            JSObject o = new JSObject();
            o.put("matches", arr);
            o.put("final", fin);
            o.put("sid", id);
            notifyListeners("result", o);
        } catch (Exception e) { }
    }

    @Override
    protected void handleOnDestroy() {
        main.post(this::destroy);
        super.handleOnDestroy();
    }

    private class Listener implements RecognitionListener {
        private final int id;
        Listener(int id) { this.id = id; }

        @Override public void onReadyForSpeech(Bundle params) { if (id == sid) emitState(id, "ready"); }
        @Override public void onBeginningOfSpeech() { if (id == sid) emitState(id, "speech"); }
        @Override public void onRmsChanged(float rmsdB) {
            long now = System.currentTimeMillis();
            if (id != sid || now - lastRms < 120) return;
            lastRms = now;
            JSObject o = new JSObject();
            o.put("db", rmsdB);
            o.put("sid", id);
            notifyListeners("rms", o);
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { if (id == sid) emitState(id, "end"); }
        @Override public void onError(int error) {
            if (id != sid) return;
            emitError(id, error);
            emitState(id, "stopped");
        }
        @Override public void onResults(Bundle results) {
            if (id != sid) return;
            emitMatches(id, results, true);
            emitState(id, "stopped");
        }
        @Override public void onPartialResults(Bundle partialResults) {
            if (id != sid) return;
            emitMatches(id, partialResults, false);
        }
        @Override public void onEvent(int eventType, Bundle params) { }
    }
}
