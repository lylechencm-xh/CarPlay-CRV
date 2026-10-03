package com.shilapi.xcertplay

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore

class CrvAirPlayState(context: Context) {
    private val prefs = context.getSharedPreferences("crv_carplay", Context.MODE_PRIVATE)

    val identity: AirPlayIdentity by lazy {
        val privateKey = prefs.getString("identity.private", null)?.decode64()
        val publicKey = prefs.getString("identity.public", null)?.decode64()
        val pairingId = prefs.getString("identity.pairingId", null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            AirPlayIdentity(privateKey, publicKey, pairingId)
        } else {
            AirPlayIdentity.generate().also { next ->
                prefs.edit()
                    .putString("identity.private", next.privateKey.encode64())
                    .putString("identity.public", next.publicKey.encode64())
                    .putString("identity.pairingId", next.pairingId)
                    .commit()
            }
        }
    }

    fun pairingStore(): PairingStore {
        val store = PairingStore { id, key ->
            prefs.edit().putString("pair.$id", key.encode64()).apply()
        }
        for ((key, value) in prefs.all) {
            if (key.startsWith("pair.") && value is String) {
                runCatching { store.save(key.removePrefix("pair."), value.decode64()) }
            }
        }
        return store
    }

    private fun ByteArray.encode64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decode64(): ByteArray =
        Base64.decode(this, Base64.NO_WRAP)
}
