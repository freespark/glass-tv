package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: a grey analog clock (see {@link ClockFace}) with the time and a short date beneath.
 * It sits in the middle of the screen and every minute shifts a few pixels around the centre, so
 * nothing burns in while it still reads as centred. Cheap enough for a 1 GB TV.
 *
 * <p>With prayer_times in the theme it adds the next prayer with a countdown, and today's times. With
 * the theme's own pictures (wallpapers/ or wallpaper.jpg) it shows them behind, dimmed, a new one every
 * {@link #PHOTO_MS}; without, the plain grey. One picture in memory, decoded off the main thread.
 *
 * <p>Everything is sized from the screen's height, in the units the design was drawn in: a screen
 * 380 units tall.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    private static final long PHOTO_MS = 10 * 60_000L;
    private static final float SCREEN_UNITS = 380f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private ClockFace face;
    /** From the theme: where to work out prayer times, NaN for none. */
    private double prayerLat = Double.NaN, prayerLon = Double.NaN;
    private TextView next, prayers;
    /** The theme's pictures, the one on screen, and the thread that decodes the next. */
    private final List<File> photos = new ArrayList<>();
    private int photoIndex;
    private ImageView photo;
    private ExecutorService decoder;

    /** Redraws the prayer lines on each new minute of the clock. */
    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            updatePrayers();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000L - now % 60_000L + 50);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private final Runnable nextPhoto = new Runnable() {
        @Override
        public void run() {
            showPhoto(photoIndex + 1);
            handler.postDelayed(this, PHOTO_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        face = new ClockFace(this);
        ((ViewGroup) content).addView(face, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Config cfg = Config.load(this);
        prayerLat = cfg.prayerLat;
        prayerLon = cfg.prayerLon;
        next = findViewById(R.id.dreamNext);
        prayers = findViewById(R.id.dreamPrayers);
        boolean times = !Double.isNaN(prayerLat);
        for (View v : new View[]{next, prayers}) v.setVisibility(times ? View.VISIBLE : View.GONE);
        updatePrayers();
        if (!cfg.wallpapers.isEmpty()) photos.addAll(cfg.wallpapers);
        else if (cfg.wallpaper != null) photos.add(cfg.wallpaper);
        if (!photos.isEmpty()) {
            // Behind everything, and dimmed further than on Home: the clock must read from across the room.
            photo = new ImageView(this);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photo.setColorFilter(0xFF8C8C8C, PorterDuff.Mode.MULTIPLY);
            photo.setAlpha(0f);
            ((ViewGroup) content.getParent()).addView(photo, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            decoder = Executors.newSingleThreadExecutor();
            photoIndex = random.nextInt(photos.size()) - 1;
        }
        TextClock date = findViewById(R.id.dreamDate);
        // The month in short and the year (in capitals, see dream_clock.xml): OCT 21, 2026.
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMdyyyy");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        View screen = (View) content.getParent();
        screen.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) size(b - t);
        });
        // Re-centre once the clock has its size for this screen.
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) placeClock();
        });
    }

    /** Lays the clock out for a screen {@code height} pixels tall. */
    private void size(int height) {
        float u = height / SCREEN_UNITS;
        // With the prayer times under it, a smaller dial leaves the room for them.
        boolean more = !Double.isNaN(prayerLat);
        face.setRimRadius((more ? 64f : 83.6f) * u);
        TextView time = findViewById(R.id.dreamTime);
        TextView amPm = findViewById(R.id.dreamAmPm);
        TextView date = findViewById(R.id.dreamDate);
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, (more ? 40f : 44f) * u);
        amPm.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * u);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);

        // The tops of the digits sit 34 units under the rim, below the room the dial keeps for its shadow.
        LinearLayout.LayoutParams row = (LinearLayout.LayoutParams) findViewById(R.id.dreamTimeRow).getLayoutParams();
        row.topMargin = Math.round(34f * u - face.shadowRoom() - capInset(time, "0"));
        // AM/PM's capitals start where the digits do.
        LinearLayout.LayoutParams ampm = (LinearLayout.LayoutParams) amPm.getLayoutParams();
        ampm.leftMargin = Math.round(5f * u);
        amPm.setTranslationY(capInset(time, "0") - capInset(amPm, "PM"));
        // The date's capitals start 16.5 units under the digits' baseline.
        LinearLayout.LayoutParams dateRow = (LinearLayout.LayoutParams) date.getLayoutParams();
        dateRow.topMargin = Math.round(16.5f * u - time.getPaint().getFontMetrics().descent - capInset(date, "O"));
        content.requestLayout();
    }

    /** How far below the top of {@code view} the tops of {@code letters} are drawn. */
    static float capInset(TextView view, String letters) {
        Paint paint = view.getPaint();
        Rect bounds = new Rect();
        paint.getTextBounds(letters, 0, letters.length(), bounds);
        return bounds.top - paint.getFontMetrics().ascent;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        face.start();
        handler.postDelayed(tick, MOVE_MS);
        handler.post(minute);
        if (photo != null) handler.post(nextPhoto);
    }

    @Override
    public void onDreamingStopped() {
        stopAll();
        face.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopAll();
        if (decoder != null) decoder.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void stopAll() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(minute);
        handler.removeCallbacks(nextPhoto);
    }

    /** The countdown to the next prayer and today's times. */
    private void updatePrayers() {
        if (Double.isNaN(prayerLat)) return;
        next.setText(PrayerTimes.countdown(prayerLat, prayerLon));
        prayers.setText(PrayerTimes.line(prayerLat, prayerLon));
    }

    /** Fades out the picture on screen and in the {@code index}th, decoded at the screen's size. */
    private void showPhoto(int index) {
        photoIndex = Math.floorMod(index, photos.size());
        final File file = photos.get(photoIndex);
        final int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        decoder.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= w) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; a dimmed photo shows no banding
            final Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), o);
            if (bmp == null) return;
            handler.post(() -> {
                if (photo == null) return;
                photo.animate().cancel();
                photo.animate().alpha(0f).setDuration(photo.getAlpha() > 0f ? 900 : 0).withEndAction(() -> {
                    photo.setImageBitmap(bmp);
                    photo.animate().alpha(1f).setDuration(1500).start();
                }).start();
            });
        });
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** The middle of the screen, nudged up to 1.5 % either way. */
    private void placeClock() {
        View parent = (View) content.getParent();
        float nudgeX = parent.getWidth() * 0.015f * (2f * random.nextFloat() - 1f);
        float nudgeY = parent.getHeight() * 0.015f * (2f * random.nextFloat() - 1f);
        float x = (parent.getWidth() - content.getWidth()) / 2f + nudgeX;
        float y = (parent.getHeight() - content.getHeight()) / 2f + nudgeY;
        content.setX(x);
        content.setY(y);
    }
}
