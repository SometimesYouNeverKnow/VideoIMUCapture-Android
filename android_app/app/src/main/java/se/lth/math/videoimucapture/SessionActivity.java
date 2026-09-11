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
import android.widget.SeekBar;
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

        View controls = findViewById(R.id.session_controls);
        if (mSession.video != null && mSession.videoMs > 0) {
            setUpPlayer(controls);
        } else {
            mVideo.setVisibility(View.GONE);
            controls.setVisibility(View.GONE);
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

    // ------------------------------------------------------------------------ the player
    //
    // Our own transport, not MediaController. The stock controller is a pop-up that appears on
    // a tap, hides itself after three seconds and anchors to the video's window position --
    // inside a scrolling page that is a control you cannot find, which is what "the player is
    // not working" looked like on the first try. A button that says Play, a bar you can drag,
    // and the time, all of them always on screen.

    private Button mPlay;
    private SeekBar mSeek;
    private TextView mTime;
    private final android.os.Handler mTick = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mPrepared = false;
    private final Runnable mTickRun = new Runnable() {
        @Override
        public void run() {
            if (mPrepared && mVideo != null) {
                int pos = mVideo.getCurrentPosition();
                if (!mSeekHeld) {
                    mSeek.setProgress(pos);
                }
                mTime.setText(clock(pos) + " / " + clock(mVideo.getDuration())
                        + (mVideo.isPlaying() && !mRendered ? " (no frame drawn)" : ""));
                mPlay.setText(mVideo.isPlaying() ? "❚❚ Pause" : "▶ Play");
            }
            mTick.postDelayed(this, 250L);
        }
    };
    private boolean mSeekHeld = false;
    private volatile boolean mRendered = false;

    private void setUpPlayer(View controls) {
        mPlay = findViewById(R.id.session_play);
        mSeek = findViewById(R.id.session_seek);
        mTime = findViewById(R.id.session_time);
        mPlay.setEnabled(false);
        mVideo.setVideoPath(mSession.video.getAbsolutePath());
        mVideo.setOnPreparedListener(mp -> {
            mPrepared = true;
            mp.setLooping(false);
            // Fit the view to the clip: a portrait 3060x4080 in a landscape box is a thin strip.
            int vw = mp.getVideoWidth(), vh = mp.getVideoHeight();
            if (vw > 0 && vh > 0) {
                int w = mVideo.getWidth() > 0 ? mVideo.getWidth()
                        : getResources().getDisplayMetrics().widthPixels;
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
                int h = Math.min(maxH, (int) ((long) w * vh / vw));
                mVideo.getLayoutParams().height = h;
                mVideo.requestLayout();
            }
            mSeek.setMax(mVideo.getDuration());
            mVideo.seekTo(1);
            mPlay.setEnabled(true);
            mTime.setText("0:00 / " + clock(mVideo.getDuration()));
        });
        mVideo.setOnCompletionListener(mp -> mPlay.setText("▶ Play"));
        // Decoding and drawing are different events, and "black" needs to say which one it is:
        // the log of the first try showed every clip prepared and started, so if the screen
        // stayed black the frame never reached the surface. This flag is the drawn half.
        mVideo.setOnInfoListener((mp, what, extra) -> {
            if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                mRendered = true;
            }
            return false;
        });
        mVideo.setOnErrorListener((mp, what, extra) -> {
            mTime.setText("cannot play (" + what + "/" + extra + ")");
            Toast.makeText(this, "video will not play (" + what + "/" + extra + ")",
                    Toast.LENGTH_LONG).show();
            return true;
        });
        mPlay.setOnClickListener(v -> {
            if (!mPrepared) {
                return;
            }
            if (mVideo.isPlaying()) {
                mVideo.pause();
                mPlay.setText("▶ Play");
            } else {
                mVideo.start();
                mPlay.setText("❚❚ Pause");
            }
        });
        mVideo.setOnClickListener(v -> mPlay.performClick());
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && mPrepared) {
                    mVideo.seekTo(progress);
                    mTime.setText(clock(progress) + " / " + clock(mVideo.getDuration()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                mSeekHeld = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                mSeekHeld = false;
            }
        });
        mTick.post(mTickRun);
    }

    private static String clock(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVideo != null && mVideo.isPlaying()) {
            mVideo.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mTick.removeCallbacks(mTickRun);
        if (mVideo != null) {
            mVideo.stopPlayback();
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
