package com.shilapi.xcertplay.transport;

/**
 * Tiny JNI bridge for usbfs management ioctls that Android did not expose in Java until API21.
 *
 * The USB file descriptor comes from UsbDeviceConnection.getFileDescriptor(), which is public
 * since API12. The native library links only against libc/JNI and is built for API17.
 */
final class UsbFsIoctl {
    static final int UNAVAILABLE = Integer.MIN_VALUE;

    private static boolean loadAttempted;
    private static boolean loaded;

    private UsbFsIoctl() {}

    static int setConfiguration(int fileDescriptor, int configurationValue) {
        if (fileDescriptor < 0 || !ensureLoaded()) return UNAVAILABLE;
        return nativeSetConfiguration(fileDescriptor, configurationValue);
    }

    static int setInterface(int fileDescriptor, int interfaceNumber, int alternateSetting) {
        if (fileDescriptor < 0 || !ensureLoaded()) return UNAVAILABLE;
        return nativeSetInterface(fileDescriptor, interfaceNumber, alternateSetting);
    }

    private static synchronized boolean ensureLoaded() {
        if (!loadAttempted) {
            loadAttempted = true;
            try {
                System.loadLibrary("crvusbfs");
                loaded = true;
            } catch (UnsatisfiedLinkError ignored) {
                loaded = false;
            } catch (SecurityException ignored) {
                loaded = false;
            }
        }
        return loaded;
    }

    private static native int nativeSetConfiguration(int fileDescriptor, int configurationValue);
    private static native int nativeSetInterface(
            int fileDescriptor,
            int interfaceNumber,
            int alternateSetting);
}
