#include <jni.h>

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <string.h>

#define MAX_USB_INTERFACES 32

static int negative_errno(void) {
    return errno > 0 ? -errno : -EIO;
}

static int disconnect_kernel_driver(int fd, int interface_number) {
    struct usbdevfs_ioctl command;
    memset(&command, 0, sizeof(command));
    command.ifno = interface_number;
    command.ioctl_code = USBDEVFS_DISCONNECT;
    command.data = NULL;
    if (ioctl(fd, USBDEVFS_IOCTL, &command) == 0) {
        return 0;
    }
    if (errno == ENODATA || errno == EINVAL || errno == ENODEV) {
        return 0;
    }
    return negative_errno();
}

static int set_configuration_kernel_aware(int fd, int configuration_value) {
    int value = configuration_value;
    if (ioctl(fd, USBDEVFS_SETCONFIGURATION, &value) == 0) {
        return 0;
    }
    if (errno != EBUSY) {
        return negative_errno();
    }

    /*
     * Android 4.2.2 can hand us an iPhone whose old configuration is already owned by
     * kernel interface drivers (notably cdc_ncm). usbfs refuses SETCONFIGURATION while
     * any old interface remains bound. Detach only drivers on this already-authorized
     * iPhone fd, then let usb_set_configuration() tear down the old interfaces and bind
     * drivers for the requested CarPlay configuration.
     */
    for (int interface_number = 0; interface_number < MAX_USB_INTERFACES; ++interface_number) {
        const int result = disconnect_kernel_driver(fd, interface_number);
        if (result != 0) {
            return result;
        }
    }

    value = configuration_value;
    if (ioctl(fd, USBDEVFS_SETCONFIGURATION, &value) == 0) {
        return 0;
    }
    return negative_errno();
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbFsIoctl_nativeSetConfiguration(
        JNIEnv *env,
        jclass clazz,
        jint file_descriptor,
        jint configuration_value) {
    (void) env;
    (void) clazz;
    return set_configuration_kernel_aware((int) file_descriptor, (int) configuration_value);
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbFsIoctl_nativeSetInterface(
        JNIEnv *env,
        jclass clazz,
        jint file_descriptor,
        jint interface_number,
        jint alternate_setting) {
    (void) env;
    (void) clazz;

    struct usbdevfs_setinterface selection;
    selection.interface = (unsigned int) interface_number;
    selection.altsetting = (unsigned int) alternate_setting;
    if (ioctl((int) file_descriptor, USBDEVFS_SETINTERFACE, &selection) == 0) {
        return 0;
    }
    return negative_errno();
}
