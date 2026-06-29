package com.example.drowsinessdetection;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final float EYE_CLOSED_THRESHOLD = 0.35f;
    private static final long DANGER_AFTER_MS = 3000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private ToneGenerator toneGenerator;

    private PreviewView previewView;
    private TextView statusText;
    private TextView eyeValueText;
    private TextView blinkValueText;
    private TextView closedEyeText;
    private TextView alertText;
    private ProgressBar drowsyProgress;
    private Button startStopButton;

    private boolean monitoring = false;
    private boolean processingFrame = false;
    private boolean alarmRunning = false;
    private long eyesClosedStartMs = 0L;

    private final Runnable alarmRunnable = new Runnable() {
        @Override
        public void run() {
            if (!alarmRunning) {
                return;
            }

            if (toneGenerator != null) {
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800);
            }
            vibrateAlert();
            mainHandler.postDelayed(this, 850);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        previewView = findViewById(R.id.previewView);
        statusText = findViewById(R.id.statusText);
        eyeValueText = findViewById(R.id.eyeValueText);
        blinkValueText = findViewById(R.id.blinkValueText);
        closedEyeText = findViewById(R.id.closedEyeText);
        alertText = findViewById(R.id.alertText);
        drowsyProgress = findViewById(R.id.drowsyProgress);
        startStopButton = findViewById(R.id.startStopButton);
        drowsyProgress.setMax((int) DANGER_AFTER_MS);

        cameraExecutor = Executors.newSingleThreadExecutor();
        toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        faceDetector = FaceDetection.getClient(
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .setMinFaceSize(0.18f)
                        .enableTracking()
                        .build()
        );

        startStopButton.setOnClickListener(view -> {
            if (monitoring) {
                stopMonitoring();
            } else {
                requestCameraAndStart();
            }
        });
        updateIdleState();
    }

    @Override
    protected void onDestroy() {
        stopAlarm();
        if (faceDetector != null) {
            faceDetector.close();
        }
        if (toneGenerator != null) {
            toneGenerator.release();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        super.onDestroy();
    }

    private void requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startMonitoring();
            return;
        }

        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMonitoring();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
            updateIdleState();
        }
    }

    private void startMonitoring() {
        monitoring = true;
        eyesClosedStartMs = 0L;
        processingFrame = false;
        startStopButton.setText(R.string.stop_monitoring);
        statusText.setText(R.string.scanning);
        statusText.setTextColor(getColor(R.color.primary));
        alertText.setText(R.string.looking_for_face);
        alertText.setTextColor(getColor(R.color.warning));
        startCamera();
    }

    private void stopMonitoring() {
        monitoring = false;
        stopAlarm();
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
                cameraProvider.unbindAll();
            } catch (Exception ignored) {
                // The UI still resets even if CameraX has already released the provider.
            }
        }, ContextCompat.getMainExecutor(this));
        updateIdleState();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (Exception exception) {
                Toast.makeText(
                        this,
                        getString(R.string.camera_start_error, exception.getMessage()),
                        Toast.LENGTH_LONG
                ).show();
                stopMonitoring();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!monitoring || processingFrame || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        processingFrame = true;
        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        faceDetector.process(image)
                .addOnSuccessListener(this::handleFaces)
                .addOnFailureListener(error -> mainHandler.post(() -> {
                    statusText.setText(R.string.camera_error);
                    statusText.setTextColor(getColor(R.color.danger));
                    alertText.setText(R.string.face_detection_failed);
                    alertText.setTextColor(getColor(R.color.danger));
                }))
                .addOnCompleteListener(task -> {
                    processingFrame = false;
                    imageProxy.close();
                });
    }

    private void handleFaces(List<Face> faces) {
        if (faces.isEmpty()) {
            mainHandler.post(() -> updateNoFaceState());
            return;
        }

        Face face = faces.get(0);
        Float leftEyeOpen = face.getLeftEyeOpenProbability();
        Float rightEyeOpen = face.getRightEyeOpenProbability();

        if (leftEyeOpen == null || rightEyeOpen == null) {
            mainHandler.post(() -> {
                statusText.setText(R.string.adjust_face);
                statusText.setTextColor(getColor(R.color.warning));
                eyeValueText.setText(R.string.eyes_open_probability_empty);
                blinkValueText.setText(R.string.face_detected_yes);
                alertText.setText(R.string.keep_both_eyes_visible);
                alertText.setTextColor(getColor(R.color.warning));
            });
            return;
        }

        boolean eyesClosed = leftEyeOpen < EYE_CLOSED_THRESHOLD && rightEyeOpen < EYE_CLOSED_THRESHOLD;
        long now = SystemClock.elapsedRealtime();

        if (eyesClosed) {
            if (eyesClosedStartMs == 0L) {
                eyesClosedStartMs = now;
            }
        } else {
            eyesClosedStartMs = 0L;
            stopAlarm();
        }

        long closedForMs = eyesClosed && eyesClosedStartMs > 0L ? now - eyesClosedStartMs : 0L;
        boolean danger = closedForMs >= DANGER_AFTER_MS;

        if (danger) {
            startAlarm();
        }

        mainHandler.post(() -> updateDashboard(leftEyeOpen, rightEyeOpen, closedForMs, danger, eyesClosed));
    }

    private void updateDashboard(
            float leftEyeOpen,
            float rightEyeOpen,
            long closedForMs,
            boolean danger,
            boolean eyesClosed
    ) {
        eyeValueText.setText(getString(R.string.eyes_open_probability_values, leftEyeOpen, rightEyeOpen));
        blinkValueText.setText(R.string.face_detected_yes);
        closedEyeText.setText(getString(R.string.eyes_closed_value, closedForMs / 1000.0));
        drowsyProgress.setProgress((int) Math.min(closedForMs, DANGER_AFTER_MS));

        if (danger) {
            statusText.setText(R.string.danger);
            statusText.setTextColor(getColor(R.color.danger));
            alertText.setText(R.string.alarm_wake_up);
            alertText.setTextColor(getColor(R.color.danger));
        } else if (eyesClosed) {
            statusText.setText(R.string.eyes_closed);
            statusText.setTextColor(getColor(R.color.warning));
            alertText.setText(R.string.warning_alarm_at_3_seconds);
            alertText.setTextColor(getColor(R.color.warning));
        } else {
            statusText.setText(R.string.awake);
            statusText.setTextColor(getColor(R.color.safe));
            alertText.setText(R.string.no_alert);
            alertText.setTextColor(getColor(R.color.safe));
            drowsyProgress.setProgress(0);
        }
    }

    private void updateNoFaceState() {
        eyesClosedStartMs = 0L;
        stopAlarm();
        statusText.setText(R.string.no_face);
        statusText.setTextColor(getColor(R.color.warning));
        eyeValueText.setText(R.string.eyes_open_probability_empty);
        blinkValueText.setText(R.string.face_detected_no);
        closedEyeText.setText(R.string.eyes_closed_empty);
        drowsyProgress.setProgress(0);
        alertText.setText(R.string.place_face_in_front_camera);
        alertText.setTextColor(getColor(R.color.warning));
    }

    private void updateIdleState() {
        startStopButton.setText(R.string.start_camera_monitoring);
        statusText.setText(R.string.ready);
        statusText.setTextColor(getColor(R.color.primary));
        eyeValueText.setText(R.string.eyes_open_probability_empty);
        blinkValueText.setText(R.string.face_detected_no);
        closedEyeText.setText(R.string.eyes_closed_empty);
        drowsyProgress.setProgress(0);
        alertText.setText(R.string.no_alert);
        alertText.setTextColor(getColor(R.color.safe));
    }

    private void startAlarm() {
        if (alarmRunning) {
            return;
        }

        alarmRunning = true;
        mainHandler.post(alarmRunnable);
    }

    private void stopAlarm() {
        alarmRunning = false;
        mainHandler.removeCallbacks(alarmRunnable);
        if (toneGenerator != null) {
            toneGenerator.stopTone();
        }
    }

    private void vibrateAlert() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(
                    new long[]{0, 250, 120, 350},
                    new int[]{0, 255, 0, 255},
                    -1
            ));
        } else {
            vibrator.vibrate(new long[]{0, 250, 120, 350}, -1);
        }
    }
}
