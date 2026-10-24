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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pengambil data cuaca real-time untuk satu lokasi tetap: Kelurahan Medokan Ayu,
 * Kecamatan Rungkut, Kota Surabaya, Jawa Timur 60295.
 * Menggunakan Open-Meteo API (gratis, presisi, tanpa API key) + cache 30 menit.
 * Koordinat yang sama dipakai untuk jadwal shalat agar keduanya selalu sinkron.
 */
public class WeatherManager {
    private static final String TAG = "WeatherManager";
    private static final String PREFS_NAME = "weather_location_prefs";
    private static final String PREF_WEATHER_TEXT = "cached_weather";
    private static final String PREF_LAST_FETCH = "last_weather_fetch";
    private static final String PREF_LOCATION_VERSION = "location_version";

    // Lokasi cuaca & jadwal shalat (titik tengah Kelurahan Medokan Ayu, Rungkut,
    // Surabaya 60295). Ini satu-satunya tempat yang perlu diubah untuk pindah lokasi.
    // Naikkan LOCATION_VERSION setiap kali LAT/LON/CITY berubah supaya cache kota lama dibuang.
    private static final int LOCATION_VERSION = 2;
    public static final double LAT = -7.3256;
    public static final double LON = 112.7988;
    public static final String CITY = "Medokan Ayu";

    private static final long REFRESH_INTERVAL_MS = 30 * 60 * 1000L; // 30 menit
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

        // Lokasi berubah: buang cache teks lama (yang masih menyebut kota sebelumnya).
        if (prefs.getInt(PREF_LOCATION_VERSION, 0) != LOCATION_VERSION) {
            cached = null;
            lastFetch = 0;
            prefs.edit()
                    .putInt(PREF_LOCATION_VERSION, LOCATION_VERSION)
                    .remove(PREF_WEATHER_TEXT)
                    .remove(PREF_LAST_FETCH)
                    .apply();
        }

        // Jika data baru diupdate kurang dari 30 menit, tampilkan cache dulu
        if (cached != null) {
            callback.onWeatherUpdated(cached);
            if (now - lastFetch < REFRESH_INTERVAL_MS) {
                return;
            }
        }

        executor.execute(() -> {
            try {
                // 1. Lokasi tetap: Medokan Ayu, Rungkut, Surabaya. Tidak ada IP geolocation,
                //    jadi cuaca tidak berpindah mengikuti IP koneksi.
                double lat = LAT;
                double lon = LON;
                String city = CITY;

                // Jadwal shalat memakai titik yang sama.
                PrayerManager.updateLocation(lat, lon, city);

                // 2. Ambil data cuaca dari Open-Meteo berdasarkan lat & lon
                String weatherApiUrl = String.format(java.util.Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto",
                        lat, lon);

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
                    String result = String.format("○ %d° %s • %s  H:%d° L:%d°",
                            (int) Math.round(temp), condition, city,
                            (int) Math.round(maxTemp), (int) Math.round(minTemp));

                    prefs.edit()
                            .putString(PREF_WEATHER_TEXT, result)
                            .putLong(PREF_LAST_FETCH, System.currentTimeMillis())
                            .apply();

                    Log.d(TAG, "Cuaca berhasil diperbarui: " + result);
                    mainHandler.post(() -> callback.onWeatherUpdated(result));
                } else {
                    Log.w(TAG, "Response code Open-Meteo: " + conn.getResponseCode());
                }
            } catch (Exception e) {
                Log.w(TAG, "Gagal mengambil data cuaca: " + e.getMessage());
            }
        });
    }

    private static String getWeatherCondition(int code) {
        if (code == 0) return "Cerah";
        if (code == 1) return "Sebagian Cerah";
        if (code == 2) return "Sebagian Berawan";
        if (code == 3) return "Mendung";
        if (code == 45 || code == 48) return "Berkabut";
        if (code >= 51 && code <= 55) return "Gerimis";
        if (code >= 61 && code <= 65) return "Hujan";
        if (code >= 80 && code <= 82) return "Hujan Deras";
        if (code >= 95) return "Badai Petir";
        return "Cerah";
    }
}
