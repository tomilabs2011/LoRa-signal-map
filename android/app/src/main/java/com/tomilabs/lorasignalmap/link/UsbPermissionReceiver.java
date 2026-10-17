package com.tomilabs.lorasignalmap.link;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;

/** Receives the answer of the USB permission dialog (explicit, non-exported receiver). */
public final class UsbPermissionReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.tomilabs.lorasignalmap.USB_PERMISSION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION.equals(intent.getAction())) {
            UsbLink.onPermissionResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
        }
    }
}
