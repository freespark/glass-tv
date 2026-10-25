package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Well-known verses of the Qur'an for the top shelf, in place of the picture's place name and lines
 * ("quran_verses": true). The text ships in the APK (res/raw/quran_verses.txt, with its sources), so
 * nothing is fetched. A new picture brings the next verse; the turn is remembered across restarts.
 */
final class QuranVerses {

    private static final String TAG = "TvHome.Quran";
    private static List<String[]> verses;

    private QuranVerses() {
    }

    /** {reference, Arabic, translation} for the next verse in turn, or null if the list cannot be read. */
    static String[] next(Context ctx) {
        List<String[]> all = all(ctx);
        if (all.isEmpty()) return null;
        SharedPreferences p = ctx.getSharedPreferences("quran", Context.MODE_PRIVATE);
        int i = Math.floorMod(p.getInt("index", -1) + 1, all.size());
        p.edit().putInt("index", i).apply();
        return all.get(i);
    }

    private static synchronized List<String[]> all(Context ctx) {
        if (verses != null) return verses;
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getResources().openRawResource(R.raw.quran_verses), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                if (f.length == 3) out.add(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "verses unreadable: " + e);
        }
        verses = out;
        return out;
    }
}
