package com.example.voiceime;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class VoiceImeService extends InputMethodService {

    private static OfflineRecognizer recognizer = null;
    private static boolean isModelLoading = false;
    private Thread workerThread;
    private Button btn;

    @Override
    public void onCreate() {
        super.onCreate();
        initModel();
    }

    @Override
    public View onCreateInputView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        btn = new Button(this);
        btn.setOnClickListener(v -> startRecording());
        layout.addView(btn);
        updateButtonState();
        return layout;
    }

    private void updateButtonState() {
        if (btn == null) return;
        btn.post(() -> {
            if (isModelLoading) {
                btn.setText("模型加载中...");
                btn.setEnabled(false);
            } else if (recognizer == null) {
                btn.setText("模型加载失败");
                btn.setEnabled(false);
            } else {
                btn.setText("点我开始说话");
                btn.setEnabled(true);
            }
        });
    }

    private void initModel() {
        if (recognizer != null || isModelLoading) return;
        isModelLoading = true;

        new Thread(() -> {
            try {
                File modelDir = new File(getFilesDir(), "model-sensevoice");
                if (!modelDir.exists()) {
                    modelDir.mkdirs();
                    String[] files = getAssets().list("model-sensevoice");
                    for (String file : files) {
                        copyAssetToFile("model-sensevoice/" + file, new File(modelDir, file));
                    }
                }

                OfflineSenseVoiceModelConfig senseVoiceConfig = OfflineSenseVoiceModelConfig.builder()
                        .setModel(new File(modelDir, "model.int8.onnx").getAbsolutePath())
                        .build();

                OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                        .setSenseVoice(senseVoiceConfig)
                        .setTokens(new File(modelDir, "tokens.txt").getAbsolutePath())
                        .setNumThreads(2)
                        .setDebug(true)
                        .build();

                OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                        .setOfflineModelConfig(modelConfig)
                        .setDecodingMethod("greedy_search")
                        .build();

                recognizer = new OfflineRecognizer(config);
                isModelLoading = false;
                updateButtonState();
            } catch (Exception e) {
                isModelLoading = false;
                final String msg = e.getMessage();
                if (btn != null) {
                    btn.post(() -> btn.setText("错误: " + msg));
                }
            }
        }).start();
    }

    private void copyAssetToFile(String assetPath, File outFile) throws Exception {
        try (InputStream is = getAssets().open(assetPath);
             FileOutputStream os = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
        }
    }

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先给 VoiceIME 麦克风权限", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        }
        if (recognizer == null) {
            Toast.makeText(this, "模型还没准备好", Toast.LENGTH_SHORT).show();
            return;
        }

        btn.setText("正在听...");
        btn.setEnabled(false);

        workerThread = new Thread(() -> {
            android.media.AudioRecord audioRecord = null;
            try {
                int sampleRate = 16000;
                int bufferSize = android.media.AudioRecord.getMinBufferSize(
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                if (bufferSize == android.media.AudioRecord.ERROR
                        || bufferSize == android.media.AudioRecord.ERROR_BAD_VALUE) {
                    bufferSize = sampleRate * 2;
                }

                audioRecord = new android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
                audioRecord.startRecording();

                OfflineStream stream = recognizer.createStream();
                short[] buffer = new short[bufferSize / 2];
                long startTime = System.currentTimeMillis();

                while (!Thread.currentThread().isInterrupted()
                        && System.currentTimeMillis() - startTime < 8000) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        float[] samples = new float[read];
                        for (int i = 0; i < read; i++) {
                            samples[i] = buffer[i] / 32768.0f;
                        }
                        stream.acceptWaveform(samples, sampleRate);
                    }
                }

                recognizer.decode(stream);
                String result = recognizer.getResult(stream);
                stream.release();

                if (result != null && !result.isEmpty() && getCurrentInputConnection() != null) {
                    getCurrentInputConnection().commitText(result, 1);
                }
            } catch (Exception e) {
                final String msg = e.getMessage();
                if (btn != null) {
                    btn.post(() -> Toast.makeText(VoiceImeService.this,
                            "识别失败: " + msg, Toast.LENGTH_LONG).show());
                }
            } finally {
                if (audioRecord != null) {
                    try { audioRecord.stop(); } catch (Exception ignored) {}
                    audioRecord.release();
                }
                if (btn != null) {
                    btn.post(() -> {
                        btn.setText("点我开始说话");
                        btn.setEnabled(true);
                    });
                }
            }
        });
        workerThread.start();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (workerThread != null && workerThread.isAlive()) {
            workerThread.interrupt();
        }
    }
}
