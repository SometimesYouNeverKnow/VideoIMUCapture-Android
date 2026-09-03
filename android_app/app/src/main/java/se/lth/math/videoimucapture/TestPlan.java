package se.lth.math.videoimucapture;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A capture matrix the operator can shoot in one outing, one button per cell.
 *
 * WHY THIS EXISTS. Every capture-side question this fork has asked is a COMPARISON: manual
 * shutter against auto, portrait against landscape, OIS on against off. Answering one means two
 * clips that differ in exactly one thing, and until now the second variable was always the
 * operator's memory -- which settings were on, how long the walk was, whether the route matched.
 * The settings were being flipped over adb between clips, which means the phone had to be
 * tethered, which means the walk was whatever fits on a USB cable.
 *
 * So each step here carries its own settings, its own duration, and its own one-line instruction
 * for the half a test that software cannot set: how to hold the phone. The app applies the
 * settings, counts down, records for a FIXED time and stops itself. Two clips from this list
 * differ in what the list says they differ in, and their names say which cell they are.
 *
 * The steps are deliberately short and paired. A pair that cannot be walked back to back in one
 * outing is a pair that will be compared across two different days, two different lights and two
 * different routes, and that comparison answers nothing.
 */
public final class TestPlan {

    /** One cell of the matrix. */
    public static final class Step {
        public final String id;            // goes in the directory name: test<id>_...
        public final String title;         // what the button says
        public final String instruction;   // the part the app cannot set: how to hold, what to do
        public final int seconds;          // fixed, so two clips are the same length
        public final Map<String, Object> prefs;   // applied before recording, restored after

        Step(String id, String title, String instruction, int seconds, Map<String, Object> prefs) {
            this.id = id;
            this.title = title;
            this.instruction = instruction;
            this.seconds = seconds;
            this.prefs = prefs;
        }
    }

    private static Map<String, Object> prefs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final String WALK =
            "Walk the same route at the same pace as the other steps. A square around the room, "
                    + "rounded corners, is a good one: the straights and the corners give two "
                    + "different rotation rates in one clip.";

