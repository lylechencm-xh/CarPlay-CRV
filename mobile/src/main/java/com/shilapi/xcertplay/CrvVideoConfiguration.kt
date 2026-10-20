package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoCodec

internal fun crvNeedsVideoConfiguration(
    hasDecoder: Boolean, previousCodec: VideoCodec, previous: ByteArray?,
    codec: VideoCodec, config: ByteArray,
): Boolean = !hasDecoder || codec != previousCodec || previous?.contentEquals(config) != true
