package com.speedoverlay.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the effective speed limit for the current road and time.
 *
 * Supports:
 *  - Plain maxspeed tags:        "100", "50 km/h", "30 mph", "NL:urban"
 *  - Conditional speed limits:   maxspeed:conditional = "130 @ (19:00-06:00)"
 *
 * Dutch variable speed limit example (A2/A4/A12 etc.):
 *   maxspeed            = 100
 *   maxspeed:conditional = 130 @ (19:00-06:00)
 *
 * At 21:00 → returns 130
 * At 14:00 → returns 100
 *
 * The conditional tag is evaluated against the device's local clock.
 * If no conditional matches, falls back to plain maxspeed.
 * Re-fetches every 100m of movement. Also re-evaluates conditionals
 * when time crosses a boundary (handled by OverlayService calling
 * fetchIfNeeded on every GPS tick — the time check is cheap).
 */
public class SpeedLimitFetcher {

    private static final String TAG = "SpeedLimitFetcher";
    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";
    private static final double REFETCH_DISTANCE_M = 100.0;

    // ── Inner data class ──────────────────────────────────────────────────
    /** Holds both tags for a road so we can re-evaluate time without re-fetching. */
    private static class RoadData {
        String maxspeed;            // plain tag e.g. "100"
        String maxspeedConditional; // conditional tag e.g. "130 @ (19:00-06:00)"
    }

    public interface Callback {
        /** Called on the main thread. null = limit unknown. */
        void onSpeedLimitResult(Integer speedLimitKmh);
    }

