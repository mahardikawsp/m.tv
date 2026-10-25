package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Menerima alarm waktu shalat, memvalidasi ketepatan jam masuk shalat,
 * memutar adzan yang sesuai (Subuh vs Reguler), dan menjadwalkan alarm berikutnya.
 */
public class AdzanReceiver extends BroadcastReceiver {
    private static final String TAG = "AdzanReceiver";
    public static final String ACTION_ADZAN = "com.gathin.tvhome.ACTION_ADZAN";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "AdzanReceiver onReceive: " + action);

        if (ACTION_ADZAN.equals(action)) {
            String prayerName = intent.getStringExtra("prayer_name");
            if (prayerName == null) prayerName = "Shalat";

            Log.d(TAG, "Menerima broadcast alarm adzan untuk: " + prayerName);

            // Validasi apakah jam saat ini benar-benar pas dengan waktu shalat tersebut
            // dan belum pernah diputar hari ini.
            boolean played = PrayerManager.playAdzanIfValid(context, prayerName);
            Log.d(TAG, "Hasil validasi adzan " + prayerName + ": " + (played ? "DIPUTAR" : "DIABAIKAN / TIDAK VALID"));

            // Jadwalkan ulang siklus alarm shalat berikutnya
            PrayerManager.scheduleAlarms(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            // Sinkronisasi dan jadwalkan ulang saat TV booting atau waktu berubah
            Log.d(TAG, "Waktu sistem berubah atau boot selesai, menjadwalkan ulang alarm shalat.");
            PrayerManager.scheduleAlarms(context);
        }
    }
}
