package com.tomilabs.lorasignalmap;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tomilabs.lorasignalmap.link.UsbLink;
import com.tomilabs.lorasignalmap.log.Session;
import com.tomilabs.lorasignalmap.log.SessionStore;
import com.tomilabs.lorasignalmap.map.ColorScale;
import com.tomilabs.lorasignalmap.map.MapStyles;
import com.tomilabs.lorasignalmap.map.RepeaterOverlay;
import com.tomilabs.lorasignalmap.map.SignalOverlay;
import com.tomilabs.lorasignalmap.model.DeviceInfo;
import com.tomilabs.lorasignalmap.model.Measurement;
import com.tomilabs.lorasignalmap.model.SignalMath;
import com.tomilabs.lorasignalmap.offline.OfflineMap;
import com.tomilabs.lorasignalmap.offline.OfflineMapStore;
import com.tomilabs.lorasignalmap.offline.OsmImporter;
import com.tomilabs.lorasignalmap.offline.OverpassDownloader;
import com.tomilabs.lorasignalmap.offline.VectorTileModule;
import com.tomilabs.lorasignalmap.offline.VectorTileRenderer;
import com.tomilabs.lorasignalmap.service.MappingService;
import com.tomilabs.lorasignalmap.ui.Fmt;
import com.tomilabs.lorasignalmap.ui.LegendView;
import com.tomilabs.lorasignalmap.ui.MaxHeightScrollView;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.ScaleBarOverlay;
import org.osmdroid.views.overlay.TilesOverlay;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity
        implements LiveState.Listener, SignalOverlay.Listener, MapEventsReceiver, OfflineMapStore.Listener {
    /** Session file name to zoom to when the activity is (re)opened from the session list. */
    public static final String EXTRA_FOCUS_SESSION = "focus_session";
    private static final int REQ_LOCATION = 1;
    private static final int REQ_NOTIFICATIONS = 2;
    private static final int REQ_IMPORT_MAP = 3;
    /** Half size of the offline map download area: ~22 x 22 km around the map centre. */
    private static final double AREA_HALF_LAT = 0.10;
    private static final double AREA_HALF_LON = 0.15;
    private static final int MAX_DETAIL_ROWS = 60;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService loader;
    private int loadGeneration;
    private SharedPreferences prefs;
    private float density;

    private MapView map;
    private SignalOverlay signals;
    private RepeaterOverlay repeater;
    private MyLocationNewOverlay myLocation;
    private String appliedStyle;
    private boolean vectorActive;
    private OfflineMap appliedOfflineMap;
    private volatile boolean mapJobCancelled;
    private boolean mapJobRunning;

    private TextView chipUsb;
    private TextView chipWifi;
    private TextView chipGps;
    private TextView recBadge;
    private TextView deviceLine;
    private TextView messageLine;
    private TextView attribution;
    private LegendView legend;
    private View lastDot;
    private TextView lastLabel;
    private TextView lastValue;
    private TextView lastDetail;
    private FrameLayout btnStart;
    private ImageView btnStartIcon;
    private TextView btnStartLabel;
    private TextView statBeacons;
    private TextView statReplies;
    private TextView statTime;
    private TextView statNoGps;
    private View details;
    private TextView detailsTitle;
    private TextView detailsSummary;
    private LinearLayout detailsList;
    private MaxHeightScrollView detailsScroll;
    private ImageButton btnLocate;

    private boolean wasRunning;
    private boolean startPending;
    private boolean notificationsAsked;
    private long lastStartStopClick;
    private String focusSession;

    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        density = getResources().getDisplayMetrics().density;
        loader = Executors.newSingleThreadExecutor();
        setContentView(R.layout.activity_main);
        bindViews();
        setupMap();
        wasRunning = LiveState.get().running;
        if (savedInstanceState == null) {
            handleIntent(getIntent());
        }
        showCrashReportIfAny();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        applyPreferences();
        if (hasLocationPermission()) {
            myLocation.enableMyLocation();
        }
        LiveState live = LiveState.get();
        live.addListener(this);
        OfflineMapStore.addListener(this);
        wasRunning = live.running;
        signals.setLive(live.running ? live.points : new ArrayList<Measurement>());
        reloadHistory();
        refreshStatus();
        ui.removeCallbacks(clock);
        ui.postDelayed(clock, 1000);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(clock);
        LiveState.get().removeListener(this);
        OfflineMapStore.removeListener(this);
        saveMapPosition();
        myLocation.disableMyLocation();
        map.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        map.onDetach();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (details.getVisibility() == View.VISIBLE) {
            hideDetails();
            return;
        }
        super.onBackPressed();
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getStringExtra(EXTRA_FOCUS_SESSION) != null) {
            focusSession = intent.getStringExtra(EXTRA_FOCUS_SESSION);
            intent.removeExtra(EXTRA_FOCUS_SESSION);
            hideDetails();
        }
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())
                && !LiveState.get().running) {
            LiveState.get().showMessage(getString(R.string.usb_attached) + ". "
                    + getString(R.string.device_idle_usb));
        }
    }

    // ------------------------------------------------------------------ setup

    private void bindViews() {
        map = findViewById(R.id.map);
        chipUsb = findViewById(R.id.chip_usb);
        chipWifi = findViewById(R.id.chip_wifi);
        chipGps = findViewById(R.id.chip_gps);
        recBadge = findViewById(R.id.rec_badge);
        deviceLine = findViewById(R.id.device_line);
        messageLine = findViewById(R.id.message_line);
        attribution = findViewById(R.id.attribution);
        legend = findViewById(R.id.legend);
        lastDot = findViewById(R.id.last_dot);
        lastLabel = findViewById(R.id.last_label);
        lastValue = findViewById(R.id.last_value);
        lastDetail = findViewById(R.id.last_detail);
        btnStart = findViewById(R.id.btn_start);
        btnStartIcon = findViewById(R.id.btn_start_icon);
        btnStartLabel = findViewById(R.id.btn_start_label);
        statBeacons = findViewById(R.id.stat_beacons);
        statReplies = findViewById(R.id.stat_replies);
        statTime = findViewById(R.id.stat_time);
        statNoGps = findViewById(R.id.stat_nogps);
        details = findViewById(R.id.details);
        detailsTitle = findViewById(R.id.details_title);
        detailsSummary = findViewById(R.id.details_summary);
        detailsList = findViewById(R.id.details_list);
        detailsScroll = findViewById(R.id.details_scroll);
        btnLocate = findViewById(R.id.btn_locate);
        legend.setFailLabel(getString(R.string.no_reply).toLowerCase(Locale.getDefault()));

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastStartStopClick < 800) {
                    return; // ignore double taps
                }
                lastStartStopClick = now;
                onStartStopClicked();
            }
        });
        findViewById(R.id.btn_layers).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLayersDialog();
            }
        });
        btnLocate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                followMe();
            }
        });
        findViewById(R.id.btn_fit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zoomToData();
            }
        });
        findViewById(R.id.btn_sessions).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SessionsActivity.class));
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.details_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideDetails();
            }
        });
    }

    private void setupMap() {
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setTilesScaledToDpi(true);
        map.setMinZoomLevel(3.0);
        map.setMaxZoomLevel(21.0);
        map.setVerticalMapRepetitionEnabled(false);
        map.setScrollableAreaLimitLatitude(85.0, -85.0, 0);
        appliedStyle = null; // applyPreferences() picks online tiles or the offline map

        map.getOverlays().add(new MapEventsOverlay(this));

        signals = new SignalOverlay(density);
        signals.setListener(this);
        map.getOverlays().add(signals);

        Drawable tower = getDrawable(R.drawable.ic_tower);
        if (tower != null) {
            tower = tower.mutate();
            tower.setTint(0xFFFFFFFF);
        }
        repeater = new RepeaterOverlay(density, tower, getString(R.string.repeater));
        map.getOverlays().add(repeater);

        myLocation = new MyLocationNewOverlay(map);
        myLocation.setPersonIcon(makeLocationDot());
        myLocation.setPersonAnchor(0.5f, 0.5f);
        myLocation.setDirectionIcon(makeLocationArrow());
        myLocation.setDirectionAnchor(0.5f, 0.5f);
        myLocation.setDrawAccuracyEnabled(true);
        map.getOverlays().add(myLocation);

        final ScaleBarOverlay scaleBar = new ScaleBarOverlay(map);
        scaleBar.setAlignBottom(false);
        scaleBar.setScaleBarOffset((int) (14 * density), (int) (150 * density));
        map.getOverlays().add(scaleBar);
        // keep the scale bar just below the status card, whatever its height
        findViewById(R.id.status_card).addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop,
                    int oldRight, int oldBottom) {
                if (bottom != oldBottom) {
                    scaleBar.setScaleBarOffset((int) (14 * density), bottom + (int) (10 * density));
                    map.invalidate();
                }
            }
        });

        double lat = Prefs.number(prefs, Prefs.MAP_LAT, Double.NaN);
        double lon = Prefs.number(prefs, Prefs.MAP_LON, Double.NaN);
        double zoom = Prefs.number(prefs, Prefs.MAP_ZOOM, 14);
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            Double[] rep = Prefs.repeater(prefs);
            lat = rep != null ? rep[0] : Prefs.HOME_LAT;
            lon = rep != null ? rep[1] : Prefs.HOME_LON;
        }
        map.getController().setZoom(Math.max(3, Math.min(20, zoom)));
        map.getController().setCenter(new GeoPoint(lat, lon));
    }

    private Bitmap makeLocationDot() {
        int s = (int) (22 * density);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x33000000);
        c.drawCircle(s / 2f, s / 2f + density * 0.6f, s / 2f - density * 0.5f, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(s / 2f, s / 2f, s / 2f - density, p);
        p.setColor(0xFF1A73E8);
        c.drawCircle(s / 2f, s / 2f, s / 2f - 4 * density, p);
        return b;
    }

    private Bitmap makeLocationArrow() {
        int s = (int) (30 * density);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path path = new Path();
        float cx = s / 2f;
        path.moveTo(cx, 2 * density);
        path.lineTo(s - 5 * density, s - 4 * density);
        path.lineTo(cx, s - 9 * density);
        path.lineTo(5 * density, s - 4 * density);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1A73E8);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(2 * density);
        p.setColor(0xFFFFFFFF);
        c.drawPath(path, p);
        return b;
    }

    private void applyPreferences() {
        applyMapSource();
        double[] sc = Prefs.scale(prefs);
        boolean bySnr = Prefs.colorBySnr(prefs);
        signals.configure(new ColorScale(sc[0], sc[1]), bySnr, Prefs.direction(prefs),
                prefs.getBoolean(Prefs.SHOW_TRACK, true), prefs.getBoolean(Prefs.SHOW_FAILED, true));
        legend.setScale(sc[0], sc[1], bySnr ? "dB" : "dBm");
        Double[] rep = Prefs.repeater(prefs);
        repeater.setPosition(rep != null ? new GeoPoint(rep[0], rep[1]) : null);
        updateKeepScreenOn();
        map.invalidate();
    }

    private void updateKeepScreenOn() {
        if (LiveState.get().running && prefs.getBoolean(Prefs.KEEP_SCREEN_ON, true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void saveMapPosition() {
        try {
            IGeoPoint c = map.getMapCenter();
            prefs.edit()
                    .putString(Prefs.MAP_LAT, Double.toString(c.getLatitude()))
                    .putString(Prefs.MAP_LON, Double.toString(c.getLongitude()))
                    .putString(Prefs.MAP_ZOOM, Double.toString(map.getZoomLevelDouble()))
                    .apply();
        } catch (RuntimeException ignored) {
            // map not laid out yet
        }
    }

    /** Loads the visible earlier sessions in the background and hands them to the overlay. */
    private void reloadHistory() {
        final int gen = ++loadGeneration;
        final boolean show = prefs.getBoolean(Prefs.SHOW_HISTORY, true);
        final Set<String> hidden = Prefs.hiddenSessions(prefs);
        final String liveFile = LiveState.get().running ? LiveState.get().sessionFile : null;
        try {
            loader.execute(new Runnable() {
                @Override
                public void run() {
                    final List<Measurement> all = new ArrayList<>();
                    if (show) {
                        List<File> files = SessionStore.list(MainActivity.this);
                        for (int i = files.size() - 1; i >= 0; i--) { // oldest first, newest drawn on top
                            File f = files.get(i);
                            if (hidden.contains(f.getName()) || f.getName().equals(liveFile)) {
                                continue;
                            }
                            Session s = SessionStore.load(f);
                            all.addAll(s.measurements);
                        }
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != loadGeneration || isFinishing() || isDestroyed()) {
                                return;
                            }
                            signals.setHistory(all);
                            map.invalidate();
                            if (focusSession != null) {
                                focusOn(focusSession, all);
                                focusSession = null;
                            }
                        }
                    });
                }
            });
        } catch (RuntimeException ignored) {
            // executor shut down: activity is finishing
        }
    }

    // ------------------------------------------------------------------ live updates

    @Override
    public void onLiveStateChanged() {
        boolean running = LiveState.get().running;
        if (running != wasRunning) {
            wasRunning = running;
            signals.setLive(running ? LiveState.get().points : new ArrayList<Measurement>());
            reloadHistory();
            updateKeepScreenOn();
            map.invalidate();
        }
        refreshStatus();
    }

    @Override
    public void onNewMeasurement(Measurement m) {
        signals.addLive(m);
        map.invalidate();
    }

    private void refreshStatus() {
        LiveState s = LiveState.get();
        boolean running = s.running;
        int ok = color(R.color.state_ok);
        int warn = color(R.color.state_warn);
        int off = color(R.color.state_off);
        int err = color(R.color.state_error);

        // USB chip
        if (running) {
            setChip(chipUsb, getString(R.string.usb), s.usbUp ? ok : off);
        } else {
            setChip(chipUsb, getString(R.string.usb), usbDevicePresent() ? color(R.color.accent) : off);
        }
        // Wi-Fi chip
        if (running && s.wifiUp) {
            setChip(chipWifi, getString(R.string.wifi), ok);
        } else if (running && s.device != null && ("connecting".equals(s.device.wifi) || "waiting".equals(s.device.wifi))) {
            setChip(chipWifi, getString(R.string.wifi), warn);
        } else {
            setChip(chipWifi, getString(R.string.wifi), off);
        }
        // GPS chip
        if (!running) {
            setChip(chipGps, getString(R.string.gps), off);
        } else if (!s.gpsEnabled) {
            setChip(chipGps, getString(R.string.gps_off), err);
        } else if (s.gpsFixRecent(3000)) {
            String acc = s.gpsAcc != null ? String.valueOf(Math.round(s.gpsAcc)) : "?";
            String txt = getString(R.string.gps_acc, acc);
            if (s.gpsSats != null && s.gpsSats > 0) {
                txt += " · " + s.gpsSats + " sat";
            }
            setChip(chipGps, txt, (s.gpsAcc != null && s.gpsAcc > 25) ? warn : ok);
        } else {
            setChip(chipGps, getString(R.string.gps_searching), warn);
        }

        recBadge.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            recBadge.setText(getString(R.string.rec) + "  " + Fmt.duration(SystemClock.elapsedRealtime() - s.startElapsedMs));
        }

        // device line
        int deviceColor = color(R.color.text_secondary);
        String deviceText;
        DeviceInfo d = s.device;
        if (!running) {
            deviceText = getString(usbDevicePresent() ? R.string.device_idle_usb : R.string.device_idle);
        } else if (d != null && "error".equals(d.radio)) {
            deviceText = getString(R.string.device_radio_error, d.radioError != null ? d.radioError : "?");
            deviceColor = err;
        } else if (d != null) {
            long age = SystemClock.elapsedRealtime() - s.lastDeviceMsgElapsedMs;
            if (age > 20000) {
                deviceText = getString(R.string.device_silent, Fmt.duration(age));
                deviceColor = warn;
            } else if (d.legacy) {
                deviceText = getString(R.string.device_legacy, Fmt.ago(age));
            } else {
                deviceText = getString(R.string.device_line, d.fw != null ? d.fw : "?",
                        d.id != null ? d.id : "?", Fmt.ago(age));
            }
        } else if (s.usbUp || s.wifiUp) {
            deviceText = getString(R.string.device_waiting);
        } else {
            deviceText = getString(R.string.device_no_link);
            deviceColor = warn;
        }
        deviceLine.setText(deviceText);
        deviceLine.setTextColor(deviceColor);

        // message / error line
        String msg = null;
        int msgColor = color(R.color.primary);
        if (s.error != null && running) {
            msg = s.error;
            msgColor = err;
        } else if (s.message != null && SystemClock.elapsedRealtime() - s.messageElapsedMs < 8000) {
            msg = s.message;
        } else if (!running && s.error != null) {
            msg = s.error;
            msgColor = err;
        }
        if (msg == null) {
            msg = mapHint();
            msgColor = warn;
        }
        messageLine.setVisibility(msg != null ? View.VISIBLE : View.GONE);
        if (msg != null) {
            messageLine.setText(msg);
            messageLine.setTextColor(msgColor);
        }

        // start button
        btnStart.setBackgroundResource(running ? R.drawable.stop_bg : R.drawable.start_bg);
        btnStartIcon.setImageResource(running ? R.drawable.ic_stop : R.drawable.ic_play);
        btnStartLabel.setText(running ? R.string.stop : R.string.start);

        // last beacon
        Measurement last = s.last;
        if (last != null && (running || s.count > 0)) {
            lastLabel.setText(getString(R.string.last_beacon) + " · #" + last.seq);
            int col = signals.colorOf(last);
            lastDot.setVisibility(View.VISIBLE);
            lastDot.setBackground(dotDrawable(col, !last.ok));
            if (last.ok) {
                Double v = signals.valueOf(last);
                boolean bySnr = Prefs.colorBySnr(prefs);
                lastValue.setText(v == null ? getString(R.string.reply) : (bySnr ? Fmt.db(v) : Fmt.dbm(v)));
                lastDetail.setText(describeSides(last));
            } else {
                lastValue.setText(R.string.no_reply);
                lastDetail.setText(last.crc > 0 ? last.crc + " corrupted packet(s) heard" : "");
            }
        } else {
            lastLabel.setText(R.string.last_beacon);
            lastDot.setVisibility(View.GONE);
            lastValue.setText(R.string.no_beacons_yet);
            lastDetail.setText("");
        }

        statBeacons.setText(String.valueOf(s.count));
        statReplies.setText(Fmt.percent(s.okCount, s.count));
        long duration = running ? SystemClock.elapsedRealtime() - s.startElapsedMs
                : (s.endElapsedMs > s.startElapsedMs ? s.endElapsedMs - s.startElapsedMs : 0);
        statTime.setText(Fmt.duration(duration));
        statNoGps.setText(String.valueOf(s.noFixCount));

        boolean following = myLocation.isFollowLocationEnabled();
        btnLocate.setImageTintList(ColorStateList.valueOf(following ? color(R.color.accent) : color(R.color.text_primary)));
    }

    private String describeSides(Measurement m) {
        StringBuilder sb = new StringBuilder();
        if (m.rssi != null) {
            sb.append("Phone ").append(Fmt.num(m.rssi, 0));
            if (m.snr != null) {
                sb.append(" / ").append(Fmt.num(m.snr, 2)).append(" dB");
            }
        }
        if (m.rrssi != null) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("Rep. ").append(Fmt.num(m.rrssi, 0));
            if (m.rsnr != null) {
                sb.append(" / ").append(Fmt.num(m.rsnr, 2)).append(" dB");
            }
        }
        return sb.toString();
    }

    private void setChip(TextView chip, String text, int tint) {
        chip.setText(text);
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(tint));
    }

    private int color(int res) {
        return getColor(res);
    }

    private GradientDrawable dotDrawable(int color, boolean ring) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (ring) {
            g.setColor(0xFFFFFFFF);
            g.setStroke((int) (2.5f * density), color);
        } else {
            g.setColor(color);
            g.setStroke((int) (1.5f * density), 0xFFFFFFFF);
        }
        return g;
    }

    private boolean usbDevicePresent() {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        if (um == null) {
            return false;
        }
        try {
            for (UsbDevice dev : um.getDeviceList().values()) {
                if (UsbLinkHelper.isCdc(dev)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // USB host not available
        }
        return false;
    }

    // ------------------------------------------------------------------ start / stop

    private void onStartStopClicked() {
        if (LiveState.get().running) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.stop_confirm_title)
                    .setMessage(R.string.stop_confirm_msg)
                    .setPositiveButton(R.string.stop, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            MappingService.stop(MainActivity.this);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        tryStart();
    }

    private void tryStart() {
        if (!hasLocationPermission()) {
            startPending = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAsked
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsAsked = true;
            startPending = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        startPending = false;
        LiveState.get().error = null;
        try {
            MappingService.start(this);
        } catch (RuntimeException e) {
            LiveState.get().error = getString(R.string.error_foreground) + " (" + e.getMessage() + ")";
            refreshStatus();
            return;
        }
        if (hasLocationPermission()) {
            myLocation.enableMyLocation();
            myLocation.enableFollowLocation();
        }
        if (!isGpsEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.gps_disabled_title)
                    .setMessage(R.string.gps_disabled_msg)
                    .setPositiveButton(R.string.open_settings, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            safeStart(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        }
                    })
                    .setNegativeButton(R.string.not_now, null)
                    .show();
        } else {
            maybeAskBatteryExemption();
        }
    }

    private void maybeAskBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())
                || prefs.getBoolean(Prefs.BATTERY_ASKED, false)) {
            return;
        }
        prefs.edit().putBoolean(Prefs.BATTERY_ASKED, true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_msg)
                .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                        if (!safeStart(i)) {
                            safeStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        }
                    }
                })
                .setNegativeButton(R.string.not_now, null)
                .show();
    }

    private boolean safeStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isGpsEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                myLocation.enableMyLocation();
                if (startPending) {
                    tryStart();
                }
            } else {
                startPending = false;
                new AlertDialog.Builder(this)
                        .setTitle(R.string.location_needed_title)
                        .setMessage(R.string.location_needed_msg)
                        .setPositiveButton(R.string.open_settings, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())));
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        } else if (requestCode == REQ_NOTIFICATIONS && startPending) {
            tryStart(); // works without notifications too
        }
    }

    // ------------------------------------------------------------------ map buttons

    private void followMe() {
        if (!hasLocationPermission()) {
            startPending = false;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        myLocation.enableMyLocation();
        if (myLocation.isFollowLocationEnabled()) {
            myLocation.disableFollowLocation();
        } else {
            myLocation.enableFollowLocation();
            if (map.getZoomLevelDouble() < 15) {
                map.getController().setZoom(16.0);
            }
            GeoPoint here = myLocation.getMyLocation();
            if (here != null) {
                map.getController().animateTo(here);
            }
        }
        refreshStatus();
    }

    private void zoomToData() {
        List<GeoPoint> pts = signals.allPoints();
        if (pts.isEmpty()) {
            LiveState.get().showMessage(getString(R.string.no_data));
            return;
        }
        myLocation.disableFollowLocation();
        fitPoints(pts);
    }

    private void focusOn(String session, List<Measurement> history) {
        List<Measurement> source = session.equals(LiveState.get().sessionFile) && LiveState.get().running
                ? LiveState.get().points : history;
        List<GeoPoint> pts = new ArrayList<>();
        for (Measurement m : source) {
            if (m.hasPosition() && session.equals(m.session)) {
                pts.add(new GeoPoint(m.lat, m.lon));
            }
        }
        if (pts.isEmpty()) {
            LiveState.get().showMessage(getString(R.string.no_data));
            return;
        }
        myLocation.disableFollowLocation();
        fitPoints(pts);
    }

    private void fitPoints(final List<GeoPoint> pts) {
        if (map.getWidth() == 0) {
            map.post(new Runnable() {
                @Override
                public void run() {
                    fitPoints(pts);
                }
            });
            return;
        }
        if (pts.size() == 1) {
            map.getController().animateTo(pts.get(0), 17.0, 600L);
            return;
        }
        BoundingBox box = BoundingBox.fromGeoPointsSafe(pts);
        if (box.getLatitudeSpan() < 0.002 && box.getLongitudeSpanWithDateLine() < 0.002) {
            map.getController().animateTo(box.getCenterWithDateLine(), 17.0, 600L);
            return;
        }
        map.zoomToBoundingBox(box.increaseByScale(1.25f), true, (int) (48 * density));
    }

    private void showLayersDialog() {
        final int pad = (int) (20 * density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * density), pad, 0);

        // map style
        box.addView(sectionTitle(R.string.layers_style));
        final RadioGroup style = new RadioGroup(this);
        final String[] styleKeys = {MapStyles.OFFLINE, MapStyles.LIGHT, MapStyles.DARK, MapStyles.OSM};
        int[] styleNames = {R.string.style_offline, R.string.style_light, R.string.style_dark, R.string.style_osm};
        addRadios(style, styleNames, indexOf(styleKeys, effectiveStyle()));
        box.addView(style);
        TextView offlineInfo = new TextView(this);
        offlineInfo.setText(offlineMapSummary());
        offlineInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        offlineInfo.setTextColor(color(R.color.text_secondary));
        offlineInfo.setPadding(0, (int) (4 * density), 0, (int) (4 * density));
        box.addView(offlineInfo);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button download = new Button(this);
        download.setText(R.string.offline_download);
        download.setAllCaps(false);
        Button importMap = new Button(this);
        importMap.setText(R.string.offline_import);
        importMap.setAllCaps(false);
        buttons.addView(download, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(importMap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(buttons);

        box.addView(sectionTitle(R.string.layers_color_by));
        final RadioGroup metric = new RadioGroup(this);
        addRadios(metric, new int[]{R.string.metric_signal, R.string.metric_snr}, Prefs.colorBySnr(prefs) ? 1 : 0);
        box.addView(metric);

        box.addView(sectionTitle(R.string.layers_direction));
        final RadioGroup dir = new RadioGroup(this);
        final String[] dirKeys = {"weakest", "down", "up"};
        addRadios(dir, new int[]{R.string.dir_weakest, R.string.dir_down, R.string.dir_up},
                Math.max(0, indexOf(dirKeys, prefs.getString(Prefs.COLOR_DIRECTION, "weakest"))));
        box.addView(dir);

        box.addView(sectionTitle(R.string.layers_show));
        box.addView(checkBox(R.string.show_track, Prefs.SHOW_TRACK));
        box.addView(checkBox(R.string.show_failed, Prefs.SHOW_FAILED));
        box.addView(checkBox(R.string.show_history, Prefs.SHOW_HISTORY));

        RadioGroup.OnCheckedChangeListener apply = new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                SharedPreferences.Editor e = prefs.edit();
                e.putString(Prefs.MAP_STYLE, styleKeys[checkedIndex(style)]);
                e.putString(Prefs.COLOR_METRIC, checkedIndex(metric) == 1 ? Prefs.METRIC_SNR : Prefs.METRIC_SIGNAL);
                e.putString(Prefs.COLOR_DIRECTION, dirKeys[checkedIndex(dir)]);
                e.apply();
                applyPreferences();
                refreshStatus();
            }
        };
        style.setOnCheckedChangeListener(apply);
        metric.setOnCheckedChangeListener(apply);
        dir.setOnCheckedChangeListener(apply);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.layers_title)
                .setView(scroll)
                .setPositiveButton(R.string.done, null)
                .show();
        download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                confirmDownload();
            }
        });
        importMap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                pickMapFile();
            }
        });
    }

    // ------------------------------------------------------------------ map source / offline map

    /** The style actually shown: "auto" means the offline map when there is one. */
    private String effectiveStyle() {
        String s = Prefs.mapStyle(prefs);
        if (MapStyles.AUTO.equals(s)) {
            return OfflineMapStore.get() != null ? MapStyles.OFFLINE : MapStyles.LIGHT;
        }
        return s;
    }

    private void applyMapSource() {
        String style = effectiveStyle();
        OfflineMap om = OfflineMapStore.get();
        if (MapStyles.OFFLINE.equals(style)) {
            if (!vectorActive || appliedOfflineMap != om) {
                map.setTileProvider(VectorTileModule.provider(getResources(), om));
                TilesOverlay tiles = map.getOverlayManager().getTilesOverlay();
                tiles.setLoadingBackgroundColor(VectorTileRenderer.LAND);
                tiles.setLoadingLineColor(VectorTileRenderer.LAND);
                vectorActive = true;
                appliedOfflineMap = om;
            }
        } else {
            ITileSource src = MapStyles.source(style);
            if (vectorActive || appliedStyle == null) {
                map.setTileProvider(new MapTileProviderBasic(getApplicationContext(), src));
                vectorActive = false;
                appliedOfflineMap = null;
            } else if (!style.equals(appliedStyle)) {
                map.setTileSource(src);
            }
        }
        appliedStyle = style;
        attribution.setText(map.getTileProvider().getTileSource().getCopyrightNotice());
        map.invalidate();
    }

    @Override
    public void onOfflineMapChanged(OfflineMap m) {
        applyMapSource();
        refreshStatus();
    }

    private boolean hasInternet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        try {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Warning shown when the map background cannot appear, or null. */
    private String mapHint() {
        if (mapJobRunning) {
            return null;
        }
        String style = effectiveStyle();
        if (MapStyles.OFFLINE.equals(style)) {
            if (OfflineMapStore.isLoaded() && OfflineMapStore.get() == null) {
                return getString(R.string.hint_no_offline_map);
            }
            return null;
        }
        if (!hasInternet()) {
            return getString(OfflineMapStore.get() != null ? R.string.hint_offline_available : R.string.hint_no_internet);
        }
        return null;
    }

    private String offlineMapSummary() {
        OfflineMap m = OfflineMapStore.get();
        if (m == null) {
            return getString(OfflineMapStore.isLoaded() ? R.string.offline_none : R.string.offline_loading);
        }
        return getString(R.string.offline_summary, Fmt.dateTime(m.createdMs),
                m.count(OfflineMap.PATH, OfflineMap.MAJOR), m.count(OfflineMap.BUILDING, OfflineMap.BUILDING),
                Fmt.num(m.south, 3), Fmt.num(m.west, 3), Fmt.num(m.north, 3), Fmt.num(m.east, 3));
    }

    private void confirmDownload() {
        if (mapJobRunning) {
            return;
        }
        IGeoPoint c = map.getMapCenter();
        final double lat = c.getLatitude();
        final double lon = c.getLongitude();
        if (!hasInternet()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.offline_download)
                    .setMessage(R.string.offline_no_internet)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.offline_download)
                .setMessage(getString(R.string.offline_download_msg,
                        String.format(Locale.US, "%.4f, %.4f", lat, lon)))
                .setPositiveButton(R.string.offline_download_go, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startDownload(lat - AREA_HALF_LAT, lon - AREA_HALF_LON, lat + AREA_HALF_LAT, lon + AREA_HALF_LON);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickMapFile() {
        if (mapJobRunning) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT_MAP);
        } catch (ActivityNotFoundException e) {
            LiveState.get().showMessage(String.valueOf(e.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_MAP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            startImport(data.getData());
        }
    }

    /** Progress dialog shared by download and import. */
    private final class MapJob {
        final AlertDialog dialog;
        final TextView status;

        MapJob(int title) {
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (22 * density);
            box.setPadding(pad, (int) (12 * density), pad, 0);
            ProgressBar bar = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            box.addView(bar);
            status = new TextView(MainActivity.this);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            status.setPadding(0, (int) (8 * density), 0, 0);
            box.addView(status);
            mapJobCancelled = false;
            mapJobRunning = true;
            dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title)
                    .setView(box)
                    .setCancelable(false)
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            mapJobCancelled = true;
                        }
                    })
                    .show();
        }

        void status(final String text) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    status.setText(text);
                }
            });
        }

        void finish(final OfflineMap result, final String error) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    mapJobRunning = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    try {
                        dialog.dismiss();
                    } catch (RuntimeException ignored) {
                        // window gone
                    }
                    if (result != null) {
                        prefs.edit().putString(Prefs.MAP_STYLE, MapStyles.OFFLINE).apply();
                        applyMapSource();
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.offline_ready_title)
                                .setMessage(getString(R.string.offline_ready_msg,
                                        result.count(OfflineMap.PATH, OfflineMap.MAJOR),
                                        result.count(OfflineMap.BUILDING, OfflineMap.BUILDING)))
                                .setPositiveButton(R.string.ok, null)
                                .show();
                    } else if (error != null && !mapJobCancelled) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.offline_failed_title)
                                .setMessage(error)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                    }
                    refreshStatus();
                }
            });
        }
    }

    private void startDownload(final double s, final double w, final double n, final double e) {
        final MapJob job = new MapJob(R.string.offline_download);
        job.status.setText(R.string.offline_stage_connect);
        final File tmp = new File(getCacheDir(), "overpass.json");
        final String ua = Configuration.getInstance().getUserAgentValue();
        new Thread(new Runnable() {
            @Override
            public void run() {
                OfflineMap result = null;
                String error = null;
                try {
                    OfflineMap m = OverpassDownloader.download(s, w, n, e, tmp, ua, new OverpassDownloader.Progress() {
                        @Override
                        public boolean onProgress(String stage, long amount) {
                            if ("download".equals(stage)) {
                                job.status(getString(R.string.offline_stage_download,
                                        Fmt.num(amount / 1048576.0, 1)));
                            } else {
                                job.status(getString(R.string.offline_stage_build, amount));
                            }
                            return !mapJobCancelled;
                        }
                    });
                    job.status(getString(R.string.offline_stage_save));
                    OfflineMapStore.install(MainActivity.this, m);
                    result = m;
                } catch (OsmImporter.Cancelled c) {
                    error = null;
                } catch (IOException | RuntimeException ex) {
                    error = getString(R.string.offline_failed_msg, String.valueOf(ex.getMessage()));
                } catch (OutOfMemoryError oom) {
                    error = getString(R.string.offline_failed_msg, "the area is too large for this phone");
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                job.finish(result, error);
            }
        }, "offline-map-download").start();
    }

    private void startImport(final Uri uri) {
        final MapJob job = new MapJob(R.string.offline_import);
        job.status.setText(R.string.offline_stage_read);
        new Thread(new Runnable() {
            @Override
            public void run() {
                OfflineMap result = null;
                String error = null;
                InputStream raw = null;
                try {
                    raw = getContentResolver().openInputStream(uri);
                    if (raw == null) {
                        throw new IOException("cannot open the file");
                    }
                    BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
                    in.mark(4096);
                    int first = -1;
                    for (int i = 0; i < 4096; i++) {
                        int b = in.read();
                        if (b < 0 || !Character.isWhitespace(b) && b != 0xEF && b != 0xBB && b != 0xBF) {
                            first = b;
                            break;
                        }
                    }
                    in.reset();
                    OsmImporter imp = new OsmImporter();
                    OsmImporter.Progress p = new OsmImporter.Progress() {
                        @Override
                        public boolean onProgress(int elements) {
                            job.status(getString(R.string.offline_stage_build, (long) elements));
                            return !mapJobCancelled;
                        }
                    };
                    if (first == '{') {
                        imp.readOverpassJson(in, p);
                    } else if (first == '<') {
                        imp.readOsmXml(in, p);
                    } else {
                        throw new IOException(getString(R.string.offline_bad_file));
                    }
                    if (imp.featureCount() == 0) {
                        throw new IOException(getString(R.string.offline_bad_file));
                    }
                    job.status(getString(R.string.offline_stage_save));
                    OfflineMap m = imp.build("OpenStreetMap file import", null);
                    OfflineMapStore.install(MainActivity.this, m);
                    result = m;
                } catch (OsmImporter.Cancelled c) {
                    error = null;
                } catch (IOException | RuntimeException ex) {
                    error = getString(R.string.offline_failed_msg, String.valueOf(ex.getMessage()));
                } catch (OutOfMemoryError oom) {
                    error = getString(R.string.offline_failed_msg, "the file is too large for this phone");
                } finally {
                    if (raw != null) {
                        try {
                            raw.close();
                        } catch (IOException ignored) {
                            // nothing to do
                        }
                    }
                }
                job.finish(result, error);
            }
        }, "offline-map-import").start();
    }

    private TextView sectionTitle(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setTextColor(color(R.color.primary));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, (int) (14 * density), 0, (int) (2 * density));
        return t;
    }

    private void addRadios(RadioGroup g, int[] labels, int checked) {
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            g.addView(b);
            if (i == checked) {
                b.setChecked(true);
            }
        }
        if (checked < 0 && g.getChildCount() > 0) {
            ((RadioButton) g.getChildAt(0)).setChecked(true);
        }
    }

    private static int checkedIndex(RadioGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            if (((RadioButton) g.getChildAt(i)).isChecked()) {
                return i;
            }
        }
        return 0;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) {
                return i;
            }
        }
        return 0;
    }

    private CheckBox checkBox(int label, final String key) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cb.setChecked(prefs.getBoolean(key, true));
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
                applyPreferences();
                if (Prefs.SHOW_HISTORY.equals(key)) {
                    reloadHistory();
                }
            }
        });
        return cb;
    }

    // ------------------------------------------------------------------ map events

    @Override
    public boolean singleTapConfirmedHelper(GeoPoint p) {
        hideDetails();
        return false;
    }

    @Override
    public boolean longPressHelper(final GeoPoint p) {
        final String coords = String.format(Locale.US, "%.6f, %.6f", p.getLatitude(), p.getLongitude());
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.repeater_set_title)
                .setMessage(getString(R.string.repeater_set_msg, coords))
                .setPositiveButton(R.string.repeater_set, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit()
                                .putString(Prefs.REPEATER_LAT, String.format(Locale.US, "%.7f", p.getLatitude()))
                                .putString(Prefs.REPEATER_LON, String.format(Locale.US, "%.7f", p.getLongitude()))
                                .apply();
                        applyPreferences();
                    }
                })
                .setNegativeButton(R.string.cancel, null);
        if (Prefs.repeater(prefs) != null) {
            b.setNeutralButton(R.string.repeater_remove, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    prefs.edit().remove(Prefs.REPEATER_LAT).remove(Prefs.REPEATER_LON).apply();
                    applyPreferences();
                }
            });
        }
        b.show();
        return true;
    }

    @Override
    public void onPointsTapped(List<Measurement> hits) {
        showDetails(hits);
    }

    @Override
    public void onEmptyTap() {
        hideDetails();
    }

    // ------------------------------------------------------------------ details sheet

    private void hideDetails() {
        if (details.getVisibility() != View.GONE) {
            details.setVisibility(View.GONE);
            signals.setSelection(null);
            map.invalidate();
        }
    }

    private void showDetails(List<Measurement> hits) {
        int n = hits.size();
        int okN = 0;
        Double best = null;
        Double worst = null;
        boolean bySnr = Prefs.colorBySnr(prefs);
        for (Measurement m : hits) {
            if (m.ok) {
                okN++;
                Double v = signals.valueOf(m);
                if (v != null) {
                    best = best == null ? v : Math.max(best, v);
                    worst = worst == null ? v : Math.min(worst, v);
                }
            }
        }
        detailsTitle.setText(n == 1 ? getString(R.string.details_one, hits.get(0).seq) : getString(R.string.details_many, n));
        String summary = getString(R.string.details_summary, okN, n);
        if (best != null && worst != null && n > 1) {
            summary += " · best " + (bySnr ? Fmt.db(best) : Fmt.dbm(best)) + " · worst "
                    + (bySnr ? Fmt.db(worst) : Fmt.dbm(worst));
        }
        detailsSummary.setText(summary);
        detailsList.removeAllViews();
        int shown = Math.min(n, MAX_DETAIL_ROWS);
        for (int i = 0; i < shown; i++) {
            detailsList.addView(detailRow(hits.get(i)));
        }
        if (n > shown) {
            TextView more = new TextView(this);
            more.setText(getString(R.string.details_more, n - shown));
            more.setTextColor(color(R.color.text_secondary));
            more.setPadding(0, (int) (6 * density), 0, 0);
            detailsList.addView(more);
        }
        View root = findViewById(R.id.root);
        int maxH = root.getHeight() > 0 ? (int) (root.getHeight() * 0.5f) : (int) (360 * density);
        detailsScroll.setMaxHeight(maxH);
        detailsScroll.scrollTo(0, 0);
        details.setVisibility(View.VISIBLE);
    }

    private View detailRow(Measurement m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.row_bg);
        int p = (int) (12 * density);
        row.setPadding(p, (int) (10 * density), p, (int) (10 * density));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = (int) (8 * density);
        row.setLayoutParams(rowLp);

        View dot = new View(this);
        int ds = (int) (16 * density);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(ds, ds);
        dotLp.topMargin = (int) (3 * density);
        dotLp.rightMargin = (int) (12 * density);
        dot.setLayoutParams(dotLp);
        dot.setBackground(dotDrawable(signals.colorOf(m), !m.ok));
        row.addView(dot);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(col);

        StringBuilder head = new StringBuilder();
        head.append(Fmt.when(m.t)).append("  ·  ");
        if (m.ok) {
            Double v = SignalMath.linkSignal(m);
            head.append(v != null ? Fmt.dbm(v) : getString(R.string.reply));
        } else {
            head.append(getString(R.string.no_reply));
        }
        col.addView(text(head.toString(), 15, color(R.color.text_primary), true));

        if (m.ok) {
            col.addView(text(getString(R.string.details_phone, sideText(m.rssi, m.snr)), 13,
                    color(R.color.text_primary), false));
            if (m.rrssi != null) {
                col.addView(text(getString(R.string.details_repeater, sideText(m.rrssi, m.rsnr)), 13,
                        color(R.color.text_primary), false));
            } else {
                col.addView(text(getString(R.string.details_repeater_na), 13, color(R.color.text_secondary), false));
            }
        }
        List<String> extra = new ArrayList<>();
        extra.add("#" + m.seq);
        if (m.rtt != null) {
            extra.add("RTT " + Fmt.num(m.rtt / 1000.0, 2) + " s");
        }
        if (m.crc > 0) {
            extra.add(m.crc + " CRC error" + (m.crc > 1 ? "s" : ""));
        }
        if (m.acc != null) {
            extra.add("GPS ±" + Math.round(m.acc) + " m");
        }
        if (m.spd != null) {
            extra.add(Fmt.num(m.spd * 3.6, 0) + " km/h");
        }
        Double[] rep = Prefs.repeater(prefs);
        if (rep != null && m.hasPosition()) {
            extra.add(Fmt.distance(SignalMath.distanceM(m.lat, m.lon, rep[0], rep[1])) + " to repeater");
        }
        if (m.via != null) {
            extra.add("via " + ("wifi".equals(m.via) ? "Wi-Fi" : "USB"));
        }
        StringBuilder sb = new StringBuilder();
        for (String e : extra) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(e);
        }
        TextView meta = text(sb.toString(), 12, color(R.color.text_secondary), false);
        meta.setPadding(0, (int) (3 * density), 0, 0);
        col.addView(meta);
        return row;
    }

    private String sideText(Integer rssi, Double snr) {
        if (rssi == null) {
            return "–";
        }
        String s = "RSSI " + Fmt.num(rssi, 0) + " dBm";
        if (snr != null) {
            s += "  ·  SNR " + Fmt.num(snr, 2) + " dB";
        }
        return s;
    }

    private TextView text(String s, int sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        t.setGravity(Gravity.START);
        return t;
    }

    // ------------------------------------------------------------------ crash report

    private void showCrashReportIfAny() {
        final File f = new File(getFilesDir(), App.CRASH_FILE);
        if (!f.isFile()) {
            return;
        }
        String report;
        try {
            report = readSmallFile(f);
        } catch (IOException e) {
            report = String.valueOf(e);
        }
        final String text = report;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = (int) (20 * density);
        tv.setPadding(pad, (int) (8 * density), pad, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(R.string.crash_msg)
                .setView(sv)
                .setPositiveButton(R.string.share, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_SUBJECT, "LoRa Signal Map crash report");
                        i.putExtra(Intent.EXTRA_TEXT, text);
                        safeStart(Intent.createChooser(i, getString(R.string.share)));
                    }
                })
                .setNegativeButton(R.string.close, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                })
                .show();
    }

    private static String readSmallFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }

    /** Small helper so the activity does not depend on UsbLink internals. */
    static final class UsbLinkHelper {
        private UsbLinkHelper() {
        }

        static boolean isCdc(UsbDevice dev) {
            return UsbLink.hasCdcPort(dev);
        }
    }
}