    private final ExecutorService executor   = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private double   lastFetchLat = 0;
    private double   lastFetchLon = 0;
    private RoadData cachedRoad   = null;
    private boolean  fetching     = false;

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Call on every GPS update. Fetches new data if moved >100m,
     * otherwise re-evaluates cached conditional against current time.
     */
    public void fetchIfNeeded(double lat, double lon, Callback callback) {
        // If we have cached road data, always re-evaluate time (cheap, on calling thread)
        if (cachedRoad != null) {
            double dist = distanceMeters(lat, lon, lastFetchLat, lastFetchLon);
            if (dist < REFETCH_DISTANCE_M) {
                // Same road, just re-check the time-based limit
                callback.onSpeedLimitResult(effectiveLimit(cachedRoad));
                return;
            }
        }

        if (fetching) return;

        fetching = true;
        lastFetchLat = lat;
        lastFetchLon = lon;

        executor.execute(() -> {
            RoadData road = queryOverpass(lat, lon);
            cachedRoad = road;
            fetching   = false;
            Integer limit = effectiveLimit(road);
            mainHandler.post(() -> callback.onSpeedLimitResult(limit));
        });
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ── Overpass query ────────────────────────────────────────────────────

    private RoadData queryOverpass(double lat, double lon) {
        // Request both maxspeed AND maxspeed:conditional in one query
        String query = "[out:json][timeout:8];"
                + "way(around:30," + lat + "," + lon + ")"
                + "[highway][~\"^maxspeed\"~\".\"];"
                + "out tags 1;";

        try {
            URL url = new URL(OVERPASS_URL + "?data=" +
                    java.net.URLEncoder.encode(query, "UTF-8"));

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setRequestProperty("User-Agent", "SpeedOverlayApp/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Overpass HTTP " + code);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            return parseRoadData(sb.toString());

        } catch (Exception e) {
            Log.e(TAG, "Overpass query failed: " + e.getMessage());
            return null;
        }
    }

    private RoadData parseRoadData(String json) {
        try {
            JSONObject root     = new JSONObject(json);
            JSONArray  elements = root.getJSONArray("elements");
            if (elements.length() == 0) return null;

            JSONObject tags = elements.getJSONObject(0).getJSONObject("tags");

            RoadData road = new RoadData();
            road.maxspeed            = tags.optString("maxspeed",             null);
            road.maxspeedConditional = tags.optString("maxspeed:conditional", null);

            Log.d(TAG, "maxspeed=" + road.maxspeed
                    + "  maxspeed:conditional=" + road.maxspeedConditional);
            return road;

        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
            return null;
        }
    }

    // ── Time-aware limit evaluation ───────────────────────────────────────

    /**
     * Returns the effective speed limit right now by:
     * 1. Checking each conditional rule against the current local time.
     * 2. If a rule matches → return its speed.
     * 3. If no rule matches → return the plain maxspeed.
     * 4. If neither exists → return null.
     */
    private Integer effectiveLimit(RoadData road) {
        if (road == null) return null;

        // Try conditional first
        if (road.maxspeedConditional != null) {
            Integer conditional = evaluateConditional(road.maxspeedConditional);
            if (conditional != null) {
                Log.d(TAG, "Using conditional limit: " + conditional);
                return conditional;
            }
        }

        // Fall back to plain maxspeed
        return parseSpeedString(road.maxspeed);
    }

    /**
     * Parses and evaluates a maxspeed:conditional tag value.
     *
     * Format (OSM conditional restrictions):
     *   "SPEED @ (CONDITION); SPEED @ (CONDITION); ..."
     *
     * Supported condition formats:
     *   19:00-06:00
     *   Mo-Fr 19:00-06:00
     *   Mo-Fr 19:00-06:00; Sa-Su 00:00-24:00
     *
     * Returns the speed of the FIRST matching rule, or null if none match.
     */
    private Integer evaluateConditional(String conditional) {
        if (conditional == null || conditional.isEmpty()) return null;

        // Split on ";" to get individual rules: "SPEED @ (CONDITION)"
        String[] rules = conditional.split(";");
        for (String rule : rules) {
            rule = rule.trim();
            // Split on "@" to separate speed from condition
            int atIdx = rule.indexOf('@');
            if (atIdx < 0) continue;

            String speedPart     = rule.substring(0, atIdx).trim();
            String conditionPart = rule.substring(atIdx + 1).trim();

            // Strip surrounding parentheses
            conditionPart = conditionPart.replaceAll("^\\(|\\)$", "").trim();

            Integer speed = parseSpeedString(speedPart);
            if (speed == null) continue;

            if (conditionMatches(conditionPart)) {
                return speed;
            }
        }
        return null;
    }

    /**
     * Evaluates a condition string against the current local time.
     *
     * Handles:
     *   "19:00-06:00"                          — time range only
     *   "Mo-Fr 19:00-06:00"                    — day range + time range
     *   "Mo-Fr 19:00-06:00; Sa-Su 00:00-24:00" — multiple sub-conditions
     */
    private boolean conditionMatches(String condition) {
        // Multiple sub-conditions separated by ";"
        for (String sub : condition.split(";")) {
            if (subConditionMatches(sub.trim())) return true;
        }
        return false;
    }

    private boolean subConditionMatches(String condition) {
        Calendar now = Calendar.getInstance(); // uses device local timezone
        int nowMin   = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int dow      = now.get(Calendar.DAY_OF_WEEK); // 1=Sun, 2=Mon … 7=Sat

        // Separate day part and time part
        // Pattern: optional "Mo-Fr " or "Sa-Su " prefix, then HH:MM-HH:MM
        String dayPart  = null;
        String timePart = condition;

        // Detect day range prefix (e.g. "Mo-Fr", "Sa-Su", "Mo-Su")
        Pattern dayPattern = Pattern.compile(
                "^(Mo|Tu|We|Th|Fr|Sa|Su)-(Mo|Tu|We|Th|Fr|Sa|Su)\\s+(.+)$",
                Pattern.CASE_INSENSITIVE);
        Matcher dayMatcher = dayPattern.matcher(condition);
        if (dayMatcher.matches()) {
            dayPart  = dayMatcher.group(1) + "-" + dayMatcher.group(2);
            timePart = dayMatcher.group(3).trim();
        }

        // Evaluate day range
        if (dayPart != null && !dayRangeMatches(dayPart, dow)) {
            return false;
        }

        // Evaluate time range
        return timeRangeMatches(timePart, nowMin);
    }

    /**
     * Day range: "Mo-Fr", "Sa-Su", "Mo-Su", etc.
     * Calendar.DAY_OF_WEEK: 1=Sun, 2=Mon, 3=Tue, 4=Wed, 5=Thu, 6=Fri, 7=Sat
     */
    private boolean dayRangeMatches(String range, int dow) {
        String[] parts = range.split("-");
        if (parts.length != 2) return true; // can't parse, assume matches

        int start = dayNameToInt(parts[0].trim());
        int end   = dayNameToInt(parts[1].trim());
        if (start < 0 || end < 0) return true;

        if (start <= end) {
            return dow >= start && dow <= end;
        } else {
            // Wraps around Sunday (e.g. Fr-Mo)
            return dow >= start || dow <= end;
        }
    }

    /** Returns Calendar.DAY_OF_WEEK value for OSM day abbreviation. */
    private int dayNameToInt(String day) {
        switch (day.toLowerCase()) {
            case "mo": return 2;
            case "tu": return 3;
            case "we": return 4;
            case "th": return 5;
            case "fr": return 6;
            case "sa": return 7;
            case "su": return 1;
            default:   return -1;
        }
    }

    /**
     * Time range: "19:00-06:00" (supports overnight ranges that cross midnight).
     * nowMin = current time in minutes since midnight.
     */
    private boolean timeRangeMatches(String range, int nowMin) {
        Pattern p = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})");
        Matcher m = p.matcher(range);
        if (!m.find()) return false;

        int startMin = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        int endMin   = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));

        // Handle "00:00-24:00" meaning all day
        if (startMin == 0 && endMin == 24 * 60) return true;

        if (startMin < endMin) {
            // Normal range e.g. 06:00-22:00
            return nowMin >= startMin && nowMin < endMin;
        } else {
            // Overnight range e.g. 19:00-06:00
            return nowMin >= startMin || nowMin < endMin;
        }
    }

    // ── Speed string parser ───────────────────────────────────────────────

    /**
     * Parses maxspeed tag values into km/h integer.
     * Handles: "50", "50 km/h", "30 mph", "NL:motorway", "NL:urban", etc.
     */
    private Integer parseSpeedString(String value) {
        if (value == null || value.isEmpty()) return null;
        value = value.trim().toLowerCase();

        // Country:zone implicit limits
        if (value.contains("motorway"))  return 130;
        if (value.contains("rural"))     return 80;
        if (value.contains("urban"))     return 50;
        if (value.contains("living"))    return 15;
        if (value.contains("walk"))      return 7;
        if (value.equals("none"))        return null;

        // Strip unit suffixes
        value = value.replace("km/h", "").trim();

        if (value.contains("mph")) {
            value = value.replace("mph", "").trim();
            try {
                return (int) Math.round(Double.parseDouble(value) * 1.60934);
            } catch (NumberFormatException e) { return null; }
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Haversine distance ────────────────────────────────────────────────

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
