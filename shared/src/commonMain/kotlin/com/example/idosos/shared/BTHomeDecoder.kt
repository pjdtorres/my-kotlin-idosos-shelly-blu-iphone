package com.example.idosos.shared

/** Interpreta anúncios BTHome v2 emitidos pelo Shelly BLU Button1. */
object BTHomeDecoder {
    data class Reading(val encrypted: Boolean, val packet: Int, val battery: Int, val button: Int)

    fun parse(data: ByteArray): Reading? {
        if (data.isEmpty()) return null
        val info = data[0].toInt() and 0xFF
        if ((info shr 5) and 0x07 != 2) return null
        if (info and 0x01 != 0) return Reading(true, -1, -1, -1)

        var packet = -1
        var battery = -1
        var button = -1
        var index = 1
        while (index < data.size) {
            val id = data[index].toInt() and 0xFF
            index++
            val size = objectSize(id) ?: break
            if (index + size > data.size) break
            val value = data[index].toInt() and 0xFF
            when (id) {
                0x00 -> packet = value
                0x01 -> battery = value
                0x3A -> if (button <= 0) button = value
            }
            index += size
        }
        return Reading(false, packet, battery, button)
    }

    private fun objectSize(id: Int): Int? = when (id) {
        0x00, 0x01, 0x09, 0x2E, 0x2F, 0x3A, 0x46 -> 1
        in 0x0F..0x11, in 0x15..0x2D -> 1
        0x02, 0x03, 0x06, 0x07, 0x08, 0x0C, 0x0D, 0x0E,
        0x12, 0x13, 0x14, 0x3C, 0x3D, 0x3F, 0x40, 0x41,
        0x43, 0x44, 0x45, 0x47, 0xF0 -> 2
        0x04, 0x05, 0x0A, 0x0B, 0x42, 0x4A, 0xF2 -> 3
        0x3E, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0xF1 -> 4
        else -> null
    }
}
