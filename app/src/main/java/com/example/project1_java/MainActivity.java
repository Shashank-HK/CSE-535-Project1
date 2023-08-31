package com.example.project1_java;

import android.Manifest;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 100;
    private Button hrButton, rrButton;
    private EditText hrText, rrText;
    private ExecutorService service;
    private Recording recording = null;
    private VideoCapture<Recorder> videoCapture = null;
    private PreviewView previewView;

    private Handler handler;
    int cameraFacing = CameraSelector.LENS_FACING_BACK;
    private final ActivityResultLauncher<String> activityResultLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), result -> {
        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(cameraFacing);
        }
    });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ContentResolver contentResolver = getContentResolver();
        handler = new Handler();
        previewView = findViewById(R.id.viewFinder);
        hrText = findViewById(R.id.hr_text);
        hrButton = findViewById(R.id.hr_button);
        hrButton.setOnClickListener( view -> {
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                activityResultLauncher.launch(Manifest.permission.CAMERA);
            }  else {
                captureVideo();
            }
        });

        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activityResultLauncher.launch(Manifest.permission.CAMERA);
        } else {
            startCamera(cameraFacing);
        }

        service = Executors.newSingleThreadExecutor();
    }

    // heart rate sensor

    public void captureVideo() {
        Recording recording1 = recording;
        if (recording1 != null) {
            recording1.stop();
            recording = null;
            handler.removeCallbacksAndMessages(null);
            return;
        }
        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault()).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues).build();

        recording = videoCapture.getOutput().prepareRecording(MainActivity.this, options).start(ContextCompat.getMainExecutor(MainActivity.this), videoRecordEvent -> {
            if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                if (!((VideoRecordEvent.Finalize) videoRecordEvent).hasError()) {
                    Uri uri = ((VideoRecordEvent.Finalize) videoRecordEvent).getOutputResults().getOutputUri();
                    String msg = "Video capture succeeded: " + uri;
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

                    String path = convertMediaUriToPath(uri);
                    SlowTask task = new SlowTask();
                    String heartRate = task.doInBackground(path);
                    hrText.setText(heartRate);
                } else {
                    recording.close();
                    recording = null;
                    String msg = "Error: " + ((VideoRecordEvent.Finalize) videoRecordEvent).getError();
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                }
            }
        });

        handler.postDelayed(() -> closeRecording(), 5000);
    }

    public void startCamera(int cameraFacing) {
        ListenableFuture<ProcessCameraProvider> processCameraProvider = ProcessCameraProvider.getInstance(MainActivity.this);

        processCameraProvider.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = processCameraProvider.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                cameraProvider.unbindAll();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(cameraFacing).build();

                Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
                if (camera.getCameraInfo().hasFlashUnit()) {
                    camera.getCameraControl().enableTorch(true);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(MainActivity.this));
    }

    public void closeRecording(){
        recording.stop();
        recording = null;
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        service.shutdown();
    }


    // heart rate helper

    public String convertMediaUriToPath(Uri uri) {
        String[] proj = {MediaStore.Images.Media.DATA};
        Cursor cursor = getContentResolver().query(uri, proj, null, null, null);
        int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        cursor.moveToFirst();
        String path = cursor.getString(column_index);
        cursor.close();
        return path;
    }

    public class SlowTask extends AsyncTask<String, String, String> {
        @Override
        protected String doInBackground(String... params) {
            Bitmap mBitmap = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            List<Bitmap> frameList = new ArrayList<>();

            try {
                retriever.setDataSource(params[0]);
                String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                int aDuration = Integer.parseInt(duration);
                int i = 10;

                while (i < aDuration) {
                    Bitmap bitmap = retriever.getFrameAtIndex(i);
                    frameList.add(bitmap);
                    i += 5;
                }
            } catch (Exception e) {
            } finally {
                if (retriever != null) {
                    try {
                        retriever.release();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                List<Long> a = new ArrayList<>();

                for (Bitmap i : frameList) {
                    long redBucket = 0;
                    long pixelCount = 0;

                    for (int y = 550; y < 650; y++) {
                        for (int x = 550; x < 650; x++) {
                            int c = i.getPixel(x, y);
                            pixelCount++;
                            redBucket += Color.red(c) + Color.blue(c) + Color.green(c);
                        }
                    }

                    a.add(redBucket);
                }

                List<Long> b = new ArrayList<>();

                for (int i = 0; i < a.size() - 5; i++) {
                    long temp = (a.get(i) + a.get(i + 1) + a.get(i + 2) + a.get(i + 3) + a.get(i + 4)) / 4;
                    b.add(temp);
                }

                long x = b.get(0);
                int count = 0;

                for (int i = 1; i < b.size(); i++) {
                    long p = b.get(i);
                    if ((p - x) > 3500) {
                        count++;
                    }
                    x = b.get(i);
                }

                int rate = (int) ((count * 60.0 / 45) / 2);
                return Integer.toString(rate);
            }
        }
    }
}