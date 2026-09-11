package se.lth.math.videoimucapture;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The film roll: every capture directory on the phone, newest first, GROUPED BY MODE.
 *
 * Grouped, not chronological, on purpose (ReconStab #15). The mistake this screen exists to
 * catch is the wrong mode -- a walk shot as a pano, a stills run tapped while standing still --
 * and in a flat gallery a mis-moded session looks exactly like a good session of the wrong kind.
 * Under a heading it is in the wrong pile, which is visible from across the room.
 *
 * Tick sessions and delete them together, or open one to watch the video, page the stills and
 * read what the streams actually recorded.
 */
public class RollActivity extends AppCompatActivity {

    private static final String[] MODE_ORDER = {"WALK", "OBJECT", "PANO", "STILLS", "OTHER"};

    private final ExecutorService mIo = Executors.newSingleThreadExecutor(r -> new Thread(r, "Roll"));
    private Thumbs mThumbs;
    private RecyclerView mList;
    private Button mDelete;
    private TextView mEmpty;
    private final List<Object> mRows = new ArrayList<>();          // String headers, SessionSummary rows
    private final Set<String> mTicked = new HashSet<>();
    private Adapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.roll_activity);
        setTitle(R.string.roll_toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        mThumbs = new Thumbs(this);
        mList = findViewById(R.id.roll_list);
        mDelete = findViewById(R.id.roll_delete);
        mEmpty = findViewById(R.id.roll_empty);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new Adapter();
        mList.setAdapter(mAdapter);
        mDelete.setOnClickListener(v -> confirmDelete());
        updateDeleteButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private File root() {
        return getExternalFilesDir(null);
    }

    private void reload() {
        mIo.execute(() -> {
            File[] dirs = root().listFiles(File::isDirectory);
            List<SessionSummary> all = new ArrayList<>();
            if (dirs != null) {
                for (File d : dirs) {
                    all.add(SessionSummary.quick(d));
                }
            }
            // Newest first inside each mode; the directory name carries the stamp.
            all.sort((a, b) -> b.name.compareTo(a.name));
            List<Object> rows = new ArrayList<>();
            for (String mode : MODE_ORDER) {
                int n = 0;
                for (SessionSummary s : all) {
                    if (s.mode.equals(mode)) {
                        if (n++ == 0) {
                            rows.add(mode);
                        }
                        rows.add(s);
                    }
                }
                if (n > 0) {
                    rows.set(rows.indexOf(mode), mode + "  (" + n + ")");
                }
            }
            runOnUiThread(() -> {
                mRows.clear();
                mRows.addAll(rows);
                mTicked.retainAll(names(all));
                mAdapter.notifyDataSetChanged();
                mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                updateDeleteButton();
            });
        });
    }

    private static Set<String> names(List<SessionSummary> all) {
        Set<String> out = new HashSet<>();
        for (SessionSummary s : all) {
            out.add(s.name);
        }
        return out;
    }

    private void updateDeleteButton() {
        int n = mTicked.size();
        mDelete.setEnabled(n > 0);
        mDelete.setText(n > 0
                ? String.format(Locale.US, "Delete %d selected", n)
                : "Tick sessions to delete");
    }

    private void confirmDelete() {
        List<SessionSummary> chosen = new ArrayList<>();
        long bytes = 0;
        for (Object o : mRows) {
            if (o instanceof SessionSummary && mTicked.contains(((SessionSummary) o).name)) {
                chosen.add((SessionSummary) o);
                bytes += ((SessionSummary) o).bytes;
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        for (SessionSummary s : chosen) {
            msg.append(s.mode).append("  ").append(s.when).append("  ").append(s.countsText())
                    .append('\n');
        }
        msg.append(String.format(Locale.US, "\n%.0f MB. This cannot be undone.", bytes / 1048576.0));
        new AlertDialog.Builder(this)
                .setTitle("Delete " + chosen.size() + (chosen.size() == 1 ? " session?" : " sessions?"))
                .setMessage(msg.toString())
                .setPositiveButton("Delete", (d, w) -> deleteAll(chosen))
                .setNegativeButton("Keep", null)
                .show();
    }

    private void deleteAll(List<SessionSummary> chosen) {
        mIo.execute(() -> {
            int failed = 0;
            for (SessionSummary s : chosen) {
                if (!SessionSummary.deleteTree(s.dir)) {
                    failed++;
                }
                mThumbs.forget(s.name);
            }
            final int f = failed;
            runOnUiThread(() -> {
                mTicked.clear();
                Toast.makeText(this, f == 0 ? "Deleted" : f + " could not be fully deleted",
                        Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    // ---------------------------------------------------------------------------- adapter

    private static final int HEADER = 0, ROW = 1;

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return mRows.get(position) instanceof String ? HEADER : ROW;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (type == HEADER) {
                return new Header(inf.inflate(R.layout.roll_header, parent, false));
            }
            return new Row(inf.inflate(R.layout.roll_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Object o = mRows.get(position);
            if (h instanceof Header) {
                ((Header) h).title.setText((String) o);
                return;
            }
            Row r = (Row) h;
            SessionSummary s = (SessionSummary) o;
            r.bind(s);
        }
    }

    private static final class Header extends RecyclerView.ViewHolder {
        final TextView title;

        Header(View v) {
            super(v);
            title = v.findViewById(R.id.roll_header_title);
        }
    }

    private final class Row extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView mode, when, counts, size;
        final CheckBox tick;
        SessionSummary bound;

        Row(View v) {
            super(v);
            thumb = v.findViewById(R.id.roll_thumb);
            mode = v.findViewById(R.id.roll_mode);
            when = v.findViewById(R.id.roll_when);
            counts = v.findViewById(R.id.roll_counts);
            size = v.findViewById(R.id.roll_size);
            tick = v.findViewById(R.id.roll_tick);
            v.setOnClickListener(x -> {
                if (bound != null) {
                    Intent i = new Intent(RollActivity.this, SessionActivity.class);
                    i.putExtra(SessionActivity.EXTRA_DIR, bound.dir.getAbsolutePath());
                    startActivity(i);
                }
            });
            v.setOnLongClickListener(x -> {
                tick.setChecked(!tick.isChecked());
                return true;
            });
            tick.setOnCheckedChangeListener((b, checked) -> {
                if (bound == null) {
                    return;
                }
                if (checked) {
                    mTicked.add(bound.name);
                } else {
                    mTicked.remove(bound.name);
                }
                updateDeleteButton();
            });
        }

        void bind(SessionSummary s) {
            bound = null;                       // silence the listener while the box is set
            tick.setChecked(mTicked.contains(s.name));
            bound = s;
            mode.setText(s.mode + "  " + s.kind);
            mode.setTextColor(modeColor(s.mode));
            when.setText(s.when);
            counts.setText(s.countsText());
            size.setText(s.sizeText());
            File src = !s.stills.isEmpty() ? firstJpeg(s) : s.video;
            if (src != null) {
                mThumbs.into(thumb, src, s.name + "/" + src.getName());
            } else {
                thumb.setTag(null);
                thumb.setImageDrawable(null);
            }
        }
    }

    static File firstJpeg(SessionSummary s) {
        for (File f : s.stills) {
            if (f.getName().endsWith(".jpg")) {
                return f;
            }
        }
        return s.video;
    }

    static int modeColor(String mode) {
        switch (mode) {
            case "WALK": return Color.rgb(0x4c, 0xaf, 0x50);
            case "OBJECT": return Color.rgb(0xff, 0x98, 0x00);
            case "PANO": return Color.rgb(0x03, 0xa9, 0xf4);
            case "STILLS": return Color.rgb(0xab, 0x47, 0xbc);
            default: return Color.GRAY;
        }
    }
}
