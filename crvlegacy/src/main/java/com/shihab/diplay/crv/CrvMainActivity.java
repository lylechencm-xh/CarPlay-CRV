package com.shihab.diplay.crv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;

public final class CrvMainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.shihab.diplay.crv.USB_PERMISSION";
    private UsbManager usbManager;
    private TextView status;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device == null) {
                    setStatus("USB permission result: no device");
                    return;
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    setStatus(describeDevice("USB ready", device));
                } else {
                    setStatus(describeDevice("USB permission denied", device));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                inspectAndRequest(device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                setStatus("iPhone/USB device disconnected");
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);

        UsbDevice attached = (UsbDevice) getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (attached != null) {
            inspectAndRequest(attached);
        } else {
            scanAttachedDevices();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int padding = dp(24);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("DiPlay CR-V · Android 4.4");
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);

        status = new TextView(this);
        status.setTextSize(18f);
        status.setGravity(Gravity.CENTER);
        status.setText("Waiting for wired iPhone USB connection…");

        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(24);
        root.addView(status, statusParams);
        setContentView(root);
    }

    private void scanAttachedDevices() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) {
            setStatus("Waiting for wired iPhone USB connection…");
            return;
        }
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == 0x05AC) {
                inspectAndRequest(device);
                return;
            }
        }
        setStatus("USB device detected, but no Apple device is attached");
    }

    private void inspectAndRequest(UsbDevice device) {
        if (device == null) {
            setStatus("USB attach event did not include a device");
            return;
        }
        if (device.getVendorId() != 0x05AC) {
            setStatus(describeDevice("Non-Apple USB device", device));
            return;
        }
        if (usbManager.hasPermission(device)) {
            setStatus(describeDevice("Apple USB device ready", device));
            return;
        }

        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        usbManager.requestPermission(device, permissionIntent);
        setStatus(describeDevice("Apple device detected; requesting USB permission", device));
    }

    private String describeDevice(String prefix, UsbDevice device) {
        return prefix
                + "\nVID:PID = "
                + hex4(device.getVendorId()) + ":" + hex4(device.getProductId())
                + "\nInterfaces = " + device.getInterfaceCount()
                + "\nAPI 19 wired-first compatibility path";
    }

    private String hex4(int value) {
        return String.format("%04X", value & 0xFFFF);
    }

    private void setStatus(String text) {
        if (status != null) {
            status.setText(text);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(usbReceiver);
        super.onDestroy();
    }
}
