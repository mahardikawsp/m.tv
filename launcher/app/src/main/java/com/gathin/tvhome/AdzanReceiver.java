package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Menerima alarm waktu shalat tepat pada detiknya, memutar suara adzan,
 * dan menjadwalkan ulang alarm shalat berikutnya.
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

            // Pastikan CPU tetap terjaga beberapa detik agar audio mulai diputar
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = null;
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvhome:adzan_wakelock");
                wakeLock.acquire(15000); // 15 detik
            }

            try {
                // Tampilkan pemberitahuan singkat di layar TV
                Toast.makeText(context, "🕌 Masuk Waktu " + prayerName, Toast.LENGTH_LONG).show();

                // Putar suara adzan
                PrayerManager.playAdzan(context);
            } catch (Exception e) {
                Log.e(TAG, "Error saat memainkan adzan", e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }

            // Jadwalkan waktu sholat berikutnya
            PrayerManager.scheduleAlarms(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            // Jadwalkan ulang saat TV menyala atau waktu berubah
            PrayerManager.scheduleAlarms(context);
        }
    }
}
