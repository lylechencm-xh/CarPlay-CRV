package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import java.io.Closeable
import java.lang.reflect.Method

/**
 * Passive observer for the factory Honda/Mitsubishi MediaCore service.
 *
 * This bridge registers only the factory listener Binder. It never invokes iAPauthStart, opens
 * /dev/jdev, claims USB interfaces, or forwards iAP payloads. The observed storage handle is useful
 * for proving whether the factory stack sees the same iPhone before an explicit ownership handoff
 * is implemented.
 */
internal class CrvHondaMediaCoreMonitor(
    context: Context,
    private val report: (String) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val callback = MediaCoreCallback()

    @Volatile private var closed = false
    @Volatile private var bound = false
    @Volatile private var registered = false
    @Volatile private var remote: IBinder? = null
    @Volatile private var factoryLoader: ClassLoader? = null
    @Volatile private var latestIap2Handle: Int? = null

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            registered = false
            remote = null
            report("Honda MediaCore Binder died")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) return
            val descriptor = runCatching { service.interfaceDescriptor }.getOrNull()
            report(
                "Honda MediaCore connected component=${name.flattenToShortString()} " +
                    "descriptor=${descriptor ?: "unavailable"} alive=${service.isBinderAlive}",
            )
            if (descriptor != SERVICE_DESCRIPTOR) {
                report("Honda MediaCore rejected unexpected descriptor")
                return
            }
            remote = service
            runCatching { service.linkToDeath(deathRecipient, 0) }
            registered = transactListener(service, TRANSACTION_REGISTER_CALLBACK)
            report("Honda MediaCore passive-listener registered=$registered activeAuth=false")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            registered = false
            remote = null
            report("Honda MediaCore disconnected component=${name.flattenToShortString()}")
        }
    }

    fun start() {
        if (closed || bound) return
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(MEDIA_CORE_PACKAGE, 0)
        }.getOrElse { error ->
            report("Honda MediaCore monitor unavailable type=${error.javaClass.simpleName}")
            return
        }
        val appInfo = packageInfo.applicationInfo
        val source = appInfo?.sourceDir.orEmpty()
        val trusted = appInfo != null &&
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                source.startsWith("/system/") ||
                source.startsWith("/vendor/"))
        if (!trusted) {
            report("Honda MediaCore monitor rejected non-system package source=$source")
            return
        }

        factoryLoader = runCatching {
            appContext.createPackageContext(
                MEDIA_CORE_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            ).classLoader
        }.onFailure { error ->
            report("Honda MediaCore class loader unavailable type=${error.javaClass.simpleName}")
        }.getOrNull()
        if (factoryLoader == null) return

        val intent = Intent().setClassName(MEDIA_CORE_PACKAGE, MEDIA_CORE_SERVICE)
        bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { error ->
            report("Honda MediaCore bind failed type=${error.javaClass.simpleName}")
        }.getOrDefault(false)
        report("Honda MediaCore bind requested=$bound mode=passive")
    }

    override fun close() {
        if (closed) return
        closed = true
        val service = remote
        if (registered && service != null && service.isBinderAlive) {
            val removed = transactListener(service, TRANSACTION_UNREGISTER_CALLBACK)
            report("Honda MediaCore passive-listener unregistered=$removed")
        }
        registered = false
        remote = null
        if (service != null) runCatching { service.unlinkToDeath(deathRecipient, 0) }
        if (bound) {
            runCatching { appContext.unbindService(connection) }
                .onFailure { error ->
                    report("Honda MediaCore unbind failed type=${error.javaClass.simpleName}")
                }
        }
        bound = false
        factoryLoader = null
        latestIap2Handle = null
    }

    private fun transactListener(service: IBinder, transaction: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            if (!service.transact(transaction, data, reply, 0)) return false
            reply.readException()
            true
        } catch (error: Throwable) {
            report(
                "Honda MediaCore listener transaction=$transaction failed " +
                    "type=${error.javaClass.simpleName}",
            )
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private inner class MediaCoreCallback : Binder(), IInterface {
        init {
            attachInterface(this, LISTENER_DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(LISTENER_DESCRIPTOR)
                return true
            }
            if (code !in FIRST_CALLBACK_TRANSACTION..LAST_CALLBACK_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            return try {
                data.enforceInterface(LISTENER_DESCRIPTOR)
                when (code) {
                    TRANSACTION_ON_STORAGE_EVENT -> onStorageEvent(data)
                    TRANSACTION_ON_IAP_AUTH_RESULT -> {
                        val result = data.readInt()
                        report("Honda MediaCore auth-result=$result observedOnly=true")
                    }
                    TRANSACTION_ON_IAP2_CONNECTED -> onIap2Connected(data)
                }
                reply?.writeNoException()
                true
            } catch (error: Throwable) {
                report(
                    "Honda MediaCore callback code=$code decode failed " +
                        "type=${error.javaClass.simpleName}",
                )
                reply?.writeNoException()
                true
            }
        }

        private fun onStorageEvent(data: Parcel) {
            val event = data.readInt()
            val device = readFactoryParcelable(data, STORAGE_DEVICE_CLASS)
            val result = data.readInt()
            if (device == null) {
                report("Honda MediaCore storage event=$event device=absent result=$result")
                return
            }
            val handle = invokeInt(device, "getmHandle")
            val type = invokeInt(device, "getmType")
            val transport = invokeInt(device, "getmTransport")
            val usbPort = invokeInt(device, "getmUsbPortNumber")
            val serialPresent = invokeString(device, "getmSserialNumber").isNullOrBlank().not()
            if (type == MEDIA_DEVICE_IOS_IAP2 && result == MEDIA_CORE_OK && handle != null) {
                when (event) {
                    STORAGE_ATTACHED -> latestIap2Handle = handle
                    STORAGE_DETACHED -> if (latestIap2Handle == handle) latestIap2Handle = null
                }
            }
            report(
                "Honda MediaCore storage event=$event result=$result handle=${handle ?: -1} " +
                    "type=${type ?: -1} transport=${transport ?: -1} " +
                    "usbPort=${usbPort ?: -1} serialPresent=$serialPresent " +
                    "iap2=${type == MEDIA_DEVICE_IOS_IAP2} " +
                    "candidateHandle=${latestIap2Handle ?: -1}",
            )
        }

        private fun onIap2Connected(data: Parcel) {
            val state = data.readInt()
            val device = readFactoryParcelable(data, USB_DEVICE_INFO_CLASS)
            val vendorId = device?.let { invokeInt(it, "getmVendorId") }
            val productId = device?.let { invokeInt(it, "getmProductId") }
            val port = device?.let { invokeInt(it, "getmPort") }
            report(
                "Honda MediaCore iap2 state=$state connected=${state == IAP2_CONNECTED} " +
                    "vid=${vendorId?.toString(16) ?: "unknown"} " +
                    "pid=${productId?.toString(16) ?: "unknown"} port=${port ?: -1}",
            )
        }
    }

    private fun readFactoryParcelable(data: Parcel, className: String): Any? {
        if (data.readInt() == 0) return null
        val loader = factoryLoader ?: return null
        val type = Class.forName(className, true, loader)
        val creator = type.getField("CREATOR").get(null) as? Parcelable.Creator<*> ?: return null
        return creator.createFromParcel(data)
    }

    private fun invokeInt(target: Any, name: String): Int? =
        invokeNoArgs(target, name) as? Int

    private fun invokeString(target: Any, name: String): String? =
        invokeNoArgs(target, name) as? String

    private fun invokeNoArgs(target: Any, name: String): Any? = runCatching {
        val method: Method = target.javaClass.getMethod(name)
        method.invoke(target)
    }.getOrNull()

    private companion object {
        const val MEDIA_CORE_PACKAGE = "com.mitsubishielectric.ada.framework.mcservice"
        const val MEDIA_CORE_SERVICE =
            "com.mitsubishielectric.ada.framework.mcservice.MediaCoreService"
        const val SERVICE_DESCRIPTOR =
            "com.mitsubishielectric.ada.framework.mcservice.IMediaCoreService"
        const val LISTENER_DESCRIPTOR =
            "com.mitsubishielectric.ada.framework.mcservice.IMediaCoreServiceListener"
        const val STORAGE_DEVICE_CLASS =
            "com.mitsubishielectric.ada.framework.mcservice.StorageDevice"
        const val USB_DEVICE_INFO_CLASS =
            "com.mitsubishielectric.ada.framework.mcservice.UsbDeviceInfo"

        // Verified against the factory MediaCoreServiceLib.odex shipped with this CR-V image.
        const val TRANSACTION_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 37
        const val TRANSACTION_UNREGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 38
        const val TRANSACTION_ON_STORAGE_EVENT = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_ON_IAP_AUTH_RESULT = IBinder.FIRST_CALL_TRANSACTION + 18
        const val TRANSACTION_ON_IAP2_CONNECTED = IBinder.FIRST_CALL_TRANSACTION + 39
        const val FIRST_CALLBACK_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION
        const val LAST_CALLBACK_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 44

        const val MEDIA_DEVICE_IOS_IAP2 = 1
        const val IAP2_CONNECTED = 0
        const val MEDIA_CORE_OK = 0
        const val STORAGE_ATTACHED = 0
        const val STORAGE_DETACHED = 1
    }
}
