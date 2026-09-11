package se.lth.math.videoimucapture;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One session: the video with transport controls, the stills as a strip (tap for full screen,
 * swipe or arrow through them), and what the streams recorded -- counted from the metadata file
 * rather than assumed from the buttons that were pressed.
 */
public class SessionActivity extends AppCompatActivity {
    public static final String EXTRA_DIR = "dir";

    private final ExecutorService mIo = Executors.newSingleThreadExecutor(r -> new Thread(r, "Session"));
    private Thumbs mThumbs;
    private SessionSummary mSession;
    private VideoView mVideo;
    private TextView mSummary, mStreams, mStillsLabel;
    private RecyclerView mStrip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.session_activity);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        String path = getIntent().getStringExtra(EXTRA_DIR);
        if (path == null) {
            finish();
            return;
        }
        mThumbs = new Thumbs(this);
        mVideo = findViewById(R.id.session_video);
        mSummary = findViewById(R.id.session_summary);
        mStreams = findViewById(R.id.session_streams);
        mStillsLabel = findViewById(R.id.session_stills_label);
        mStrip = findViewById(R.id.session_strip);
        Button delete = findViewById(R.id.session_delete);
        mStrip.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        delete.setOnClickListener(v -> confirmDelete());

        mSession = SessionSummary.quick(new File(path));
        setTitle(mSession.mode + " · " + mSession.when);
        mSummary.setText(mSession.mode + "  " + mSession.kind + "\n" + mSession.name + "\n"
                + mSession.countsText() + " · " + mSession.sizeText());
        mStreams.setText("reading streams…");

        if (mSession.video != null && mSession.videoMs > 0) {
            MediaController mc = new MediaController(this);
            mc.setAnchorView(mVideo);
            mVideo.setMediaController(mc);
            mVideo.setVideoPath(mSession.video.getAbsolutePath());
            mVideo.setOnPreparedListener(mp -> {
                mp.setLooping(false);
                mVideo.seekTo(1);
            });
            mVideo.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "video will not play (" + what + "/" + extra + ")",
                        Toast.LENGTH_LONG).show();
                return true;
            });
        } else {
            mVideo.setVisibility(View.GONE);
        }

        if (mSession.stills.isEmpty()) {
            mStillsLabel.setText("no stills");
            mStrip.setVisibility(View.GONE);
        } else {
            mStillsLabel.setText(String.format(Locale.US, "%d stills — tap to view",
                    mSession.stills.size()));
            mStrip.setAdapter(new StripAdapter());
        }

        mIo.execute(() -> {
            SessionSummary.Streams st = mSession.streams();
            runOnUiThread(() -> mStreams.setText(st.text()));
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVideo != null && mVideo.isPlaying()) {
            mVideo.pause();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete this session?")
                .setMessage(mSession.mode + "  " + mSession.when + "\n" + mSession.countsText()
                        + " · " + mSession.sizeText() + "\n\nThis cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (mVideo != null) {
                        mVideo.stopPlayback();
                    }
                    mIo.execute(() -> {
                        boolean ok = SessionSummary.deleteTree(mSession.dir);
                        mThumbs.forget(mSession.name);
                        runOnUiThread(() -> {
                            Toast.makeText(this, ok ? "Deleted" : "Not fully deleted",
                                    Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    });
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    // ------------------------------------------------------------------------ the stills

    private final class StripAdapter extends RecyclerView.Adapter<Cell> {
        @NonNull
        @Override
        public Cell onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Cell(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.still_thumb, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Cell c, int position) {
            File f = mSession.stills.get(position);
            c.label.setText(shortName(f.getName()));
            if (f.getName().endsWith(".jpg")) {
                mThumbs.into(c.image, f, mSession.name + "/" + f.getName());
            } else {
                c.image.setTag(null);
                c.image.setImageDrawable(null);
            }
            c.itemView.setOnClickListener(v -> showFull(position));
        }

        @Override
        public int getItemCount() {
            return mSession.stills.size();
        }
    }

    private static final class Cell extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label;

        Cell(View v) {
            super(v);
            image = v.findViewById(R.id.still_image);
            label = v.findViewById(R.id.still_label);
        }
    }

    /** "stereo_395…_uw.jpg" -> "uw", "still_395…_03.jpg" -> "03", keeps the extension for DNG. */
    static String shortName(String n) {
        String base = n.endsWith(".jpg") ? n.substring(0, n.length() - 4) : n;
        int u = base.lastIndexOf('_');
        return u >= 0 ? base.substring(u + 1) : base;
    }

    private void showFull(int start) {
        final int[] index = {start};
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.still_full);
        ImageView img = d.findViewById(R.id.full_image);
        TextView cap = d.findViewById(R.id.full_caption);
        Runnable show = () -> {
            File f = mSession.stills.get(index[0]);
            cap.setText(String.format(Locale.US, "%d / %d   %s", index[0] + 1,
                    mSession.stills.size(), f.getName()));
            if (!f.getName().endsWith(".jpg")) {
                img.setImageDrawable(null);
                cap.append("   (RAW; no preview)");
                return;
            }
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int max = Math.max(dm.widthPixels, dm.heightPixels);
            final File target = f;
            mIo.execute(() -> {
                Bitmap b = Thumbs.large(target, max);
                runOnUiThread(() -> {
                    if (mSession.stills.get(index[0]) == target) {
                        img.setImageBitmap(b);
                    }
                });
            });
        };
        d.findViewById(R.id.full_prev).setOnClickListener(v -> {
            index[0] = (index[0] - 1 + mSession.stills.size()) % mSession.stills.size();
            show.run();
        });
        d.findViewById(R.id.full_next).setOnClickListener(v -> {
            index[0] = (index[0] + 1) % mSession.stills.size();
            show.run();
        });
        img.setOnClickListener(v -> d.dismiss());
        show.run();
        d.show();
    }
}
