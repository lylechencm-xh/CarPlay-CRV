package com.shilapi.xcertplay

/** Compare USB enumeration identity, never merely the Apple vendor ID. */
internal data class CrvUsbIdentity(val name: String, val id: Int, val vendor: Int, val product: Int)

internal fun shouldHandleCrvUsbDetach(
    detached: CrvUsbIdentity,
    active: CrvUsbIdentity?,
    present: Collection<CrvUsbIdentity>?,
): Boolean = active == detached && (present == null || detached !in present)
