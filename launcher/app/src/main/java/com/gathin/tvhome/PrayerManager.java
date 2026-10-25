package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import java.util.Calendar;
import java.util.Locale;

public class PrayerManager {
    private static final String TAG = "PrayerManager";
    private static final String PREFS_NAME = "weather_location_prefs";

    // Koordinat lokasi dinamis, default dari WeatherManager
    public static double latitude = WeatherManager.LAT;
    public static double longitude = WeatherManager.LON;
    public static String cityName = WeatherManager.CITY;
    public static final double TIMEZONE = 7.0; // WIB (GMT+7)

    public static class PrayerInfo {
        public String subuh, terbit, dzuhur, ashar, maghrib, isya;
        public String nextPrayerName;
        public String nextPrayerTime;
        public int minutesUntilNext;
        public String countdownText;
        public String fullScheduleText;
        /** Waktu shalat dalam urutan: Subuh, Dzuhur, Ashar, Maghrib, Isya (5 elemen) */
        public String[] prayerTimesArray;
        public String locationName;
    }

    private static MediaPlayer mediaPlayer;
    private static PowerManager.WakeLock wakeLock;

    public static void updateLocation(double lat, double lon, String city) {
        latitude = lat;
        longitude = lon;
        if (city != null && !city.isEmpty()) {
            cityName = city;
        }
        Log.d(TAG, "Koordinat jadwal shalat diperbarui: " + cityName + " (" + latitude + ", " + longitude + ")");
    }

    public static PrayerInfo calculate(Context context, Calendar cal) {
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);

        int a = (14 - month) / 12;
        int y = year + 4800 - a;
        int m = month + 12 * a - 3;
        double jd = day + ((153 * m + 2) / 5) + 365 * y + (y / 4) - (y / 100) + (y / 400) - 32045;

        double d = jd - 2451545.0;
        double g = 357.529 + 0.98560028 * d;
        double q = 280.459 + 0.98564736 * d;
        double L = q + 1.915 * Math.sin(Math.toRadians(g)) + 0.020 * Math.sin(Math.toRadians(2 * g));
        double e = 23.439 - 0.00000036 * d;

        double sinDec = Math.sin(Math.toRadians(e)) * Math.sin(Math.toRadians(L));
        double dec = Math.toDegrees(Math.asin(sinDec));

        double ra = Math.toDegrees(Math.atan2(Math.cos(Math.toRadians(e)) * Math.sin(Math.toRadians(L)), Math.cos(Math.toRadians(L))));
        ra = (ra % 360 + 360) % 360;
        ra = ra / 15.0;

        double eot = (q / 15.0) - ra;
        while (eot > 1.0) eot -= 24.0;
        while (eot < -1.0) eot += 24.0;
        eot = eot * 60.0; // minutes

        double noon = 12.0 + TIMEZONE - (longitude / 15.0) - (eot / 60.0);

        double fajrH = getHourAngle(-20.0, latitude, dec) / 15.0;
        double sunH = getHourAngle(-0.8333, latitude, dec) / 15.0;
        double ishaH = getHourAngle(-18.0, latitude, dec) / 15.0;

        double asrAlt = Math.toDegrees(Math.atan(1.0 / (1.0 + Math.tan(Math.toRadians(Math.abs(latitude - dec))))));
        double asrH = getHourAngle(asrAlt, latitude, dec) / 15.0;

        // Ditambahkan ihtiyat (pengaman 2 menit standar Kemenag)
        double tSubuh = noon - fajrH + (2.0 / 60.0);
        double tTerbit = noon - sunH;
        double tDzuhur = noon + (2.0 / 60.0);
        double tAshar = noon + asrH + (2.0 / 60.0);
        double tMaghrib = noon + sunH + (2.0 / 60.0);
        double tIsya = noon + ishaH + (2.0 / 60.0);

