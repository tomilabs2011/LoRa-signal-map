package com.tomilabs.lorasignalmap.link;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wi-Fi link: UDP on port 47820. The Pico joins the phone's hotspot and sends to the
 * gateway (this phone) until it has heard our "hello"; then it answers to our address.
 */
public final class UdpLink {
    public static final int PORT = 47820;
    private static final String TAG = "UdpLink";
    private static final long TICK_MS = 3000;
    private static final long DOWN_AFTER_NS = 15_000_000_000L;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Handler worker;
    private final LinkListener listener;
    private final byte[] hello;
    private volatile boolean running;
    private volatile DatagramSocket socket;
    private volatile InetSocketAddress peer;
    private volatile long lastRxNanos;
    private volatile boolean up;
    private Thread rxThread;
    private String lastError;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                ensureSocket();
                sendHello();
                checkDown();
            } catch (RuntimeException e) {
                Log.w(TAG, "tick failed", e);
            }
            worker.removeCallbacks(this);
            worker.postDelayed(this, TICK_MS);
        }
    };

    public UdpLink(Handler worker, LinkListener listener, String helloLine) {
        this.worker = worker;
        this.listener = listener;
        this.hello = (helloLine + "\n").getBytes(UTF8);
    }

    /** Worker thread. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker.post(tick);
    }

    /** Worker thread. */
    public void stop() {
        running = false;
        worker.removeCallbacks(tick);
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            s.close();
        }
        if (rxThread != null) {
            try {
                rxThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            rxThread = null;
        }
        if (up) {
            up = false;
            listener.onLinkState(LinkListener.WIFI, false, "stopped");
        }
    }

    public boolean isUp() {
        return up;
    }

    public String getLastError() {
        return lastError;
    }

    /** Sends a line to the device if we know where it is. Worker thread. */
    public boolean send(String line) {
        DatagramSocket s = socket;
        InetSocketAddress p = peer;
        if (s == null || p == null || !up) {
            return false;
        }
        byte[] data = (line + "\n").getBytes(UTF8);
        try {
            s.send(new DatagramPacket(data, data.length, p));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void ensureSocket() {
        if (socket != null) {
            return;
        }
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(PORT));
            s.setSoTimeout(1000);
        } catch (IOException | RuntimeException e) {
            if (s != null) {
                s.close();
            }
            lastError = "UDP port " + PORT + ": " + e.getMessage();
            return;
        }
        lastError = null;
        socket = s;
        final DatagramSocket mine = s;
        rxThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop(mine);
            }
        }, "udp-rx");
        rxThread.setDaemon(true);
        rxThread.start();
    }

    private void receiveLoop(DatagramSocket s) {
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running && socket == s) {
            try {
                p.setLength(buf.length);
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (running && socket == s) {
                    SystemClock.sleep(300);
                }
                continue;
            }
            long now = SystemClock.elapsedRealtimeNanos();
            SocketAddress from = p.getSocketAddress();
            if (!(from instanceof InetSocketAddress)) {
                continue;
            }
            InetSocketAddress addr = (InetSocketAddress) from;
            String text = new String(p.getData(), p.getOffset(), p.getLength(), UTF8);
            if (text.indexOf('{') < 0) {
                continue; // not one of ours (and not our own hello either: those carry "cmd")
            }
            boolean fromDevice = text.contains("\"t\"");
            if (!fromDevice) {
                continue; // our own broadcast hello looping back, or another phone
            }
            peer = addr;
            lastRxNanos = now;
            final String ip = addr.getAddress().getHostAddress();
            worker.post(new Runnable() {
                @Override
                public void run() {
                    if (!up && running) {
                        up = true;
                        listener.onLinkState(LinkListener.WIFI, true, ip);
                    }
                }
            });
            for (String line : text.split("\n")) {
                line = line.trim();
                if (!line.isEmpty()) {
                    listener.onLine(LinkListener.WIFI, ip, line, now);
                }
            }
        }
    }

    private void checkDown() {
        if (up && SystemClock.elapsedRealtimeNanos() - lastRxNanos > DOWN_AFTER_NS) {
            up = false;
            listener.onLinkState(LinkListener.WIFI, false, "no data for 15 s");
        }
    }

    private void sendHello() {
        DatagramSocket s = socket;
        if (s == null) {
            return;
        }
        List<InetAddress> targets = new ArrayList<>();
        InetSocketAddress p = peer;
        if (p != null) {
            targets.add(p.getAddress());
        }
        targets.addAll(broadcastAddresses());
        for (InetAddress a : targets) {
            try {
                s.send(new DatagramPacket(hello, hello.length, a, PORT));
            } catch (IOException | RuntimeException ignored) {
                // interface without a route etc.
            }
        }
    }

    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) {
                        out.add(b);
                    }
                }
            }
        } catch (SocketException | RuntimeException ignored) {
            // no interfaces available
        }
        return out;
    }
}
