package com.tomilabs.lorasignalmap.link;

/** Callbacks from the links. Called on link threads; implementations must hand off quickly. */
public interface LinkListener {
    String USB = "usb";
    String WIFI = "wifi";

    /**
     * A text line arrived.
     *
     * @param port identifies the physical port (USB interface or UDP sender) the line came from
     * @param rxElapsedNanos SystemClock.elapsedRealtimeNanos() when the line was complete
     */
    void onLine(String link, String port, String line, long rxElapsedNanos);

    void onLinkState(String link, boolean up, String detail);
}
