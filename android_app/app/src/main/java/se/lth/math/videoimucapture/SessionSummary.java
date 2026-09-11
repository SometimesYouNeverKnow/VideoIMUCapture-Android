package se.lth.math.videoimucapture;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What one capture directory holds, cheaply enough to list dozens and honestly enough to trust.
 *
 * Two levels. {@link #quick} touches only the directory listing and the mp4 header: mode, time,
 * duration, how many stills and pairs, bytes. {@link #streams} walks the metadata file once,
 * counting each stream and noting its first and last stamp and its worst gap -- which is what
 * says whether the IMU was actually running, whether GNSS ever fixed, whether the stereo pairs
 * landed. ReconStab #15: the first time anyone learned a capture had failed used to be at a
 * desk, days later. This is the field-side check.
 *
 * The metadata file is a concatenation of VideoCaptureData messages (see RecordingWriter), so it
 * is read at the top level with a CodedInputStream and only the small sub-messages are decoded;
 * the IMU stream is decoded for its stamps but never held.
 */
public final class SessionSummary {
    private static final String TAG = "SessionSummary";

    public final File dir;
    public final String name;          // directory name
    public final String mode;          // WALK, OBJECT, PANO, STILLS, OTHER
    public final String kind;          // "video", "stills run", "test S2", ...
    public final String when;          // "2026-09-10 20:09"
    public final long bytes;
    public final File video;           // may be null
    public final File meta;            // may be null
    public final long videoMs;         // 0 if none or unreadable
    public final List<File> stills;    // every jpg/dng, sorted by name
    public final int stereoPairs;      // stereo_*_uw.jpg with a matching _main.jpg
    public final int dngs;

    private SessionSummary(File dir, String mode, String kind, String when, long bytes,
                           File video, File meta, long videoMs, List<File> stills,
                           int stereoPairs, int dngs) {
        this.dir = dir;
        this.name = dir.getName();
        this.mode = mode;
        this.kind = kind;
        this.when = when;
        this.bytes = bytes;
        this.video = video;
        this.meta = meta;
        this.videoMs = videoMs;
        this.stills = stills;
        this.stereoPairs = stereoPairs;
        this.dngs = dngs;
    }

    /** The directory listing and the mp4 header. Milliseconds, not seconds. */
    public static SessionSummary quick(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            files = new File[0];
        }
        long bytes = 0;
        File video = null, meta = null;
        List<File> stills = new ArrayList<>();
        java.util.Set<String> uw = new java.util.HashSet<>(), main = new java.util.HashSet<>();
        int dngs = 0;
        for (File f : files) {
            bytes += f.length();
            String n = f.getName();
            if (n.equals("video_recording.mp4")) {
                video = f;
            } else if (n.equals("video_meta.pb3")) {
                meta = f;
            } else if (n.endsWith(".jpg")) {
                stills.add(f);
                if (n.startsWith("stereo_")) {
                    String stem = n.substring(0, n.lastIndexOf('_'));
                    (n.endsWith("_uw.jpg") ? uw : main).add(stem);
                }
            } else if (n.endsWith(".dng")) {
                stills.add(f);
                dngs++;
            }
        }
        java.util.Collections.sort(stills);
        int pairs = 0;
        for (String s : uw) {
            if (main.contains(s)) {
                pairs++;
            }
        }
        long videoMs = 0;
        if (video != null && video.length() > 0) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(video.getAbsolutePath());
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) {
                    videoMs = Long.parseLong(d);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "no duration for " + video + ": " + e);
            } finally {
                try {
                    r.release();
                } catch (IOException ignored) {
                }
            }
        }
        String[] parsed = parseName(dir.getName());
        return new SessionSummary(dir, parsed[0], parsed[1], parsed[2], bytes, video, meta,
                videoMs, stills, pairs, dngs);
    }

    /**
     * Directory names are "<prefix>_yyyy_MM_dd_HH_mm_ss", prefix one of walk, walk_vid, object,
     * object_vid, pano, pano_vid, stills, optionally led by "test<cell>_".
     *
     * @return {mode, kind, when}
     */
    static String[] parseName(String name) {
        String cell = null;
        String n = name;
        if (n.startsWith("test")) {
            int u = n.indexOf('_');
            if (u > 4) {
                cell = n.substring(4, u);
                n = n.substring(u + 1);
            }
        }
        // The stamp is the last six underscore-separated fields.
        String[] parts = n.split("_");
        String when = "";
        String prefix = n;
        if (parts.length >= 7) {
            int k = parts.length - 6;
            when = String.format(Locale.US, "%s-%s-%s %s:%s", parts[k], parts[k + 1],
                    parts[k + 2], parts[k + 3], parts[k + 4]);
            StringBuilder p = new StringBuilder(parts[0]);
            for (int i = 1; i < k; i++) {
                p.append('_').append(parts[i]);
            }
            prefix = p.toString();
        }
        String mode;
        if (prefix.startsWith("walk")) {
            mode = "WALK";
        } else if (prefix.startsWith("object")) {
            mode = "OBJECT";
        } else if (prefix.startsWith("pano")) {
            mode = "PANO";
        } else if (prefix.startsWith("stills")) {
            mode = "STILLS";
        } else {
            mode = "OTHER";
        }
        String kind = prefix.endsWith("_vid") ? "video"
                : (mode.equals("STILLS") ? "manual burst" : "stills run");
        if (cell != null) {
            kind = "test " + cell + " " + kind;
        }
        return new String[]{mode, kind, when};
    }

    public String sizeText() {
        if (bytes >= 1L << 30) {
            return String.format(Locale.US, "%.2f GB", bytes / (double) (1L << 30));
        }
        return String.format(Locale.US, "%.0f MB", bytes / (double) (1L << 20));
    }

    public String countsText() {
        StringBuilder b = new StringBuilder();
        if (video != null) {
            b.append(String.format(Locale.US, "video %.1f s", videoMs / 1000.0));
        }
        int loose = stills.size() - dngs - stereoPairs * 2;
        if (loose > 0) {
            b.append(b.length() > 0 ? " · " : "").append(loose).append(" stills");
        }
        if (dngs > 0) {
            b.append(b.length() > 0 ? " · " : "").append(dngs).append(" RAW");
        }
        if (stereoPairs > 0) {
            b.append(b.length() > 0 ? " · " : "").append(stereoPairs).append(" pairs");
        }
        if (b.length() == 0) {
            b.append(video == null && meta == null ? "EMPTY" : "no frames");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ the streams

    /** One stream's count, span and worst gap. */
    public static final class Stream {
        public final String label;
        public int count;
        public long firstNs = Long.MAX_VALUE, lastNs = Long.MIN_VALUE, maxGapNs, prevNs;

        Stream(String label) {
            this.label = label;
        }

        void stamp(long ns) {
            if (ns <= 0) {
                count++;
                return;
            }
            if (count > 0 && prevNs > 0) {
                maxGapNs = Math.max(maxGapNs, ns - prevNs);
            }
            prevNs = ns;
            firstNs = Math.min(firstNs, ns);
            lastNs = Math.max(lastNs, ns);
            count++;
        }

        public double spanS() {
            return count > 1 && lastNs > firstNs ? (lastNs - firstNs) / 1e9 : 0;
        }

        public String line() {
            if (count == 0) {
                return label + ": none";
            }
            String s = String.format(Locale.US, "%s: %d over %.1f s", label, count, spanS());
            if (spanS() > 0) {
                s += String.format(Locale.US, " (%.0f Hz", count / spanS());
                if (maxGapNs > 0) {
                    s += String.format(Locale.US, ", worst gap %.2f s", maxGapNs / 1e9);
                }
                s += ")";
            }
            return s;
        }
    }

    public static final class Streams {
        public final Stream frames = new Stream("frames");
        public final Stream imu = new Stream("IMU");
        public final Stream orientation = new Stream("orientation");
        public final Stream gnss = new Stream("GNSS fixes");
        public final Stream stills = new Stream("still rows");
        public final Stream thermal = new Stream("thermal");
        public int stereoRows;
        public float lastBatteryC = Float.NaN;
        public long exposureMinNs = Long.MAX_VALUE, exposureMaxNs = 0;
        public int isoMin = Integer.MAX_VALUE, isoMax = 0;
        public String error;

        public String text() {
            StringBuilder b = new StringBuilder();
            for (Stream s : new Stream[]{frames, imu, orientation, gnss, stills, thermal}) {
                b.append(s.line()).append('\n');
            }
            if (stereoRows > 0) {
                b.append("stereo rows: ").append(stereoRows).append('\n');
            }
            if (exposureMaxNs > 0) {
                b.append(String.format(Locale.US, "exposure %.2f-%.2f ms, ISO %d-%d\n",
                        exposureMinNs / 1e6, exposureMaxNs / 1e6, isoMin, isoMax));
            }
            if (!Float.isNaN(lastBatteryC)) {
                b.append(String.format(Locale.US, "battery %.1f °C at the end\n", lastBatteryC));
            }
            if (error != null) {
                b.append("READ ERROR: ").append(error).append('\n');
            }
            return b.toString().trim();
        }
    }

    /** Walk the metadata file. Background thread; a ten-minute walk is tens of MB. */
    public Streams streams() {
        Streams out = new Streams();
        if (meta == null) {
            out.error = "no video_meta.pb3";
            return out;
        }
        try (FileInputStream fs = new FileInputStream(meta);
             BufferedInputStream bs = new BufferedInputStream(fs, 1 << 16)) {
            CodedInputStream in = CodedInputStream.newInstance(bs);
            in.setSizeLimit(Integer.MAX_VALUE);
            while (!in.isAtEnd()) {
                int tag = in.readTag();
                if (tag == 0) {
                    break;
                }
                int field = WireFormat.getTagFieldNumber(tag);
                int wire = WireFormat.getTagWireType(tag);
                if (wire != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    in.skipField(tag);
                    continue;
                }
                switch (field) {
                    case 4: {   // imu
                        RecordingProtos.IMUData m = in.readMessage(RecordingProtos.IMUData.parser(),
                                com.google.protobuf.ExtensionRegistryLite.getEmptyRegistry());
                        out.imu.stamp(m.getTimeNs());
                        break;
                    }
                    case 5: {   // video_meta
                        RecordingProtos.VideoFrameMetaData m = in.readMessage(
                                RecordingProtos.VideoFrameMetaData.parser(),
                                com.google.protobuf.ExtensionRegistryLite.getEmptyRegistry());
                        out.frames.stamp(m.getTimeNs());
                        if (m.getExposureTimeNs() > 0) {
                            out.exposureMinNs = Math.min(out.exposureMinNs, m.getExposureTimeNs());
                            out.exposureMaxNs = Math.max(out.exposureMaxNs, m.getExposureTimeNs());
                        }
                        if (m.getIso() > 0) {
                            out.isoMin = Math.min(out.isoMin, m.getIso());
                            out.isoMax = Math.max(out.isoMax, m.getIso());
                        }
                        break;
                    }
                    case 8: {   // orientation
                        RecordingProtos.OrientationData m = in.readMessage(
                                RecordingProtos.OrientationData.parser(),
                                com.google.protobuf.ExtensionRegistryLite.getEmptyRegistry());
                        out.orientation.stamp(m.getTimeNs());
                        break;
                    }
                    case 9: {   // gnss
                        RecordingProtos.GnssData m = in.readMessage(RecordingProtos.GnssData.parser(),
                                com.google.protobuf.ExtensionRegistryLite.getEmptyRegistry());
                        out.gnss.stamp(m.getElapsedRealtimeNs());
                        break;
                    }
                    case 10: {  // stills
                        RecordingProtos.StillMetaData m = in.readMessage(
                                RecordingProtos.StillMetaData.parser(),
                                com.google.protobuf.ExtensionRegistryLite.getEmptyRegistry());
                        out.stills.stamp(m.getTimeNs());
                        if (m.getJpegFile().startsWith("stereo_")) {
                            out.stereoRows++;
                        }
                        break;
                    }
                    case 11: {  // thermal
                        RecordingProtos.ThermalData m = in.readMessage(
                                RecordingProtos.ThermalData.parser(),
                                com.google.protobuf.ExtensionRegistryLite.getEmptyRegistry());
                        out.thermal.stamp(m.getTimeNs());
                        if (m.getBatteryTempC() != 0f) {
                            out.lastBatteryC = m.getBatteryTempC();
                        }
                        break;
                    }
                    default:
                        in.skipField(tag);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "metadata walk failed for " + meta + ": " + e);
            out.error = e.toString();
        }
        return out;
    }

    /** Remove the whole directory. Returns false if anything survived. */
    public static boolean deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        return f.delete();
    }
}
