package ru.application.application;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private Spinner labelSpinner;
    private Button startStopButton;

    private boolean isRecording = false;
    private int currentLabelId = -1;
    private String androidId;

    private Map<String, Integer> labelMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Получаем уникальный Android ID устройства
        androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        labelSpinner = findViewById(R.id.labelSpinner);
        startStopButton = findViewById(R.id.startStopButton);

        // Загрузить список меток с сервера в Spinner
        fetchLabelsFromServer();

        // Обработчик нажатия кнопки запуска/остановки записи
        startStopButton.setOnClickListener(v -> {
            isRecording = !isRecording;
            if (isRecording) {
                startStopButton.setText("Остановить запись");
                // Запуск Foreground-сервиса для записи данных с сенсоров
                Intent serviceIntent = new Intent(this, SensorForegroundService.class);
                serviceIntent.putExtra("label_id", currentLabelId);
                serviceIntent.putExtra("android_id", androidId);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } else {
                startStopButton.setText("Начать запись");
                // Остановка сервиса и сбор данных
                stopService(new Intent(this, SensorForegroundService.class));
            }
        });
    }

    /**
     * Запрос списка меток с сервера и заполнение Spinner.
     */
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
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, labelTitles);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                labelSpinner.setAdapter(adapter);

                labelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        String selected = (String) parent.getItemAtPosition(position);
                        currentLabelId = labelMap.get(selected);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
            });
        }).start();
    }
}