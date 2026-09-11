package se.lth.math.videoimucapture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small pictures of big files, made once and kept in the app's cache directory -- never inside a
 * capture directory, whose file count the desk-side census reads as data.
 *
 * A still is decoded at 1/8 (a 4080-wide JPEG lands at 510 px); a video is asked for the frame
 * one second in. Both happen on one background thread, and a view that has been recycled onto a
 * different file by the time its picture is ready is left alone.
 */
public final class Thumbs {
    private static final String TAG = "Thumbs";
    private static final int TARGET_PX = 512;

    private final File cacheDir;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(24 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "Thumbs"));
    private final Handler main = new Handler(Looper.getMainLooper());

    public Thumbs(Context ctx) {
        cacheDir = new File(ctx.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
    }

    /** Load a session's or a still's thumbnail into the view; the tag guards recycling. */
    public void into(ImageView view, File source, String key) {
        view.setTag(key);
        Bitmap hit = memory.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        worker.execute(() -> {
            Bitmap b = load(source, key);
            if (b == null) {
                return;
            }
            memory.put(key, b);
            main.post(() -> {
                if (key.equals(view.getTag())) {
                    view.setImageBitmap(b);
                }
            });
        });
    }

    private Bitmap load(File source, String key) {
        File cached = new File(cacheDir, key.replace('/', '_') + ".jpg");
        if (cached.isFile() && cached.lastModified() >= source.lastModified()) {
            Bitmap b = BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (b != null) {
                return b;
            }
        }
        Bitmap b = source.getName().endsWith(".mp4") ? videoFrame(source) : stillSmall(source);
        if (b != null) {
            try (FileOutputStream s = new FileOutputStream(cached)) {
                b.compress(Bitmap.CompressFormat.JPEG, 80, s);
            } catch (IOException e) {
                Log.w(TAG, "thumb cache write failed: " + e);
            }
        }
        return b;
    }

    private static Bitmap stillSmall(File f) {
        if (f.getName().endsWith(".dng")) {
            return null;   // no decoder here; the JPEG twin stands in for it
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= TARGET_PX) {
            sample *= 2;
        }
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private static Bitmap videoFrame(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            Bitmap full = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (full == null) {
                return null;
            }
            int w = full.getWidth(), h = full.getHeight();
            float s = TARGET_PX / (float) Math.max(w, h);
            if (s >= 1f) {
                return full;
            }
            Bitmap small = Bitmap.createScaledBitmap(full, Math.round(w * s), Math.round(h * s), true);
            full.recycle();
            return small;
        } catch (RuntimeException e) {
            Log.w(TAG, "video thumb failed for " + f + ": " + e);
            return null;
        } finally {
            try {
                r.release();
            } catch (IOException ignored) {
            }
        }
    }

    /** Full-size decode of a still for the viewer, bounded to the screen. Background thread. */
    public static Bitmap large(File f, int maxPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) {
            sample *= 2;
        }
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    public void forget(String keyPrefix) {
        File[] fs = cacheDir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.getName().startsWith(keyPrefix.replace('/', '_'))) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }
}
