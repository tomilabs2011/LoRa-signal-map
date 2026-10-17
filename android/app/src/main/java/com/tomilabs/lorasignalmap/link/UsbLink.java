package com.tomilabs.lorasignalmap.link;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * USB CDC-ACM link to the Pico (CircuitPython exposes one or two CDC serial ports).
 * Every CDC port of every attached CDC device is opened; lines from all of them are reported.
 * All bookkeeping runs on the worker handler's thread; each port has its own reader thread.
 */
public final class UsbLink {
    private static final String TAG = "UsbLink";
    private static final int POLL_MS = 1500;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile UsbLink active;

    private final Context ctx;
    private final UsbManager usb;
    private final Handler worker;
    private final LinkListener listener;
    private final Map<String, OpenDevice> open = new HashMap<>();
    private final Set<String> permissionAsked = new HashSet<>();
    private boolean running;
    private BroadcastReceiver detachReceiver;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                pollDevices();
            } catch (RuntimeException e) {
                Log.w(TAG, "poll failed", e);
            }
            worker.removeCallbacks(this);
            worker.postDelayed(this, POLL_MS);
        }
    };

    public UsbLink(Context ctx, Handler worker, LinkListener listener) {
        this.ctx = ctx.getApplicationContext();
        this.usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        this.worker = worker;
        this.listener = listener;
    }

    /** Must be called on the worker thread. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        active = this;
        detachReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                worker.post(poll);
            }
        };
        IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(detachReceiver, f, null, worker, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(detachReceiver, f, null, worker);
            }
        } catch (RuntimeException e) {
            detachReceiver = null; // polling still notices changes
        }
        worker.post(poll);
    }

    /** Must be called on the worker thread. */
    public void stop() {
        running = false;
        if (active == this) {
            active = null;
        }
        worker.removeCallbacks(poll);
        if (detachReceiver != null) {
            try {
                ctx.unregisterReceiver(detachReceiver);
            } catch (RuntimeException ignored) {
                // not registered
            }
            detachReceiver = null;
        }
        for (OpenDevice d : new ArrayList<>(open.values())) {
            close(d, "stopped");
        }
        open.clear();
    }

    public boolean isUp() {
        return !open.isEmpty();
    }

    /** Called by {@link UsbPermissionReceiver} with the system's answer. */
    static void onPermissionResult(boolean granted) {
        UsbLink l = active;
        if (l != null) {
            l.worker.post(l.poll);
        }
    }

    /** Remember that a port carries protocol lines; commands then go only there. */
    public void markProtocolPort(String portName) {
        for (OpenDevice d : open.values()) {
            for (Port p : d.ports) {
                if (p.name.equals(portName)) {
                    p.protocol = true;
                }
            }
        }
    }

    /** Sends a line to protocol ports (or to every port if none is known yet). Worker thread. */
    public boolean send(String line) {
        byte[] data = (line + "\n").getBytes(UTF8);
        boolean anyProtocol = false;
        for (OpenDevice d : open.values()) {
            for (Port p : d.ports) {
                anyProtocol |= p.protocol;
            }
        }
        boolean sent = false;
        for (OpenDevice d : open.values()) {
            for (Port p : d.ports) {
                if (anyProtocol && !p.protocol) {
                    continue;
                }
                int r = d.conn.bulkTransfer(p.out, data, data.length, 1000);
                sent |= r == data.length;
            }
        }
        return sent;
    }

    // ------------------------------------------------------------------ internals

    private void pollDevices() {
        Map<String, UsbDevice> attached = usb.getDeviceList();
        // forget devices that went away
        for (OpenDevice d : new ArrayList<>(open.values())) {
            if (!attached.containsKey(d.device.getDeviceName()) || d.failed) {
                close(d, d.failed ? "i/o error" : "unplugged");
            }
        }
        permissionAsked.retainAll(attached.keySet());
        for (UsbDevice dev : attached.values()) {
            if (open.containsKey(dev.getDeviceName()) || findCdcPorts(dev).isEmpty()) {
                continue;
            }
            if (usb.hasPermission(dev)) {
                openDevice(dev);
            } else if (permissionAsked.add(dev.getDeviceName())) {
                requestPermission(dev);
            }
        }
    }

    private void requestPermission(UsbDevice dev) {
        try {
            Intent i = new Intent(ctx, UsbPermissionReceiver.class);
            i.setAction(UsbPermissionReceiver.ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE; // the system adds the result extras
            }
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i, flags);
            usb.requestPermission(dev, pi);
        } catch (RuntimeException e) {
            Log.w(TAG, "permission request failed", e);
        }
    }

    public static boolean hasCdcPort(UsbDevice dev) {
        return !findCdcPorts(dev).isEmpty();
    }

    /** Pairs of (communication interface, data interface) indexes of CDC-ACM ports. */
    static List<int[]> findCdcPorts(UsbDevice dev) {
        List<int[]> out = new ArrayList<>();
        int n = dev.getInterfaceCount();
        for (int i = 0; i < n; i++) {
            UsbInterface c = dev.getInterface(i);
            if (c.getInterfaceClass() != UsbConstants.USB_CLASS_COMM || c.getInterfaceSubclass() != 2) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                UsbInterface d = dev.getInterface(j);
                if (d.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) {
                    break;
                }
                if (d.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA && bulkIn(d) != null && bulkOut(d) != null) {
                    out.add(new int[]{i, j});
                    break;
                }
            }
        }
        return out;
    }

    private static UsbEndpoint bulkIn(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection() == UsbConstants.USB_DIR_IN) {
                return e;
            }
        }
        return null;
    }

    private static UsbEndpoint bulkOut(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection() == UsbConstants.USB_DIR_OUT) {
                return e;
            }
        }
        return null;
    }

    public static String describe(UsbDevice dev) {
        String name = null;
        try {
            name = dev.getProductName();
        } catch (RuntimeException ignored) {
            // some devices refuse string descriptors
        }
        if (name == null || name.trim().isEmpty()) {
            name = "USB serial";
        }
        return String.format(Locale.US, "%s (%04x:%04x)", name.trim(), dev.getVendorId(), dev.getProductId());
    }

    private void openDevice(UsbDevice dev) {
        List<int[]> pairs = findCdcPorts(dev);
        UsbDeviceConnection conn;
        try {
            conn = usb.openDevice(dev);
        } catch (RuntimeException e) {
            conn = null;
        }
        if (conn == null) {
            Log.w(TAG, "openDevice failed for " + dev.getDeviceName());
            return;
        }
        OpenDevice od = new OpenDevice(dev, conn, describe(dev));
        for (int[] pair : pairs) {
            UsbInterface ctrl = dev.getInterface(pair[0]);
            UsbInterface data = dev.getInterface(pair[1]);
            if (!conn.claimInterface(ctrl, true) || !conn.claimInterface(data, true)) {
                Log.w(TAG, "claimInterface failed on " + ctrl.getId());
                continue;
            }
            // 115200 8N1 (ignored by CircuitPython, but never 1200: that reboots it to the bootloader)
            byte[] lineCoding = {(byte) 0x00, (byte) 0xC2, (byte) 0x01, 0x00, 0x00, 0x00, 0x08};
            conn.controlTransfer(0x21, 0x20, 0, ctrl.getId(), lineCoding, lineCoding.length, 500);
            // DTR + RTS: CircuitPython only sends data while DTR is set
            conn.controlTransfer(0x21, 0x22, 0x03, ctrl.getId(), null, 0, 500);
            Port p = new Port(od, ctrl, data, bulkIn(data), bulkOut(data),
                    dev.getDeviceName() + "#" + ctrl.getId());
            od.ports.add(p);
        }
        if (od.ports.isEmpty()) {
            conn.close();
            return;
        }
        open.put(dev.getDeviceName(), od);
        for (Port p : od.ports) {
            p.start();
        }
        listener.onLinkState(LinkListener.USB, true, od.label);
    }

    private void close(OpenDevice d, String why) {
        open.remove(d.device.getDeviceName());
        for (Port p : d.ports) {
            p.running = false;
        }
        for (Port p : d.ports) {
            try {
                d.conn.controlTransfer(0x21, 0x22, 0x00, p.ctrl.getId(), null, 0, 200);
            } catch (RuntimeException ignored) {
                // device may already be gone
            }
        }
        for (Port p : d.ports) {
            if (p.thread != null) {
                try {
                    p.thread.join(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                d.conn.releaseInterface(p.data);
                d.conn.releaseInterface(p.ctrl);
            } catch (RuntimeException ignored) {
                // device may already be gone
            }
        }
        try {
            d.conn.close();
        } catch (RuntimeException ignored) {
            // already closed
        }
        listener.onLinkState(LinkListener.USB, !open.isEmpty(), d.label + ": " + why);
    }

    private static final class OpenDevice {
        final UsbDevice device;
        final UsbDeviceConnection conn;
        final String label;
        final List<Port> ports = new ArrayList<>();
        volatile boolean failed;

        OpenDevice(UsbDevice device, UsbDeviceConnection conn, String label) {
            this.device = device;
            this.conn = conn;
            this.label = label;
        }
    }

    private final class Port implements Runnable {
        final OpenDevice dev;
        final UsbInterface ctrl;
        final UsbInterface data;
        final UsbEndpoint in;
        final UsbEndpoint out;
        final String name;
        final LineAssembler assembler = new LineAssembler(8192);
        volatile boolean running;
        volatile boolean protocol;
        Thread thread;

        Port(OpenDevice dev, UsbInterface ctrl, UsbInterface data, UsbEndpoint in, UsbEndpoint out, String name) {
            this.dev = dev;
            this.ctrl = ctrl;
            this.data = data;
            this.in = in;
            this.out = out;
            this.name = name;
        }

        void start() {
            running = true;
            thread = new Thread(this, "usb-rx " + name);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            // Read exactly one packet per transfer: a transfer that times out half-filled
            // would lose its data inside the kernel.
            byte[] buf = new byte[Math.max(64, in.getMaxPacketSize())];
            int fastErrors = 0;
            while (running) {
                long t0 = SystemClock.uptimeMillis();
                int n = dev.conn.bulkTransfer(in, buf, buf.length, 250);
                if (n > 0) {
                    fastErrors = 0;
                    final long now = SystemClock.elapsedRealtimeNanos();
                    assembler.feed(buf, n, new LineAssembler.Sink() {
                        @Override
                        public void onLine(String line) {
                            listener.onLine(LinkListener.USB, name, line, now);
                        }
                    });
                } else if (n < 0 && SystemClock.uptimeMillis() - t0 < 20) {
                    // immediate failure (not a timeout): device unplugged or broken
                    fastErrors++;
                    if (fastErrors > 50) {
                        dev.failed = true;
                        worker.post(poll);
                        return;
                    }
                    SystemClock.sleep(20);
                }
            }
        }
    }
}
