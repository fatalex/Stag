package com.stag200.gofast

import java.nio.ByteBuffer
import java.nio.ByteOrder

object UgicProtocol {
    const val HEADER_BYTE: Byte = 0xAC.toByte()
    const val ALT_HEADER_BYTE: Byte = 0xAF.toByte()

    // Verified supported commands on user's STAG-200 GoFast ECU
    const val CMD_DEVICE_INFO: Byte = 0x00
    const val CMD_PING: Byte = 0x01
    const val CMD_SUPPORTED_CMDS: Byte = 0x03
    const val CMD_STATUS: Byte = 0x04
    const val CMD_SUPPORTED_LIVE: Byte = 0x10
    const val CMD_ERR_COUNTERS: Byte = 0x40
    const val CMD_GET_ERRORS: Byte = 0x41
    const val CMD_ERASE_ERRORS: Byte = 0x45
    const val CMD_RTC_CLOCK: Byte = 0x49
    const val CMD_HW_INFO: Byte = 0x51
    const val CMD_FW_INFO: Byte = 0x52
    const val CMD_LIVE_DUMP: Byte = 0x64

    fun buildFrame(
        cmd: Byte,
        sub: Byte = 0x00,
        payload: ByteArray = ByteArray(0),
        header: Byte = HEADER_BYTE,
        flags: Byte = 0x00
    ): ByteArray {
        val lenField = payload.size + 3
        val out = ByteArray(6 + payload.size + 1)
        out[0] = header
        out[1] = flags
        out[2] = ((lenField shr 8) and 0xFF).toByte()
        out[3] = (lenField and 0xFF).toByte()
        out[4] = cmd
        out[5] = sub
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, 6, payload.size)
        }
        var sum = 0
        for (i in 0 until 6 + payload.size) {
            sum = (sum + (out[i].toInt() and 0xFF)) and 0xFF
        }
        out[6 + payload.size] = sum.toByte()
        return out
    }

    fun verifyChecksum(frame: ByteArray): Boolean {
        if (frame.size < 7) return false
        var sum = 0
        for (i in 0 until frame.size - 1) {
            sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        return (sum and 0xFF) == (frame.last().toInt() and 0xFF)
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    data class LiveTelemetry(
        val mapBar: Double,
        val gasPressureBar: Double,
        val tempGasC: Int,
        val tempReducerC: Int,
        val voltageV: Double,
        val rpm: Int,
        val petrolInjMs: List<Double>,
        val gasInjMs: List<Double>,
        val isGasActive: Boolean,
        val valveCurrentMa: String,
        val sysInfo: String
    )

    /**
     * Calibrated directly against user's STAG-200 GoFast cold-petrol & warm-gas captures:
     * p[0..1]   = MAP * 100 (bar)
     * p[2..3]   = Gas pressure * 100 (bar)
     * p[4..5]   = Gas temp (signed int16, C)
     * p[6..7]   = Reducer temp (signed int16, C)
     * p[8..9]   = Battery voltage * 10 (V)
     * p[10..11] = Engine RPM
     * p[20..35] = Petrol injectors cyl 1-4 (uint32 / 100.0 ms)
     * p[52..67] = Gas injectors cyl 1-4 (uint32 / 100.0 ms)
     * p[78..]   = ASCII diagnostic lines (VCur, Stack, Led400)
     */
    fun parseLiveDump64(payload: ByteArray): LiveTelemetry? {
        if (payload.size < 68) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val mapBar = (bb.short.toInt() and 0xFFFF) / 100.0
        val gasBar = (bb.short.toInt() and 0xFFFF) / 100.0
        val tempGas = bb.short.toInt()
        val tempRed = bb.short.toInt()
        val volt = (bb.short.toInt() and 0xFFFF) / 10.0
        val rpm = bb.short.toInt() and 0xFFFF

        val petrol = (0 until 4).map { i -> bb.getInt(20 + i * 4) / 100.0 }
        val gas = (0 until 4).map { i -> bb.getInt(52 + i * 4) / 100.0 }
        val isGas = gas.any { it > 0.05 }

        // Extract clean ASCII starting at "VCur:"
        val rawAscii = String(payload.copyOfRange(74.coerceAtMost(payload.size), payload.size), Charsets.US_ASCII)
        val vcurIdx = rawAscii.indexOf("VCur:")
        val cleanAscii = if (vcurIdx >= 0) {
            rawAscii.substring(vcurIdx).takeWhile { it != '\u0000' }.trim()
        } else {
            ""
        }
        val vcurMatch = Regex("VCur:\\s*(\\d+)").find(cleanAscii)
        val vcurStr = vcurMatch?.groupValues?.get(1)?.let { "$it мА" } ?: "—"

        return LiveTelemetry(
            mapBar = mapBar,
            gasPressureBar = gasBar,
            tempGasC = tempGas,
            tempReducerC = tempRed,
            voltageV = volt,
            rpm = rpm,
            petrolInjMs = petrol,
            gasInjMs = gas,
            isGasActive = isGas,
            valveCurrentMa = vcurStr,
            sysInfo = cleanAscii
        )
    }

    fun parseRtc49(payload: ByteArray): String? {
        if (payload.size < 7) return null
        val yr = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        val mo = payload[2].toInt() and 0xFF
        val dy = payload[3].toInt() and 0xFF
        val hh = payload[4].toInt() and 0xFF
        val mm = payload[5].toInt() and 0xFF
        val ss = payload[6].toInt() and 0xFF
        return "%02d.%02d.%04d %02d:%02d:%02d".format(dy, mo, yr, hh, mm, ss)
    }

    /**
     * Verified against 0x00 payload:
     * p[9]=0x07 (day), p[10]=0x03 (month), p[11..12]=0x07E9 (2025)
     */
    fun parseFwDate00(payload: ByteArray): String? {
        if (payload.size < 13) return null
        val day = payload[9].toInt() and 0xFF
        val month = payload[10].toInt() and 0xFF
        val year = ((payload[11].toInt() and 0xFF) shl 8) or (payload[12].toInt() and 0xFF)
        return "%02d.%02d.%04d".format(day, month, year)
    }
}
