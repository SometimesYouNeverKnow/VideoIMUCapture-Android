package se.lth.math.videoimucapture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only window onto the film roll for the one app allowed to look through it.
 *
 * Recordings live in this app's private external files dir, which no other app can read
 * on Android 11+ -- not even through the folder picker. EpochRift Pocket needs the roll
 * to queue solves, so this hands it out on purpose, and only to a caller holding
 * {@code se.lth.math.videoimucapture.permission.READ_ROLL}. That permission is
 * signature-level: it is granted only to an app signed with this app's key, which keeps
 * the roll (video, GPS in the metadata) away from anything else on the phone.
 *
 * <pre>
 *   content://se.lth.math.videoimucapture.roll/sessions                       query: one row per session
 *   content://se.lth.math.videoimucapture.roll/sessions/{name}/thumb          openFile: JPEG
 *   content://se.lth.math.videoimucapture.roll/sessions/{name}/files/{file}   openFile: read-only
 * </pre>
 *
 * Nothing here takes a path from the caller: {@code name} must be a directory directly
 * under the roll root and {@code file} a plain file directly under it.
 */
public final class RollProvider extends ContentProvider {
    public static final String AUTHORITY = "se.lth.math.videoimucapture.roll";

    public static final String COL_NAME = "name";
    public static final String COL_MODE = "mode";
    public static final String COL_KIND = "kind";
    public static final String COL_WHEN = "when_text";
    public static final String COL_BYTES = "bytes";
    public static final String COL_VIDEO_MS = "video_ms";
    public static final String COL_STILLS = "stills";
    public static final String COL_STEREO_PAIRS = "stereo_pairs";
    public static final String COL_DNGS = "dngs";
    public static final String COL_HAS_VIDEO = "has_video";
    public static final String COL_HAS_META = "has_meta";
    public static final String COL_WARNING = "warning";

    static final String[] COLUMNS = {
            COL_NAME, COL_MODE, COL_KIND, COL_WHEN, COL_BYTES, COL_VIDEO_MS, COL_STILLS,
            COL_STEREO_PAIRS, COL_DNGS, COL_HAS_VIDEO, COL_HAS_META, COL_WARNING,
    };

    private static final int SESSIONS = 1;
    private static final int THUMB = 2;
    private static final int FILE = 3;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "sessions", SESSIONS);
        MATCHER.addURI(AUTHORITY, "sessions/*/thumb", THUMB);
        MATCHER.addURI(AUTHORITY, "sessions/*/files/*", FILE);
    }

    private Thumbs mThumbs;

    @Override
    public boolean onCreate() {
        mThumbs = new Thumbs(getContext());
        return true;
    }

    private File root() {
        return getContext().getExternalFilesDir(null);
    }

    /** A session directory, or null unless {@code name} is exactly one directory under the root. */
    @Nullable
    private File sessionDir(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) {
            return null;
        }
        File dir = new File(root(), name);
        return dir.isDirectory() ? dir : null;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        if (MATCHER.match(uri) != SESSIONS) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        File[] dirs = root().listFiles(File::isDirectory);
        List<SessionSummary> all = new ArrayList<>();
        if (dirs != null) {
            for (File d : dirs) {
                all.add(SessionSummary.quick(d));
            }
        }
        // Newest first, like the roll: the directory name carries the stamp.
        all.sort((a, b) -> b.name.compareTo(a.name));
        MatrixCursor cursor = new MatrixCursor(COLUMNS, all.size());
        for (SessionSummary s : all) {
            cursor.addRow(new Object[]{
                    s.name, s.mode, s.kind, s.when, s.bytes, s.videoMs, s.stills.size(),
                    s.stereoPairs, s.dngs, s.video != null ? 1 : 0, s.meta != null ? 1 : 0,
                    s.receiptWarning,
            });
        }
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("The roll is read-only");
        }
        List<String> segments = uri.getPathSegments();
        switch (MATCHER.match(uri)) {
            case THUMB: {
                File dir = sessionDir(segments.get(1));
                if (dir == null) {
                    throw new FileNotFoundException("No such session");
                }
                SessionSummary s = SessionSummary.quick(dir);
                File source = s.video != null ? s.video : (s.stills.isEmpty() ? null : s.stills.get(0));
                if (source == null) {
                    throw new FileNotFoundException("Session has nothing to thumbnail");
                }
                File thumb = mThumbs.cachedFile(source, s.name);
                if (thumb == null) {
                    throw new FileNotFoundException("Thumbnail could not be made");
                }
                return ParcelFileDescriptor.open(thumb, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            case FILE: {
                File dir = sessionDir(segments.get(1));
                String name = segments.get(3);
                if (dir == null || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
                    throw new FileNotFoundException("No such file");
                }
                File f = new File(dir, name);
                if (!f.isFile()) {
                    throw new FileNotFoundException("No such file");
                }
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            default:
                throw new FileNotFoundException("Unknown URI " + uri);
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        switch (MATCHER.match(uri)) {
            case SESSIONS:
                return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".session";
            case THUMB:
                return "image/jpeg";
            case FILE: {
                String name = uri.getLastPathSegment();
                if (name == null) {
                    return null;
                }
                if (name.endsWith(".mp4")) return "video/mp4";
                if (name.endsWith(".jpg")) return "image/jpeg";
                if (name.endsWith(".dng")) return "image/x-adobe-dng";
                if (name.endsWith(".json")) return "application/json";
                return "application/octet-stream";
            }
            default:
                return null;
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("The roll is read-only");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("The roll is read-only");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("The roll is read-only");
    }
}