        PrayerInfo info = new PrayerInfo();
        info.subuh = formatTime(tSubuh);
        info.terbit = formatTime(tTerbit);
        info.dzuhur = formatTime(tDzuhur);
        info.ashar = formatTime(tAshar);
        info.maghrib = formatTime(tMaghrib);
        info.isya = formatTime(tIsya);
        info.locationName = cityName;

        int currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        int mSubuh = toMinutes(tSubuh);
        int mDzuhur = toMinutes(tDzuhur);
        int mAshar = toMinutes(tAshar);
        int mMaghrib = toMinutes(tMaghrib);
        int mIsya = toMinutes(tIsya);

        String nextName;
        String nextTime;
        int diff;

        if (currentMinutes < mSubuh) {
            nextName = "Subuh";
            nextTime = info.subuh;
            diff = mSubuh - currentMinutes;
        } else if (currentMinutes < mDzuhur) {
            nextName = "Dzuhur";
            nextTime = info.dzuhur;
            diff = mDzuhur - currentMinutes;
        } else if (currentMinutes < mAshar) {
            nextName = "Ashar";
            nextTime = info.ashar;
            diff = mAshar - currentMinutes;
        } else if (currentMinutes < mMaghrib) {
            nextName = "Maghrib";
            nextTime = info.maghrib;
            diff = mMaghrib - currentMinutes;
        } else if (currentMinutes < mIsya) {
            nextName = "Isya";
            nextTime = info.isya;
            diff = mIsya - currentMinutes;
        } else {
            nextName = "Subuh (Besok)";
            nextTime = info.subuh;
            diff = (24 * 60 - currentMinutes) + mSubuh;
        }

        info.nextPrayerName = nextName;
        info.nextPrayerTime = nextTime;
        info.minutesUntilNext = diff;

        int hours = diff / 60;
        int mins = diff % 60;
        String countdownStr;
        if (hours > 0) {
            countdownStr = String.format(Locale.getDefault(), "Menuju %s: %s (dalam %d jam %d menit)", nextName, nextTime, hours, mins);
        } else {
            countdownStr = String.format(Locale.getDefault(), "Menuju %s: %s (dalam %d menit)", nextName, nextTime, mins);
        }
        info.countdownText = countdownStr;
        info.prayerTimesArray = new String[]{info.subuh, info.dzuhur, info.ashar, info.maghrib, info.isya};
        info.fullScheduleText = String.format(Locale.getDefault(),
                "Subuh %s  •  Dzuhur %s  •  Ashar %s  •  Maghrib %s  •  Isya %s",
                info.subuh, info.dzuhur, info.ashar, info.maghrib, info.isya);

        // Periksa apakah waktu adzan telah tiba (sinkronisasi dari minuteTick)
        if (context != null) {
            checkAndPlayAdzan(context, cal, currentMinutes, mSubuh, mDzuhur, mAshar, mMaghrib, mIsya);
        }

