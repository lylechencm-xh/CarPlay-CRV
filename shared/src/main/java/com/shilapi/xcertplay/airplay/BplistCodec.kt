package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size >= 40) { "bplist: too short" }
        require(bytes.size <= 4 * 1024 * 1024) { "bplist: input limit exceeded" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        val objectCount = readBigEndianLong(bytes, (trailer + 8).toLong(), 8)
        val top = readBigEndianLong(bytes, (trailer + 16).toLong(), 8)
        val offsetTable = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)
        require(offsetSize in 1..8 && refSize in 1..8) { "bplist: invalid index width" }
        require(objectCount in 1..100_000 && top in 0 until objectCount) { "bplist: invalid object count/index" }
        require(offsetTable in 8..trailer.toLong() &&
            objectCount <= (trailer - offsetTable) / offsetSize) { "bplist: invalid offset table" }
        val numObjects = objectCount.toInt()
        val topObject = top.toInt()

        val offsets = LongArray(numObjects)
        for (index in offsets.indices) {
            offsets[index] = readBigEndianLong(bytes, offsetTable + index.toLong() * offsetSize, offsetSize)
            require(offsets[index] in 8 until offsetTable) { "bplist: invalid object offset" }
        }
        return readObject(bytes, offsets, refSize, topObject, DecodeState(numObjects, offsetTable.toInt()), 0)
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private class DecodeState(count: Int, val end: Int) {
        val visiting = BooleanArray(count)
        val decoded = BooleanArray(count)
        val values = arrayOfNulls<Any>(count)
        val costs = LongArray(count)
        var remaining = 16 * 1024 * 1024L; private set
        fun consume(amount: Long) {
            require(amount >= 0 && amount <= remaining) { "bplist: decoded size limit exceeded" }
            remaining -= amount
        }
    }

    private fun readObject(bytes: ByteArray, offsets: LongArray, refSize: Int, index: Int,
        state: DecodeState, depth: Int): Any? {
        require(index in offsets.indices && depth <= 64) { "bplist: invalid reference/depth" }
        require(!state.visiting[index]) { "bplist: cyclic reference" }
        if (state.decoded[index]) {
            // Bound the logical expanded tree too, so logging a shared DAG cannot
            // expand a tiny input into an enormous string after successful parsing.
            state.consume(state.costs[index])
            return state.values[index]
        }
        val budgetBefore = state.remaining
        state.consume(8)
        state.visiting[index] = true
        try {
            val value = readValue(bytes, offsets, refSize, index, state, depth)
            state.values[index] = value
            state.costs[index] = budgetBefore - state.remaining
            state.decoded[index] = true
            return value
        } finally {
            state.visiting[index] = false
        }
    }

    private fun readValue(bytes: ByteArray, offsets: LongArray, refSize: Int, index: Int,
        state: DecodeState, depth: Int): Any? {
        var position = offsets[index].toInt()
        val markerByte = bytes[position].toInt() and 0xff
        val type = markerByte ushr 4
        val nibble = markerByte and 0x0f
        position++

        fun requireBytes(count: Long) {
            require(count >= 0 && count <= state.end.toLong() - position) { "bplist: truncated object" }
        }

        fun readCount(): Int {
            if (nibble != 0x0f) return nibble
            requireBytes(1)
            val sizeMarker = bytes[position].toInt() and 0xff
            position++
            require(sizeMarker ushr 4 == 1 && (sizeMarker and 0x0f) <= 3) { "bplist: invalid length integer" }
            val intBytes = 1 shl (sizeMarker and 0x0f)
            requireBytes(intBytes.toLong())
            val count = readBigEndianLong(bytes, position.toLong(), intBytes)
            position += intBytes
            require(count in 0..state.end.toLong()) { "bplist: invalid object length" }
            return count.toInt()
        }

        return when (type) {
            0x0 -> when (nibble) {
                0x00 -> null
                0x08 -> false
                0x09 -> true
                else -> throw IllegalArgumentException("bplist: unsupported primitive 0x0$nibble")
            }
            0x1 -> {
                val size = 1 shl nibble
                require(nibble <= 4) { "bplist: unsupported integer width" }
                requireBytes(size.toLong())
                if (size == 16) {
                    // Apple can pad unsigned 64-bit identifiers to a 128-bit plist integer.
                    // Preserve the low 64 bits used by streamConnectionID, without accepting
                    // arbitrary larger values or weakening bounds for lengths/references.
                    require((0 until 8).all { bytes[position + it] == 0.toByte() } ||
                        (0 until 8).all { bytes[position + it] == 0xff.toByte() }) { "bplist: integer exceeds 64 bits" }
                    readBigEndianLong(bytes, position.toLong() + 8, 8)
                } else readBigEndianLong(bytes, position.toLong(), size)
            }
            0x2 -> {
                val size = 1 shl nibble
                requireBytes(size.toLong())
                when (size) {
                    4 -> Float.fromBits(readBigEndianLong(bytes, position.toLong(), size).toInt())
                    8 -> Double.fromBits(readBigEndianLong(bytes, position.toLong(), size))
                    else -> throw IllegalArgumentException("bplist: unsupported real size $size")
                }
            }
            // Date: 8-byte real, seconds since 2001-01-01 UTC.
            0x3 -> {
                require(nibble == 3) { "bplist: invalid date" }
                requireBytes(8)
                java.util.Date(
                Math.round((Double.fromBits(readBigEndianLong(bytes, position.toLong(), 8)) + APPLE_EPOCH_SECONDS) * 1000),
                )
            }
            0x4 -> {
                val count = readCount()
                requireBytes(count.toLong())
                state.consume(count.toLong())
                bytes.copyOfRange(position, position + count)
            }
            // UID (keyed archives): its integer value.
            0x8 -> {
                require(nibble < 8) { "bplist: invalid UID width" }
                requireBytes((nibble + 1).toLong())
                readBigEndianLong(bytes, position.toLong(), nibble + 1)
            }
            0x5 -> {
                val count = readCount()
                requireBytes(count.toLong())
                state.consume(count.toLong() * 2)
                String(bytes, position, count, Charsets.US_ASCII)
            }
            0x6 -> {
                val count = readCount()
                requireBytes(count.toLong() * 2)
                state.consume(count.toLong() * 2)
                String(bytes, position, count * 2, Charsets.UTF_16BE)
            }
            0xa, 0xc -> {
                val count = readCount()
                requireBytes(count.toLong() * refSize)
                state.consume(count.toLong() * 16)
                val array = ArrayList<Any?>(count)
                for (i in 0 until count) {
                    val reference = readBigEndianLong(bytes, position + i.toLong() * refSize, refSize)
                    require(reference in 0 until offsets.size.toLong()) { "bplist: invalid reference" }
                    array.add(readObject(bytes, offsets, refSize, reference.toInt(), state, depth + 1))
                }
                array
            }
            0xd -> {
                val count = readCount()
                requireBytes(count.toLong() * refSize * 2)
                state.consume(count.toLong() * 64)
                val dict = LinkedHashMap<String, Any?>(count)
                for (i in 0 until count) {
                    val keyReference = readBigEndianLong(bytes, position + i.toLong() * refSize, refSize)
                    val valueReference =
                        readBigEndianLong(bytes, position + (count + i).toLong() * refSize, refSize)
                    require(keyReference in 0 until offsets.size.toLong() &&
                        valueReference in 0 until offsets.size.toLong()) { "bplist: invalid reference" }
                    val key = readObject(bytes, offsets, refSize, keyReference.toInt(), state, depth + 1)
                    require(key is String) { "bplist: dictionary key must be a string" }
                    dict[key] =
                        readObject(bytes, offsets, refSize, valueReference.toInt(), state, depth + 1)
                }
                dict
            }
            else -> throw IllegalArgumentException("bplist: unsupported object type 0x${type.toString(16)}")
        }
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        require(size in 1..8 && offset >= 0 && offset <= bytes.size.toLong() - size) { "bplist: truncated integer" }
        var value = 0L
        var base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()

    private const val APPLE_EPOCH_SECONDS = 978_307_200.0
}
