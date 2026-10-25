package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/**
 * Kemenag times for Medan (3.5952 N, 98.6722 E, WIB): Imsak, Subuh, Terbit, Zuhur, Asar, Magrib, Isya. Each
 * prayer is 2 to 3 minutes after the raw KEMENAG method on api.aladhan.com for the same day (the 2 minutes of
 * ihtiyat, plus rounding up), Terbit 2 to 3 minutes before its sunrise, and Imsak 10 minutes before Subuh.
 */
public class PrayerTimesTest {

    private static int[] hm(String... times) {
        int[] m = new int[times.length];
        for (int i = 0; i < times.length; i++) {
            String[] p = times[i].split(":");
            m[i] = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        }
        return m;
    }

    private static void medan(int y, int mo, int d, String... expected) {
        assertArrayEquals(y + "-" + mo + "-" + d, hm(expected), PrayerTimes.minutes(y, mo, d, 3.5952, 98.6722, 7));
    }

    @Test
    public void medanThroughTheYear() {
        // aladhan 04:51 sunrise 06:09 12:09 15:30 18:10 19:20
        medan(2026, 10, 25, "04:43", "04:53", "06:07", "12:12", "15:32", "18:12", "19:22");
        // aladhan 05:14 sunrise 06:37 12:35 15:58 18:33 19:46
        medan(2026, 1, 15, "05:07", "05:17", "06:34", "12:37", "16:01", "18:35", "19:49");
        // aladhan 04:53 sunrise 06:17 12:27 15:54 18:37 19:53
        medan(2026, 6, 21, "04:45", "04:55", "06:15", "12:30", "15:56", "18:40", "19:55");
        // aladhan 05:02 sunrise 06:26 12:23 15:47 18:21 19:36
        medan(2026, 12, 21, "04:55", "05:05", "06:23", "12:26", "15:50", "18:23", "19:38");
    }
}
