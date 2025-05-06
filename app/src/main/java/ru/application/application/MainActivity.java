package ru.application.application;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope, magnetometer, pressureSensor;

    private Spinner labelSpinner;
    private Button startStopButton;

    private boolean isRecording = false;
    private int currentLabelId = -1;

    private Handler handler = new Handler();

    private final int RECORD_DURATION_MS = 5000;
    private final int SAMPLE_RATE_MS = 10;
    private final int TOTAL_SAMPLES = 500;
    private final int PAUSE_DURATION_MS = 30000;

    private Map<String, Integer> labelMap = new HashMap<>();

    private static class SensorSnapshot {
        float accX = Float.NaN, accY = Float.NaN, accZ = Float.NaN;
        float gyroX = Float.NaN, gyroY = Float.NaN, gyroZ = Float.NaN;
        float magX = Float.NaN, magY = Float.NaN, magZ = Float.NaN;
        Float pressure = 0f;
    }

    private SensorSnapshot latestSnapshot = new SensorSnapshot();
    private final List<JSONObject> motionData = new ArrayList<>();
    private String androidId;
    private long startTime;
    private final AtomicInteger globalId = new AtomicInteger(10000);

    private final SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null) return;

            switch (event.sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    latestSnapshot.accX = event.values[0];
                    latestSnapshot.accY = event.values[1];
                    latestSnapshot.accZ = event.values[2];
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    latestSnapshot.gyroX = event.values[0];
                    latestSnapshot.gyroY = event.values[1];
                    latestSnapshot.gyroZ = event.values[2];
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    latestSnapshot.magX = event.values[0];
                    latestSnapshot.magY = event.values[1];
                    latestSnapshot.magZ = event.values[2];
                    break;
                case Sensor.TYPE_PRESSURE:
                    latestSnapshot.pressure = event.values[0];
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        labelSpinner = findViewById(R.id.labelSpinner);
        startStopButton = findViewById(R.id.startStopButton);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        fetchLabelsFromServer();

        startStopButton.setOnClickListener(v -> {
            isRecording = !isRecording;
            if (isRecording) {
                startStopButton.setText("Остановить запись");
                startRecordingCycle();
            } else {
                startStopButton.setText("Начать запись");
                handler.removeCallbacksAndMessages(null);
                sensorManager.unregisterListener(sensorEventListener);
            }
        });
    }

    private void fetchLabelsFromServer() {
        OkHttpClient client = new OkHttpClient();
        labelMap.clear();
        List<String> labelTitles = new ArrayList<>();

        new Thread(() -> {
            Request request = new Request.Builder()
                    .url("http://89.111.170.165:8000/label/")
                    .get()
                    .addHeader("accept", "application/json")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.e("LABELS", "Ошибка запроса: " + response.code());
                    return;
                }

                String responseBody = response.body().string();
                JSONArray labelsArray = new JSONArray(responseBody);

                for (int i = 0; i < labelsArray.length(); i++) {
                    JSONObject labelJson = labelsArray.getJSONObject(i);
                    int id = labelJson.getInt("id");
                    String name = labelJson.getString("name");
                    labelMap.put(name, id);
                    labelTitles.add(name);
                    Log.d("LABELS", "Добавлена метка: " + name);
                }

            } catch (Exception e) {
                Log.e("LABELS", "Ошибка парсинга JSON: " + e.getMessage());
            }

            runOnUiThread(() -> {
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labelTitles);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                labelSpinner.setAdapter(adapter);

                labelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        String selected = (String) parent.getItemAtPosition(position);
                        currentLabelId = labelMap.get(selected);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
            });
        }).start();
    }

    private void startRecordingCycle() {
        if (accelerometer != null) sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        if (gyroscope != null) sensorManager.registerListener(sensorEventListener, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        if (magnetometer != null) sensorManager.registerListener(sensorEventListener, magnetometer, SensorManager.SENSOR_DELAY_FASTEST);
        if (pressureSensor != null) sensorManager.registerListener(sensorEventListener, pressureSensor, SensorManager.SENSOR_DELAY_FASTEST);

        checkIfSensorsReady(0);
    }

    private void checkIfSensorsReady(int attempt) {
        if (attempt >= 60) {
            Toast.makeText(this, "Ошибка: не все сенсоры активны", Toast.LENGTH_LONG).show();
            return;
        }

        if (!Float.isNaN(latestSnapshot.accX) &&
                !Float.isNaN(latestSnapshot.gyroX) &&
                !Float.isNaN(latestSnapshot.magX)) {
            motionData.clear();
            startTime = System.currentTimeMillis();
            collectData(0);
        } else {
            handler.postDelayed(() -> checkIfSensorsReady(attempt + 1), 50);
        }
    }

    private void collectData(int index) {
        if (!isRecording || index >= TOTAL_SAMPLES) {
            sensorManager.unregisterListener(sensorEventListener);
            sendMotionData();
            handler.postDelayed(this::startRecordingCycle, PAUSE_DURATION_MS);
            return;
        }

        try {
            if (Float.isNaN(latestSnapshot.accX) || Float.isNaN(latestSnapshot.accY) || Float.isNaN(latestSnapshot.accZ) ||
                    Float.isNaN(latestSnapshot.gyroX) || Float.isNaN(latestSnapshot.gyroY) || Float.isNaN(latestSnapshot.gyroZ) ||
                    Float.isNaN(latestSnapshot.magX) || Float.isNaN(latestSnapshot.magY) || Float.isNaN(latestSnapshot.magZ)) {
                throw new Exception("NaN обнаружен в измерении. Пропуск точки");
            }

            JSONObject obj = new JSONObject();
            obj.put("time", startTime);
            obj.put("user_imei", androidId);
            obj.put("id", globalId.getAndIncrement());
            obj.put("acceleration_x", latestSnapshot.accX);
            obj.put("acceleration_y", latestSnapshot.accY);
            obj.put("acceleration_z", latestSnapshot.accZ);
            obj.put("gyro_x", latestSnapshot.gyroX);
            obj.put("gyro_y", latestSnapshot.gyroY);
            obj.put("gyro_z", latestSnapshot.gyroZ);
            obj.put("magnetometer_x", latestSnapshot.magX);
            obj.put("magnetometer_y", latestSnapshot.magY);
            obj.put("magnetometer_z", latestSnapshot.magZ);
            obj.put("pressure", latestSnapshot.pressure);
            obj.put("label_id", currentLabelId);

            motionData.add(obj);
        } catch (Exception e) {
            Log.e("DATA", "Ошибка при записи точки: " + e.getMessage());
        }

        handler.postDelayed(() -> collectData(index + 1), SAMPLE_RATE_MS);
    }

    private void sendMotionData() {
        JSONArray array = new JSONArray(motionData);
        Log.d("SEND", "Отправка данных: " + array.length() + " точек");

        if (array.length() == 0) {
            Toast.makeText(this, "Нет данных для отправки", Toast.LENGTH_SHORT).show();
            return;
        }

        String jsonString = array.toString();
        Log.d("SEND_PAYLOAD", jsonString);

        OkHttpClient client = new OkHttpClient();

        RequestBody body = RequestBody.create(
                jsonString,
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url("http://89.111.170.165:8000/motions/")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("SEND", "Ошибка при отправке: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e("SEND", "Ответ сервера: " + response.code());
                } else {
                    Log.d("SEND", "Данные успешно отправлены. Код: " + response.code());
                }
            }
        });

        Toast.makeText(this, "Отправлено: " + array.length() + " точек", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorEventListener);
        }
        handler.removeCallbacksAndMessages(null);
    }
}
