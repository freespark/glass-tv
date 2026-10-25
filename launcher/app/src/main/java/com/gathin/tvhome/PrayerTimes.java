package com.gathin.tvhome;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Today's prayer times, worked out on the TV from the sun's position: no network, nothing looked up.
 * Indonesia's Ministry of Religious Affairs (Kemenag) method: Subuh at the sun 20 degrees below the horizon,
 * Isya at 18, Asar when a shadow is its object's length plus the noon shadow (Shafi'i), and 2 minutes of
 * ihtiyat (safety margin) added to every time, rounded up. Terbit, sunrise, ends Subuh, so its margin is
 * taken off and it rounds down; Imsak is 10 minutes before Subuh. The astronomy is PrayTimes.org's, within
 * a minute.
 */
final class PrayerTimes {

    static final String[] NAMES = {"Imsak", "Subuh", "Terbit", "Zuhur", "Asar", "Magrib", "Isya"};
    private static final double FAJR_ANGLE = 20, ISHA_ANGLE = 18, SUNSET_ANGLE = 0.833;
    private static final int IHTIYAT_MIN = 2, IMSAK_MIN = 10;

    private PrayerTimes() {
    }

    /** The line under the clock, the next prayer in white and the rest dimmed; after Isya, tomorrow's. */
    static CharSequence line(double lat, double lon) {
        int[] u = upcoming(lat, lon);
        int next = u[0];
        int[] t = java.util.Arrays.copyOfRange(u, 2, u.length);
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (int i = 0; i < t.length; i++) {
            if (i > 0) b.append("   ");
            int start = b.length();
            b.append(NAMES[i]).append(' ').append(t[i] < 0 ? "--:--"
                    : String.format(Locale.ROOT, "%02d:%02d", t[i] / 60, t[i] % 60));
            if (i == next) {
                b.setSpan(new ForegroundColorSpan(0xFFFFFFFF), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new StyleSpan(Typeface.BOLD), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return b;
    }

    /** What comes next, e.g. "Magrib · 1 jam 23 menit lagi": the same time {@link #line} brightens. */
    static String countdown(double lat, double lon) {
        int[] u = upcoming(lat, lon);
        int left = u[1], h = left / 60, m = left % 60;
        String in = left < 1 ? "kurang dari 1 menit" : h == 0 ? m + " menit" : m == 0 ? h + " jam" : h + " jam " + m + " menit";
        return NAMES[u[0]] + " · " + in + " lagi";
    }

    /** {index of the next time, minutes until it, then that day's times}; after Isya, tomorrow's. */
    private static int[] upcoming(double lat, double lon) {
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int[] t = minutes(now, lat, lon);
        int next = 0;
        while (next < t.length && t[next] >= 0 && t[next] <= nowMin) next++;
        int left;
        if (next == t.length) {
            now.add(Calendar.DAY_OF_MONTH, 1);
            t = minutes(now, lat, lon);
            next = 0;
            left = t[0] + 1440 - nowMin;
        } else {
            left = t[next] - nowMin;
        }
        int[] u = new int[t.length + 2];
        u[0] = next;
        u[1] = left;
        System.arraycopy(t, 0, u, 2, t.length);
        return u;
    }

    private static int[] minutes(Calendar day, double lat, double lon) {
        TimeZone tz = day.getTimeZone();
        double tzHours = tz.getOffset(day.getTimeInMillis()) / 3_600_000.0;
        return minutes(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH), lat, lon, tzHours);
    }

    /** Minutes after local midnight for each of {@link #NAMES}; -1 where the sun never gets there. */
    static int[] minutes(int year, int month, int day, double lat, double lon, double tzHours) {
        double jd = julian(year, month, day) - lon / (15 * 24);
        // Each time is worked out with the sun where it is near that time of day, as PrayTimes does.
        double[] h = {
                angleTime(jd, lat, FAJR_ANGLE, 5, true),
                angleTime(jd, lat, SUNSET_ANGLE, 6, true),
                noon(jd, 12),
                asrTime(jd, lat, 13),
                angleTime(jd, lat, SUNSET_ANGLE, 18, false),
                angleTime(jd, lat, ISHA_ANGLE, 18, false)};
        int[] m = new int[h.length];
        for (int i = 0; i < h.length; i++) {
            if (Double.isNaN(h[i])) {
                m[i] = -1;
                continue;
            }
            double min = (h[i] + tzHours - lon / 15) * 60;
            m[i] = i == 1 ? (int) Math.floor(min + 1e-9) - IHTIYAT_MIN : (int) Math.ceil(min - 1e-9) + IHTIYAT_MIN;
            m[i] = (m[i] + 1440) % 1440;
        }
        int imsak = m[0] < 0 ? -1 : (m[0] - IMSAK_MIN + 1440) % 1440;
        return new int[]{imsak, m[0], m[1], m[2], m[3], m[4], m[5]};
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) {
            y--;
            m += 12;
        }
        double a = Math.floor(y / 100.0), b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** {declination in degrees, equation of time in hours} at Julian day {@code jd}. */
    private static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * d, 360), q = fix(280.459 + 0.98564736 * d, 360);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360), e = 23.439 - 0.00000036 * d;
        double ra = Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15;
        return new double[]{Math.toDegrees(Math.asin(sin(e) * sin(l))), q / 15 - fix(ra, 24)};
    }

    private static double noon(double jd, double hour) {
        return fix(12 - sun(jd + hour / 24)[1], 24);
    }

    private static double angleTime(double jd, double lat, double angle, double hour, boolean beforeNoon) {
        double decl = sun(jd + hour / 24)[0];
        double c = (-sin(angle) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat));
        if (c < -1 || c > 1) return Double.NaN;
        double t = Math.toDegrees(Math.acos(c)) / 15;
        return noon(jd, hour) + (beforeNoon ? -t : t);
    }

    private static double asrTime(double jd, double lat, double hour) {
        double decl = sun(jd + hour / 24)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        return angleTime(jd, lat, angle, hour, false);
    }

    private static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    private static double cos(double deg) {
        return Math.cos(Math.toRadians(deg));
    }

    private static double fix(double a, double range) {
        a -= range * Math.floor(a / range);
        return a < 0 ? a + range : a;
    }
}
