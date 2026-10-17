package com.tomilabs.lorasignalmap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.tomilabs.lorasignalmap.log.CsvExport;
import com.tomilabs.lorasignalmap.log.Session;
import com.tomilabs.lorasignalmap.log.SessionStore;
import com.tomilabs.lorasignalmap.ui.Fmt;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** List of recorded / imported sessions: show on map, share, export CSV, delete, import. */
public final class SessionsActivity extends Activity {
    private static final int REQ_IMPORT = 10;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Session> sessions = new ArrayList<>();
    private ExecutorService io;
    private SharedPreferences prefs;
    private Adapter adapter;
    private TextView empty;
    private View progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sessions);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.sessions);
        }
        prefs = Prefs.get(this);
        io = Executors.newSingleThreadExecutor();
        empty = findViewById(R.id.empty);
        progress = findViewById(R.id.progress);
        ListView list = findViewById(R.id.list);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Session s = sessions.get(position);
                Prefs.setSessionHidden(prefs, s.fileName, false);
                prefs.edit().putBoolean(Prefs.SHOW_HISTORY, true).apply();
                startActivity(new Intent(SessionsActivity.this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_FOCUS_SESSION, s.fileName)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.sessions, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_import) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_IMPORT);
            } catch (ActivityNotFoundException e) {
                toast(getString(R.string.sessions_import_failed, e.getMessage()));
            }
            return true;
        } else if (id == R.id.action_show_all) {
            prefs.edit().remove(Prefs.HIDDEN_SESSIONS).putBoolean(Prefs.SHOW_HISTORY, true).apply();
            adapter.notifyDataSetChanged();
            return true;
        } else if (id == R.id.action_hide_all) {
            Set<String> hidden = Prefs.hiddenSessions(prefs);
            for (Session s : sessions) {
                hidden.add(s.fileName);
            }
            prefs.edit().putStringSet(Prefs.HIDDEN_SESSIONS, hidden).apply();
            adapter.notifyDataSetChanged();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        progress.setVisibility(View.VISIBLE);
        submit(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    File f = SessionStore.importLog(SessionsActivity.this, uri);
                    msg = getString(R.string.sessions_imported, f.getName());
                } catch (IOException | RuntimeException e) {
                    msg = getString(R.string.sessions_import_failed, e.getMessage());
                }
                final String m = msg;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(m);
                        reload();
                    }
                });
            }
        });
    }

    private void reload() {
        progress.setVisibility(View.VISIBLE);
        submit(new Runnable() {
            @Override
            public void run() {
                final List<Session> loaded = new ArrayList<>();
                for (File f : SessionStore.list(SessionsActivity.this)) {
                    loaded.add(SessionStore.load(f));
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        sessions.clear();
                        sessions.addAll(loaded);
                        adapter.notifyDataSetChanged();
                        progress.setVisibility(View.GONE);
                        empty.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    private void submit(Runnable r) {
        try {
            io.execute(r);
        } catch (RuntimeException ignored) {
            // activity is closing
        }
    }

    private boolean isRecording(Session s) {
        LiveState live = LiveState.get();
        return live.running && s.fileName.equals(live.sessionFile);
    }

    private void showMenu(View anchor, final Session s) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, R.string.sessions_share_log);
        pm.getMenu().add(0, 2, 1, R.string.sessions_export_csv);
        if (!isRecording(s)) {
            pm.getMenu().add(0, 3, 2, R.string.delete);
        }
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == 1) {
                    share(LogFileProvider.uriFor(LogFileProvider.DIR_SESSIONS, s.fileName), "text/plain", s.fileName);
                } else if (item.getItemId() == 2) {
                    exportCsv(s);
                } else if (item.getItemId() == 3) {
                    confirmDelete(s);
                }
                return true;
            }
        });
        pm.show();
    }

    private void exportCsv(final Session s) {
        progress.setVisibility(View.VISIBLE);
        submit(new Runnable() {
            @Override
            public void run() {
                File out = null;
                String error = null;
                try {
                    File src = SessionStore.find(SessionsActivity.this, s.fileName);
                    if (src == null) {
                        throw new IOException("file not found");
                    }
                    out = CsvExport.export(SessionStore.load(src), SessionStore.exportDir(SessionsActivity.this));
                } catch (IOException | RuntimeException e) {
                    error = e.getMessage();
                }
                final File f = out;
                final String err = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.setVisibility(View.GONE);
                        if (f != null) {
                            share(LogFileProvider.uriFor(LogFileProvider.DIR_EXPORTS, f.getName()), "text/csv", f.getName());
                        } else {
                            toast("CSV export failed: " + err);
                        }
                    }
                });
            }
        });
    }

    private void share(Uri uri, String mime, String name) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_SUBJECT, name);
        i.setClipData(ClipData.newRawUri(name, uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (ActivityNotFoundException e) {
            toast(e.getMessage());
        }
    }

    private void confirmDelete(final Session s) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sessions_delete_title)
                .setMessage(getString(R.string.sessions_delete_msg, s.fileName))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SessionStore.delete(SessionsActivity.this, s.fileName);
                        Prefs.setSessionHidden(prefs, s.fileName, false);
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return sessions.size();
        }

        @Override
        public Object getItem(int position) {
            return sessions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(SessionsActivity.this).inflate(R.layout.item_session, parent, false);
            final Session s = sessions.get(position);
            TextView title = v.findViewById(R.id.title);
            TextView subtitle = v.findViewById(R.id.subtitle);
            TextView file = v.findViewById(R.id.file);
            CheckBox visible = v.findViewById(R.id.visible);

            long start = s.startT > 0 ? s.startT : s.fileModified;
            title.setText(start > 0 ? Fmt.dateTime(start) : s.fileName);
            String sub;
            if (s.badLines < 0) {
                sub = getString(R.string.sessions_unreadable);
            } else {
                sub = getString(R.string.sessions_summary, Fmt.duration(s.durationMs()), s.count(),
                        Fmt.percent(s.okCount(), s.count()));
                if (isRecording(s)) {
                    sub += " · " + getString(R.string.sessions_recording);
                } else if (!s.ended && s.count() > 0) {
                    sub += " · " + getString(R.string.sessions_interrupted);
                }
            }
            subtitle.setText(sub);
            String fw = s.deviceFw != null ? "  ·  fw " + s.deviceFw : "";
            file.setText(String.format(Locale.US, "%s  ·  %.1f kB%s", s.fileName, s.fileLength / 1024.0, fw));

            visible.setOnCheckedChangeListener(null);
            visible.setChecked(!Prefs.hiddenSessions(prefs).contains(s.fileName));
            visible.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Prefs.setSessionHidden(prefs, s.fileName, !isChecked);
                    if (isChecked) {
                        prefs.edit().putBoolean(Prefs.SHOW_HISTORY, true).apply();
                    }
                }
            });
            v.findViewById(R.id.more).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showMenu(view, s);
                }
            });
            return v;
        }
    }
}
