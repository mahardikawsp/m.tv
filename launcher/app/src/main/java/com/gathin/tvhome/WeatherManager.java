package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pengambil data cuaca real-time presisi tinggi untuk:
 * Kelurahan Medokan Ayu, Kecamatan Rungkut, Kota Surabaya (Jawa Timur 60295).
 * Menggunakan Open-Meteo API dengan timezone Asia/Jakarta dan auto-refresh.
 */
public class WeatherManager {
    private static final String TAG = "WeatherManager";
    private static final String PREFS_NAME = "weather_location_prefs";
    private static final String PREF_WEATHER_TEXT = "cached_weather";
    private static final String PREF_LAST_FETCH = "last_weather_fetch";
    private static final String PREF_LOCATION_VERSION = "location_version";

    // Versi konfigurasi - ubah angka ini untuk mereset cache cuaca seketika
    private static final int LOCATION_VERSION = 5;

    // Titik presisi: Medokan Ayu, Rungkut, Surabaya 60295
    public static final double LAT = -7.3256;
    public static final double LON = 112.7988;
    public static final String CITY = "Medokan Ayu";

    // Refresh cuaca setiap 10 menit
    private static final long REFRESH_INTERVAL_MS = 10 * 60 * 1000L;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface WeatherCallback {
        void onWeatherUpdated(String weatherText);
    }

    public static void fetchWeather(Context context, WeatherCallback callback) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        long now = System.currentTimeMillis();
        long lastFetch = prefs.getLong(PREF_LAST_FETCH, 0);
        String cached = prefs.getString(PREF_WEATHER_TEXT, null);

        // Jika versi lokasi berubah, buang cache lama
        if (prefs.getInt(PREF_LOCATION_VERSION, 0) != LOCATION_VERSION) {
            cached = null;
            lastFetch = 0;
            prefs.edit()
                    .putInt(PREF_LOCATION_VERSION, LOCATION_VERSION)
                    .remove(PREF_WEATHER_TEXT)
                    .remove(PREF_LAST_FETCH)
                    .apply();
        }

        // Tampilkan cache terlebih dahulu jika ada agar UI tidak kosong
        if (cached != null && callback != null) {
            callback.onWeatherUpdated(cached);
        }

        // Cek apakah perlu mengambil data baru dari server (setiap 10 menit atau jika jam melompat)
        boolean needFetch = (cached == null) || (lastFetch == 0) || (now - lastFetch >= REFRESH_INTERVAL_MS) || (now < lastFetch);
        if (!needFetch) {
            return;
        }

        executor.execute(() -> {
            try {
                // Pastikan jadwal shalat sinkron dengan koordinat yang sama
                PrayerManager.updateLocation(LAT, LON, CITY);

                // API Open-Meteo dengan timezone Asia/Jakarta
                String weatherApiUrl = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,apparent_temperature,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=Asia%%2FJakarta",
                        LAT, LON);

                URL url = new URL(weatherApiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject current = json.getJSONObject("current");
                    double temp = current.getDouble("temperature_2m");
                    int code = current.getInt("weather_code");

                    JSONObject daily = json.getJSONObject("daily");
                    JSONArray maxArr = daily.getJSONArray("temperature_2m_max");
                    JSONArray minArr = daily.getJSONArray("temperature_2m_min");
                    double maxTemp = maxArr.getDouble(0);
                    double minTemp = minArr.getDouble(0);

                    String condition = getWeatherCondition(code);
                    int currentTempInt = (int) Math.round(temp);
                    int highTempInt = (int) Math.round(maxTemp);
                    int lowTempInt = (int) Math.round(minTemp);

                    // Format: ○ 31° Cerah Berawan • Medokan Ayu  H:32° L:26°
                    String result = String.format(Locale.getDefault(),
                            "○ %d° %s • %s  H:%d° L:%d°",
                            currentTempInt, condition, CITY, highTempInt, lowTempInt);

                    prefs.edit()
                            .putString(PREF_WEATHER_TEXT, result)
                            .putLong(PREF_LAST_FETCH, System.currentTimeMillis())
                            .apply();

                    Log.d(TAG, "Cuaca real-time Medokan Ayu berhasil diperbarui: " + result);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onWeatherUpdated(result));
                    }
                } else {
                    Log.w(TAG, "Response code Open-Meteo: " + conn.getResponseCode());
                }
            } catch (Exception e) {
                Log.w(TAG, "Gagal mengambil data cuaca: " + e.getMessage());
            }
        });
    }

    /**
     * Penerjemahan kode WMO Open-Meteo yang presisi untuk wilayah tropis Indonesia
     */
    private static String getWeatherCondition(int code) {
        switch (code) {
            case 0:
            case 1:
                return "Cerah";
            case 2:
                return "Cerah Berawan";
            case 3:
                return "Berawan";
            case 45:
            case 48:
                return "Berkabut";
            case 51:
            case 53:
            case 55:
                return "Gerimis";
            case 61:
            case 63:
            case 65:
                return "Hujan";
            case 80:
            case 81:
            case 82:
                return "Hujan Deras";
            case 95:
            case 96:
            case 99:
                return "Hujan Petir";
            default:
                return "Cerah Berawan";
        }
    }
}
