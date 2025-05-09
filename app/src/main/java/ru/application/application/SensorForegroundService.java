package ru.application.application;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SensorForegroundService extends Service {

    private static final String CHANNEL_ID = "SensorServiceChannel";

    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope, magnetometer, pressureSensor;

    private final Handler handler = new Handler();
    private boolean isRecording = false;
    private int currentLabelId = -1;
    private String androidId;
    private long startTime;
    private final AtomicInteger globalId = new AtomicInteger(10000);

    private final int RECORD_DURATION_MS = 5000;
    private final int SAMPLE_RATE_MS = 10;
    private final int TOTAL_SAMPLES = 500;
    private final int PAUSE_DURATION_MS = 30000;

    private final List<JSONObject> motionData = new ArrayList<>();

    // Структура для хранения текущих значений сенсоров
    private static class SensorSnapshot {
        float accX = Float.NaN, accY = Float.NaN, accZ = Float.NaN;
        float gyroX = Float.NaN, gyroY = Float.NaN, gyroZ = Float.NaN;
        float magX = Float.NaN, magY = Float.NaN, magZ = Float.NaN;
        Float pressure = 0f;
    }
    private SensorSnapshot latestSnapshot = new SensorSnapshot();

    // Слушатель сенсоров: обновляет текущие значения latestSnapshot
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
    public void onCreate() {
        super.onCreate();
        // Создаем канал уведомлений для Foreground Service
        createNotificationChannel();
        // Формируем уведомление о работе сервиса
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Запись данных с датчиков")
                .setContentText("Приложение собирает данные")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)  // уведомление постоянно (не смахивается)
                .build();
        // Запуск сервиса в режиме Foreground
        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Инициализируем параметры на основе переданных данных
        if (intent != null) {
            currentLabelId = intent.getIntExtra("label_id", -1);
            androidId = intent.getStringExtra("android_id");
        }
        if (androidId == null) {
            // На случай, если Android ID не был передан, получим его напрямую
            androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        isRecording = true;
        // Получаем менеджер сенсоров и сами датчики
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        // Запускаем цикл сбора данных с сенсоров
        startRecordingCycle();
        return START_NOT_STICKY;
    }

    /**
     * Начинает 5-секундный цикл сбора данных с сенсоров.
     * Регистрирует слушатели и ждет активации всех сенсоров.
     */
    private void startRecordingCycle() {
        if (accelerometer != null)
            sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        if (gyroscope != null)
            sensorManager.registerListener(sensorEventListener, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        if (magnetometer != null)
            sensorManager.registerListener(sensorEventListener, magnetometer, SensorManager.SENSOR_DELAY_FASTEST);
        if (pressureSensor != null)
            sensorManager.registerListener(sensorEventListener, pressureSensor, SensorManager.SENSOR_DELAY_FASTEST);
        // Проверяем, что все основные сенсоры начали давать данные
        checkIfSensorsReady(0);
    }

    /**
     * Рекурсивно проверяет, готовы ли данные со всех необходимых сенсоров.
     * Если через определенное число попыток данные не получены, выдает ошибку.
     */
    private void checkIfSensorsReady(int attempt) {
        if (attempt >= 60) {
            Toast.makeText(this, "Ошибка: не все сенсоры активны", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Float.isNaN(latestSnapshot.accX) &&
                !Float.isNaN(latestSnapshot.gyroX) &&
                !Float.isNaN(latestSnapshot.magX)) {
            // Все основные сенсоры откликнулись, начинаем сбор данных
            motionData.clear();
            startTime = System.currentTimeMillis();
            collectData(0);
        } else {
            // Ждем 50 мс и проверяем снова
            handler.postDelayed(() -> checkIfSensorsReady(attempt + 1), 50);
        }
    }

    /**
     * Сбор данных с заданной частотой. Каждые SAMPLE_RATE_MS миллисекунд сохраняет текущее значение сенсоров.
     * По достижении TOTAL_SAMPLES или при остановке записи данные отправляются, и планируется следующий цикл.
     */
    private void collectData(int index) {
        if (!isRecording || index >= TOTAL_SAMPLES) {
            // Завершение сбора текущей серии данных
            sensorManager.unregisterListener(sensorEventListener);
            sendMotionData();
            // Планируем запуск следующего цикла сбора через PAUSE_DURATION_MS (30 секунд)
            handler.postDelayed(this::startRecordingCycle, PAUSE_DURATION_MS);
            return;
        }
        try {
            // Пропускаем точку, если какой-то из основных сенсоров не вернул значение (NaN)
            if (Float.isNaN(latestSnapshot.accX) ||
                    Float.isNaN(latestSnapshot.accY) || Float.isNaN(latestSnapshot.accZ) ||
                    Float.isNaN(latestSnapshot.gyroX) ||
                    Float.isNaN(latestSnapshot.gyroY) || Float.isNaN(latestSnapshot.gyroZ) ||
                    Float.isNaN(latestSnapshot.magX) ||
                    Float.isNaN(latestSnapshot.magY) || Float.isNaN(latestSnapshot.magZ)) {
                throw new Exception("NaN обнаружен в измерении. Пропуск точки");
            }
            // Формируем JSON-объект с текущими показаниями всех датчиков
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
        // Планируем запись следующей точки через SAMPLE_RATE_MS (10 мс)
        handler.postDelayed(() -> collectData(index + 1), SAMPLE_RATE_MS);
    }

    /**
     * Отправка накопленных за цикл данных на сервер в формате JSON с помощью OkHttp.
     */
    private void sendMotionData() {
        JSONArray array = new JSONArray(motionData);
        Log.d("SEND", "Отправка данных: " + array.length() + " точек");
        if (array.length() == 0) {
            // Если нечего отправлять, выходим
            Toast.makeText(this, "Нет данных для отправки", Toast.LENGTH_SHORT).show();
            return;
        }
        String jsonString = array.toString();
        Log.d("SEND_PAYLOAD", jsonString);
        OkHttpClient client = new OkHttpClient();
        RequestBody body = RequestBody.create(jsonString, MediaType.parse("application/json"));
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
    public void onDestroy() {
        super.onDestroy();
        // Остановка сбора данных и очистка ресурсов
        isRecording = false;
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorEventListener);
        }
        handler.removeCallbacksAndMessages(null);
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Binding не используется
        return null;
    }

    /**
     * Создание канала уведомлений для Android 8.0+ (Oreo и выше).
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sensor Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}