        return info;
    }

    private static void checkAndPlayAdzan(Context context, Calendar cal, int current, int mSubuh, int mDzuhur, int mAshar, int mMaghrib, int mIsya) {
        String prayerNow = null;
        if (Math.abs(current - mSubuh) <= 1) prayerNow = "Subuh";
        else if (Math.abs(current - mDzuhur) <= 1) prayerNow = "Dzuhur";
        else if (Math.abs(current - mAshar) <= 1) prayerNow = "Ashar";
        else if (Math.abs(current - mMaghrib) <= 1) prayerNow = "Maghrib";
        else if (Math.abs(current - mIsya) <= 1) prayerNow = "Isya";

        if (prayerNow != null) {
            playAdzanIfValid(context, prayerNow);
        }
    }

    /**
     * Memvalidasi apakah adzan benar-benar boleh diputar:
     * 1. Belum pernah diputar untuk waktu shalat ini pada hari ini (disimpan di SharedPreferences)
     * 2. Waktu saat ini harus sesuai (+- 2 menit) dengan jadwal shalat tersebut
     * 3. Jika valid, putar audio Arab yang sesuai (Subuh memakai adzan_subuh.mp3, lainnya memakai adzan.mp3)
     */
    public static synchronized boolean playAdzanIfValid(Context context, String prayerName) {
        if (context == null || prayerName == null) return false;

        Calendar now = Calendar.getInstance();
        int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        // 1. Cek riwayat putar hari ini
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String todayKey = "adzan_played_" + now.get(Calendar.YEAR) + "_" + now.get(Calendar.DAY_OF_YEAR) + "_" + prayerName;
        if (prefs.getBoolean(todayKey, false)) {
            Log.d(TAG, "Adzan " + prayerName + " sudah pernah diputar hari ini. Abaikan.");
            return false;
        }

        // 2. Hitung jadwal shalat dan cek toleransi waktu (+- 2 menit)
        PrayerInfo info = calculate(null, now);
        int scheduledMinutes = getPrayerMinutes(info, prayerName);

        if (scheduledMinutes < 0 || Math.abs(currentMinutes - scheduledMinutes) > 2) {
            Log.w(TAG, "Alarm adzan " + prayerName + " dibatalkan karena waktu tidak cocok! Current: "
                    + currentMinutes + " (" + formatMinutes(currentMinutes) + "), Scheduled: "
                    + scheduledMinutes + " (" + formatMinutes(scheduledMinutes) + ")");
            return false;
        }

        // 3. Simpan status bahwa adzan waktu ini sudah diputar hari ini
        prefs.edit().putBoolean(todayKey, true).apply();

        // 4. Tampilkan toast pemberitahuan
        try {
            Toast.makeText(context.getApplicationContext(), "🕌 Masuk Waktu " + prayerName, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}

        // 5. Putar audio adzan
        playAdzan(context, prayerName);
        return true;
    }

    private static int getPrayerMinutes(PrayerInfo info, String prayerName) {
        if (info == null || prayerName == null) return -1;
        switch (prayerName.toLowerCase(Locale.ROOT)) {
            case "subuh": return toMinutesFromStr(info.subuh);
            case "dzuhur": return toMinutesFromStr(info.dzuhur);
            case "ashar": return toMinutesFromStr(info.ashar);
            case "maghrib": return toMinutesFromStr(info.maghrib);
            case "isya": return toMinutesFromStr(info.isya);
            default: return -1;
        }
    }

    /**
     * Memutar audio adzan Imam Makkah:
     * - Subuh: Sheikh Ali Ahmed Mullah (dengan lafadz Ash-shalatu khairum minan naum)
     * - Dzuhur/Ashar/Maghrib/Isya: Sheikh Esam Ali Khan (adzan standar tanpa kalimat subuh)
     */
    public static void playAdzan(Context context, String prayerName) {
        if (context == null) return;
        try {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                    mediaPlayer.release();
                } catch (Exception ignored) {}
                mediaPlayer = null;
            }

            // Pilih resource audio sesuai waktu shalat
            String resName = "adzan";
            if (prayerName != null && prayerName.equalsIgnoreCase("Subuh")) {
                int subuhRes = context.getResources().getIdentifier("adzan_subuh", "raw", context.getPackageName());
                if (subuhRes != 0) {
                    resName = "adzan_subuh";
                }
            }

            int resId = context.getResources().getIdentifier(resName, "raw", context.getPackageName());
            if (resId == 0) {
                resId = context.getResources().getIdentifier("adzan", "raw", context.getPackageName());
            }

            if (resId != 0) {
                // Jaga CPU tetap aktif selama adzan berbunyi
                acquireWakeLock(context, 5 * 60 * 1000); // Max 5 menit

                mediaPlayer = MediaPlayer.create(context.getApplicationContext(), resId);
                if (mediaPlayer != null) {
                    mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    mediaPlayer.setOnCompletionListener(mp -> {
                        try {
                            mp.release();
                        } catch (Exception ignored) {}
                        mediaPlayer = null;
                        releaseWakeLock();
                        Log.d(TAG, "Audio adzan selesai diputar.");
                    });
                    mediaPlayer.start();
                    Log.d(TAG, "Audio adzan " + prayerName + " (" + resName + ") berhasil diputar!");
                }
            } else {
                Log.w(TAG, "Resource audio adzan tidak ditemukan!");
                releaseWakeLock();
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal memutar audio adzan", e);
            releaseWakeLock();
        }
    }

    public static void playAdzan(Context context) {
        playAdzan(context, "Dzuhur");
    }

    private static synchronized void acquireWakeLock(Context context, long timeoutMs) {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvhome:adzan_player");
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(timeoutMs);
            }
        } catch (Exception e) {
            Log.w(TAG, "Gagal acquire WakeLock", e);
        }
    }

    private static synchronized void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Gagal release WakeLock", e);
        }
    }

    /**
     * Menjadwalkan alarm sistem (AlarmManager) tepat pada jam masuk shalat untuk setiap waktu shalat hari ini.
     * AlarmManager bekerja independen dan akan memutar adzan bahkan saat pengguna sedang membuka
     * YouTube, Netflix, ataupun screensaver.
     */
    public static void scheduleAlarms(Context context) {
        if (context == null) return;
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Calendar cal = Calendar.getInstance();
            PrayerInfo info = calculate(null, cal);
            long now = System.currentTimeMillis();

            int[] minutes = {
                toMinutesFromStr(info.subuh),
                toMinutesFromStr(info.dzuhur),
                toMinutesFromStr(info.ashar),
                toMinutesFromStr(info.maghrib),
                toMinutesFromStr(info.isya)
            };
            String[] names = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};

            for (int i = 0; i < names.length; i++) {
                int prayerMin = minutes[i];
                Calendar target = Calendar.getInstance();
                target.set(Calendar.HOUR_OF_DAY, prayerMin / 60);
                target.set(Calendar.MINUTE, prayerMin % 60);
                target.set(Calendar.SECOND, 0);
                target.set(Calendar.MILLISECOND, 0);

                long targetMillis = target.getTimeInMillis();
                // Jika waktu hari ini sudah terlewat, jadwalkan untuk besok
                if (targetMillis <= now) {
                    target.add(Calendar.DAY_OF_YEAR, 1);
                    targetMillis = target.getTimeInMillis();
                }

                Intent intent = new Intent(context, AdzanReceiver.class);
                intent.setAction(AdzanReceiver.ACTION_ADZAN);
                intent.putExtra("prayer_name", names[i]);

                PendingIntent pi = PendingIntent.getBroadcast(
                        context,
                        i + 1,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pi);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, targetMillis, pi);
                }
                Log.d(TAG, "Alarm adzan " + names[i] + " dijadwalkan pada: " + target.getTime());
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal menjadwalkan alarm adzan", e);
        }
    }

    private static double getHourAngle(double alt, double lat, double dec) {
        double rad = Math.PI / 180.0;
        double cosH = (Math.sin(alt * rad) - Math.sin(lat * rad) * Math.sin(dec * rad)) /
                      (Math.cos(lat * rad) * Math.cos(dec * rad));
        if (cosH > 1.0) return 0.0;
        if (cosH < -1.0) return 180.0;
        return Math.toDegrees(Math.acos(cosH));
    }

    private static int toMinutes(double hours) {
        int h = (int) Math.floor(hours);
        int m = (int) Math.floor((hours - h) * 60.0);
        return h * 60 + m;
    }

    private static int toMinutesFromStr(String timeStr) {
        if (timeStr == null || !timeStr.contains(":")) return 0;
        try {
            String[] p = timeStr.split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatMinutes(int minutes) {
        return String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static String formatTime(double hours) {
        int h = (int) Math.floor(hours);
        int m = (int) Math.floor((hours - h) * 60.0);
        return String.format(Locale.getDefault(), "%02d:%02d", h, m);
    }
}
