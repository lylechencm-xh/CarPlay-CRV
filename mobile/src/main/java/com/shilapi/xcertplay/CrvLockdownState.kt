package com.shilapi.xcertplay

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.transport.LockdownPairRecord

/**
 * Persists the iPhone Lockdown pair record in app-private storage.
 *
 * Android 4.4 has no EncryptedSharedPreferences; these values remain inside this app's
 * private data directory and are never logged or exported.
 */
class CrvLockdownState(context: Context) {
    private val prefs = context.getSharedPreferences("crv_lockdown_pair", Context.MODE_PRIVATE)

    fun load(): LockdownPairRecord? {
        val hostId = prefs.getString("hostId", null) ?: return null
        val systemBuid = prefs.getString("systemBuid", null) ?: return null
        val wifiMac = prefs.getString("wifiMac", null) ?: return null
        return runCatching {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = bytes("devicePublicKey"),
                deviceCertificatePem = bytes("deviceCertificate"),
                hostPrivateKeyPem = bytes("hostPrivateKey"),
                hostCertificatePem = bytes("hostCertificate"),
                rootPrivateKeyPem = bytes("rootPrivateKey"),
                rootCertificatePem = bytes("rootCertificate"),
            )
        }.getOrNull()
    }

    fun save(record: LockdownPairRecord) {
        val saved = prefs.edit()
            .putString("hostId", record.hostId)
            .putString("systemBuid", record.systemBuid)
            .putString("wifiMac", record.wifiMacAddress)
            .putString("devicePublicKey", record.devicePublicKeyPem.encode64())
            .putString("deviceCertificate", record.deviceCertificatePem.encode64())
            .putString("hostPrivateKey", record.hostPrivateKeyPem.encode64())
            .putString("hostCertificate", record.hostCertificatePem.encode64())
            .putString("rootPrivateKey", record.rootPrivateKeyPem.encode64())
            .putString("rootCertificate", record.rootCertificatePem.encode64())
            .commit()
        check(saved) { "Could not save iPhone trust record to device storage" }
        check(load()?.hostId == record.hostId) {
            "Saved iPhone trust record could not be read back"
        }
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun bytes(key: String): ByteArray {
        val value = prefs.getString(key, null) ?: error("Missing Lockdown pair field: $key")
        return Base64.decode(value, Base64.NO_WRAP)
    }

    private fun ByteArray.encode64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)
}
