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

                OfflineRecognizerConfig config = new OfflineRecognizerConfig();
                config.modelConfig = new OfflineRecognizerConfig.OfflineModelConfig();
                config.modelConfig.senseVoice = new OfflineRecognizerConfig.OfflineModelConfig.SenseVoiceModelConfig();
                config.modelConfig.senseVoice.model = new File(modelDir, "model.int8.onnx").getAbsolutePath();
                config.modelConfig.senseVoice.tokens = new File(modelDir, "tokens.txt").getAbsolutePath();
                config.modelConfig.numThreads = 2;
                config.featConfig.sampleRate = 16000;

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
            try {
                OfflineStream stream = recognizer.createStream();
                // 这里需要接入录音逻辑，实时读取PCM数据并送入 stream.acceptWaveform()
                // 识别结束后调用 recognizer.decode(stream) 获取结果
                // 由于代码篇幅限制，此处为示意，具体录音和识别逻辑需要完整实现
                String result = "识别结果占位"; 
                if (getCurrentInputConnection() != null && !result.isEmpty()) {
                    getCurrentInputConnection().commitText(result, 1);
                }
            } catch (Exception e) {
                // 错误处理
            } finally {
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