    /**
     * The matrix as of 2026-09-03. A, B, C, D are a 2x2 of hold against shutter -- the two
     * questions open right now (#38's manual shutter, and #47's rolling-shutter direction).
     * E and F each add one variable to B.
     */
    public static List<Step> steps() {
        List<Step> out = new ArrayList<>();

        out.add(new Step("A", "A - portrait, auto exposure",
                "Hold the phone UPRIGHT (portrait), camera roughly level.\n\n" + WALK
                        + "\n\nThis is the control: what the app did before today.",
                30, prefs("blur_budget_manual", false, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        out.add(new Step("B", "B - portrait, manual shutter",
                "Hold the phone UPRIGHT (portrait), camera roughly level.\n\n" + WALK
                        + "\n\nSame as A with the shutter capped from the gyro. Against A this "
                        + "says what the manual shutter costs and buys.",
                30, prefs("blur_budget_manual", true, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        out.add(new Step("C", "C - landscape, auto exposure",
                "Hold the phone SIDEWAYS (landscape), camera roughly level.\n\n" + WALK
                        + "\n\nAgainst A: does the hold change anything when the shutter does not?",
                30, prefs("blur_budget_manual", false, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        out.add(new Step("D", "D - landscape, manual shutter",
                "Hold the phone SIDEWAYS (landscape), camera roughly level.\n\n" + WALK
                        + "\n\nThe fourth corner of the square. B vs D is the rolling-shutter "
                        + "question (#47): the readout direction turns with the phone, the "
                        + "panning does not.",
                30, prefs("blur_budget_manual", true, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        out.add(new Step("E", "E - portrait, manual shutter, OIS ON",
                "Hold the phone UPRIGHT (portrait), camera roughly level.\n\n" + WALK
                        + "\n\nOIS on. The file already records what the HAL SAYS about OIS; "
                        + "against B this says what the lens actually DID, by comparing image "
                        + "motion with the gyro that should predict it.",
                30, prefs("blur_budget_manual", true, "lock_radiometry", false,
                        "ois", true, "ois_data", true)));

        out.add(new Step("F", "F - exposure keys, standing still",
                "Stand still, phone UPRIGHT, pointed at something with both bright and dark in "
                        + "it.\n\nPress VOLUME UP three times at about 5 seconds, then VOLUME "
                        + "DOWN six times at about 15 seconds.\n\nThe readout should show EV and "
                        + "the system volume bar should NOT appear. No walking: the point is the "
                        + "exposure steps, and motion would hide them.",
                25, prefs("blur_budget_manual", false, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        // Z1 and Z2 are a PAIR and only mean anything together: same scene, same position, one
        // setting different. They answer ReconStab #48, which is currently blocking any absolute
        // blur, shear or focal number this repo produces.
        //
        // The question: at zoom_ratio 0.6 every frame reports SCALER_CROP_REGION (815,611)-
        // (3263,2447) -- exactly 0.6 of the array -- while at 1.0 it reports the full 4080x3060.
        // So the crop tracks the request. What is NOT known is whether the recorded stream
        // actually honours it. If it does, the 0.6 clip is a 1.667x zoom IN and its focal is
        // 4629 px; if the metadata is bookkeeping the HAL then ignores, the focal is 2778 and
        // every solve initialised at 4604 has been 67% wrong. Two clips of the same wall settle
        // it in one look: either Z2 is tighter than Z1 or it is not.
        String zoomShot = "Point the phone at something with detail across the WHOLE frame — a "
                + "bookshelf, a cluttered bench, a brick wall — from about two metres.\n\nHold as "
                + "still as you can and DO NOT MOVE BETWEEN Z1 AND Z2. Shoot them back to back "
                + "from the same spot; if the phone moves, the pair is worthless.";
        out.add(new Step("Z1", "Z1 - zoom check, ratio 1.0",
                zoomShot + "\n\nThis one at zoom 1.0 — the full sensor field.",
                12, prefs("zoom_ratio", 1.0f, "blur_budget_manual", false,
                        "lock_radiometry", false, "ois", false, "ois_data", false)));
        out.add(new Step("Z2", "Z2 - zoom check, ratio 0.6",
                zoomShot + "\n\nThis one at zoom 0.6 — your usual setting. If it looks TIGHTER "
                        + "than Z1, the crop is real.",
                12, prefs("zoom_ratio", 0.6f, "blur_budget_manual", false,
                        "lock_radiometry", false, "ois", false, "ois_data", false)));

        return out;
    }

    /** Apply a step's settings, returning the previous values so they can be put back. */
    public static Map<String, Object> apply(SharedPreferences sp, Step step) {
        Map<String, Object> previous = new LinkedHashMap<>();
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Object> e : step.prefs.entrySet()) {
            Object want = e.getValue();
            if (want instanceof Boolean) {
                previous.put(e.getKey(), sp.getBoolean(e.getKey(), false));
                ed.putBoolean(e.getKey(), (Boolean) want);
            } else if (want instanceof Integer) {
                previous.put(e.getKey(), sp.getInt(e.getKey(), 0));
                ed.putInt(e.getKey(), (Integer) want);
            } else if (want instanceof Float) {
                previous.put(e.getKey(), sp.getFloat(e.getKey(), 1.0f));
                ed.putFloat(e.getKey(), (Float) want);
            } else if (want instanceof String) {
                previous.put(e.getKey(), sp.getString(e.getKey(), ""));
                ed.putString(e.getKey(), (String) want);
            }
        }
        ed.apply();
        return previous;
    }

    /** Put back what {@link #apply} displaced. The operator's own settings are not ours to keep. */
    public static void restore(SharedPreferences sp, Map<String, Object> previous) {
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Object> e : previous.entrySet()) {
            Object was = e.getValue();
            if (was instanceof Boolean) {
                ed.putBoolean(e.getKey(), (Boolean) was);
            } else if (was instanceof Integer) {
                ed.putInt(e.getKey(), (Integer) was);
            } else if (was instanceof Float) {
                ed.putFloat(e.getKey(), (Float) was);
            } else if (was instanceof String) {
                ed.putString(e.getKey(), (String) was);
            }
        }
        ed.apply();
    }

    private TestPlan() {
    }
}
