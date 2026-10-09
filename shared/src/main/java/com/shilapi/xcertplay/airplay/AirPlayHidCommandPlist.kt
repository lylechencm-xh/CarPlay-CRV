package com.shilapi.xcertplay.airplay

/**
 * Allocation-light encoder for the fixed hidSendReport event plist.
 *
 * The generic bplist encoder is used once per HID/report-size shape to build the canonical bytes.
 * Subsequent reports only copy that template and replace the data object's payload, preserving the
 * exact dictionary/object ordering expected by the existing implementation.
 */
internal object AirPlayHidCommandPlist {
    private data class Template(
        val bytes: ByteArray,
        val reportOffset: Int,
        val reportSize: Int,
    )

    @Volatile private var touch: Template? = null
    @Volatile private var knob: Template? = null
    @Volatile private var media: Template? = null
    @Volatile private var telephony: Template? = null

    fun encode(uid: Int, report: ByteArray): ByteArray {
        val template = template(uid, report.size) ?: return generic(uid, report)
        val output = template.bytes.copyOf()
        report.copyInto(output, template.reportOffset)
        return output
    }

    /**
     * Not thread-safe; intended to live behind AirPlaySession.eventWriteLock.
     * Reuses the complete bplist body for a fixed HID type/report size.
     */
    class ReusableEncoder(
        private val uid: Int,
        reportSize: Int,
    ) {
        private val template = template(uid, reportSize)
        private val body = template?.bytes?.copyOf()

        fun encode(report: ByteArray): ByteArray {
            val current = template
            val output = body
            if (current == null || output == null || report.size != current.reportSize) {
                return generic(uid, report)
            }
            report.copyInto(output, current.reportOffset)
            return output
        }
    }


    private fun template(uid: Int, reportSize: Int): Template? {
        val cached = when (uid) {
            AirPlayHid.TOUCH_HID_UID -> touch
            AirPlayHid.KNOB_HID_UID -> knob
            AirPlayHid.MEDIA_HID_UID -> media
            AirPlayHid.TELEPHONY_HID_UID -> telephony
            else -> null
        }
        if (cached != null && cached.reportSize == reportSize) return cached

        val expectedSize = when (uid) {
            AirPlayHid.TOUCH_HID_UID -> 12
            AirPlayHid.KNOB_HID_UID -> 4
            AirPlayHid.MEDIA_HID_UID, AirPlayHid.TELEPHONY_HID_UID -> 1
            else -> return null
        }
        if (reportSize != expectedSize) return null

        return synchronized(this) {
            val rechecked = when (uid) {
                AirPlayHid.TOUCH_HID_UID -> touch
                AirPlayHid.KNOB_HID_UID -> knob
                AirPlayHid.MEDIA_HID_UID -> media
                AirPlayHid.TELEPHONY_HID_UID -> telephony
                else -> null
            }
            if (rechecked != null && rechecked.reportSize == reportSize) {
                rechecked
            } else {
                buildTemplate(uid, reportSize)?.also { built ->
                    when (uid) {
                        AirPlayHid.TOUCH_HID_UID -> touch = built
                        AirPlayHid.KNOB_HID_UID -> knob = built
                        AirPlayHid.MEDIA_HID_UID -> media = built
                        AirPlayHid.TELEPHONY_HID_UID -> telephony = built
                    }
                }
            }
        }
    }

    private fun buildTemplate(uid: Int, reportSize: Int): Template? {
        val placeholder = ByteArray(reportSize) { index ->
            (0xa0 + index).toByte()
        }
        val encoded = generic(uid, placeholder)
        val marker = (0x40 or reportSize).toByte()
        var found = -1
        var matches = 0
        for (index in 0 until encoded.size - reportSize) {
            if (encoded[index] != marker) continue
            var same = true
            for (offset in placeholder.indices) {
                if (encoded[index + 1 + offset] != placeholder[offset]) {
                    same = false
                    break
                }
            }
            if (same) {
                found = index + 1
                matches++
            }
        }
        return if (matches == 1) Template(encoded, found, reportSize) else null
    }

    private fun generic(uid: Int, report: ByteArray): ByteArray =
        BplistCodec.encode(
            linkedMapOf(
                "type" to "hidSendReport",
                "uuid" to uid.toString(16),
                "hidReport" to report,
            ),
        )
}
