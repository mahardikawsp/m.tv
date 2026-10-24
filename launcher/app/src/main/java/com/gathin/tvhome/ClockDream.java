package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Screensaver: jam + jadwal shalat Medokan Ayu, Rungkut, Surabaya di atas foto bergantian (gurun arab / padang hijau).
 * Wallpaper berganti setiap 20 menit via crossfade 2 detik. Waktu shalat diperbarui tiap menit.
 * Hemat GPU: tidak ada animasi terus-menerus selain CrossFade berkala.
 */
public class ClockDream extends DreamService {

    /** Interval ganti wallpaper: 20 menit */
    private static final long WALLPAPER_INTERVAL_MS = 20 * 60 * 1000L;
    /** Durasi crossfade antar foto: 2 detik */
    private static final long CROSSFADE_DURATION_MS = 2_000L;
    /** Interval update waktu shalat: 1 menit */
    private static final long PRAYER_INTERVAL_MS = 60_000L;

    private ClockScene scene;
    private Handler handler;

    private ImageView wallpaperA, wallpaperB;
    private TextView dreamPrayerCountdown, dreamPrayerSchedule;

    /** true = A terlihat (B akan fade-in next), false = B terlihat (A akan fade-in next) */
    private boolean showingA = true;

    private final Runnable wallpaperTick = new Runnable() {
        @Override public void run() {
            crossfadeWallpaper();
            handler.postDelayed(this, WALLPAPER_INTERVAL_MS);
        }
    };

    private final Runnable prayerTick = new Runnable() {
        @Override public void run() {
            updatePrayer();
            handler.postDelayed(this, PRAYER_INTERVAL_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        handler = new Handler(Looper.getMainLooper());

        View root = getWindow().getDecorView();
        scene = new ClockScene(root);

        wallpaperA = root.findViewById(R.id.dreamWallpaperA);
        wallpaperB = root.findViewById(R.id.dreamWallpaperB);
        dreamPrayerCountdown = root.findViewById(R.id.dreamPrayerCountdown);
        dreamPrayerSchedule  = root.findViewById(R.id.dreamPrayerSchedule);

        // Mulai dengan wallpaper A terlihat, B transparan
        if (wallpaperA != null) wallpaperA.setAlpha(1f);
        if (wallpaperB != null) wallpaperB.setAlpha(0f);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
        // Update prayer langsung, lalu tiap menit
        updatePrayer();
        handler.postDelayed(prayerTick, PRAYER_INTERVAL_MS);
        // Ganti wallpaper pertama setelah 20 menit
        handler.postDelayed(wallpaperTick, WALLPAPER_INTERVAL_MS);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(wallpaperTick);
        handler.removeCallbacks(prayerTick);
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }

    // ---- Crossfade wallpaper ----------------------------------------------------------------

    private void crossfadeWallpaper() {
        if (wallpaperA == null || wallpaperB == null) return;

        if (showingA) {
            // A terlihat → fade-in B, fade-out A
            ObjectAnimator inB = ObjectAnimator.ofFloat(wallpaperB, "alpha", 0f, 1f);
            inB.setDuration(CROSSFADE_DURATION_MS);
            ObjectAnimator outA = ObjectAnimator.ofFloat(wallpaperA, "alpha", 1f, 0f);
            outA.setDuration(CROSSFADE_DURATION_MS);
            inB.start();
            outA.start();
            showingA = false;
        } else {
            // B terlihat → fade-in A, fade-out B
            ObjectAnimator inA = ObjectAnimator.ofFloat(wallpaperA, "alpha", 0f, 1f);
            inA.setDuration(CROSSFADE_DURATION_MS);
            ObjectAnimator outB = ObjectAnimator.ofFloat(wallpaperB, "alpha", 1f, 0f);
            outB.setDuration(CROSSFADE_DURATION_MS);
            inA.start();
            outB.start();
            showingA = true;
        }
    }

    // ---- Prayer times update ----------------------------------------------------------------

    private void updatePrayer() {
        if (dreamPrayerCountdown == null) return;
        Calendar cal = Calendar.getInstance();
        PrayerManager.PrayerInfo info = PrayerManager.calculate(getApplicationContext(), cal);
        dreamPrayerCountdown.setText("\uD83D\uDD54 " + info.countdownText);
        if (dreamPrayerSchedule != null) {
            dreamPrayerSchedule.setText(
                    "Subuh " + info.subuh + "   \u2022   " +
                    "Dzuhur " + info.dzuhur + "   \u2022   " +
                    "Ashar " + info.ashar + "   \u2022   " +
                    "Maghrib " + info.maghrib + "   \u2022   " +
                    "Isya " + info.isya
            );
        }
    }
}

