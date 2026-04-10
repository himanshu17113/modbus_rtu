package com.modbus.rtu

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Modbus RTU Repository for Android USB OTG + RS-485/RS-232 adapter.
 *
 * ── Setup ───────────────────────────────────────────────────────────────────
 * 1. settings.gradle → add JitPack repo:
 *      maven { url 'https://jitpack.io' }
 *
 * 2. build.gradle (app) → add dependency:
 *      implementation 'com.github.mik3y:usb-serial-for-android:3.7.0'
 *
 * 3. AndroidManifest.xml → add USB host feature:
 *      <uses-feature android:name="android.hardware.usb.host" />
 *
 * 4. res/xml/device_filter.xml → declare your adapter's USB VID/PID,
 *    or use the default prober which auto-detects CP210x, FTDI, CH340, PL2303.
 * ────────────────────────────────────────────────────────────────────────────
 */
class ModbusRtuRepository(
    private val context: Context,
    private val slaveId: Int = 1,
    private val baudRate: Int = 9600
) {
    companion object {
        private const val TAG = "ModbusRTU"
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 1000
    }

    // ─── CRC-16 (Modbus) ────────────────────────────────────────────────────

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) (crc shr 1) xor 0xA001
                else crc shr 1
            }
        }
        return crc
    }

    // ─── Frame Builder ───────────────────────────────────────────────────────

    /**
     * Builds a Modbus RTU Read Input Registers (FC 0x04) request frame.
     *
     * Frame: [slaveId][0x04][addrHi][addrLo][countHi][countLo][crcLo][crcHi]
     *
     * @param address  Register start address (matches pymodbus ir= block addresses)
     * @param count    Number of 16-bit registers to read
     */
    private fun buildReadInputRegistersRequest(address: Int, count: Int): ByteArray {
        val frame = ByteArray(6)
        frame[0] = slaveId.toByte()
        frame[1] = 0x04                                    // FC: Read Input Registers
        frame[2] = ((address shr 8) and 0xFF).toByte()
        frame[3] = (address and 0xFF).toByte()
        frame[4] = ((count shr 8) and 0xFF).toByte()
        frame[5] = (count and 0xFF).toByte()

        val crc = crc16(frame)
        return frame + byteArrayOf(
            (crc and 0xFF).toByte(),                       // CRC low byte first
            ((crc shr 8) and 0xFF).toByte()                // CRC high byte
        )
    }

    // ─── Response Parser ─────────────────────────────────────────────────────

    /**
     * Parses a Modbus RTU Read Input Registers response.
     *
     * Response: [slaveId][0x04][byteCount][dataBytes...][crcLo][crcHi]
     *
     * @return IntArray of register values (each 0–65535), or null on any error
     */
    private fun parseResponse(response: ByteArray, expectedCount: Int): IntArray? {
        val minLen = 5 + expectedCount * 2          // 3-byte header + data + 2-byte CRC
        if (response.size < minLen) {
            Log.e(TAG, "Response too short: ${response.size} bytes (need $minLen)")
            return null
        }

        val receivedSlaveId = response[0].toInt() and 0xFF
        val functionCode    = response[1].toInt() and 0xFF

        if (receivedSlaveId != slaveId) {
            Log.e(TAG, "Slave ID mismatch: got $receivedSlaveId, expected $slaveId")
            return null
        }
        if (functionCode == 0x84) {
            Log.e(TAG, "Modbus exception response, error code: ${response[2].toInt() and 0xFF}")
            return null
        }
        if (functionCode != 0x04) {
            Log.e(TAG, "Unexpected function code: 0x${functionCode.toString(16)}")
            return null
        }

        val byteCount = response[2].toInt() and 0xFF
        if (byteCount != expectedCount * 2) {
            Log.e(TAG, "Byte count mismatch: got $byteCount, expected ${expectedCount * 2}")
            return null
        }

        // Verify CRC over everything except the last 2 bytes
        val dataWithoutCrc  = response.copyOf(response.size - 2)
        val receivedCrc     = (response[response.size - 2].toInt() and 0xFF) or
                              ((response[response.size - 1].toInt() and 0xFF) shl 8)
        val calculatedCrc   = crc16(dataWithoutCrc)

        if (receivedCrc != calculatedCrc) {
            Log.e(TAG, "CRC mismatch — received=0x${receivedCrc.toString(16)}, " +
                       "calculated=0x${calculatedCrc.toString(16)}")
            return null
        }

        return IntArray(expectedCount) { i ->
            val offset = 3 + i * 2
            ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)
        }
    }

    // ─── Float Decoder (BADC — matches server's BinaryPayloadBuilder) ────────

    /**
     * Decodes a 32-bit float from two Modbus registers in BADC byte order.
     *
     * Server uses: BinaryPayloadBuilder(byteorder=Big, wordorder=Little)
     * This produces BADC layout where:
     *   registers[0] = words [B][A]   (high word, bytes swapped)
     *   registers[1] = words [D][C]   (low word, bytes swapped)
     *
     * To reconstruct the float: reassemble bytes as [C][D][A][B]
     * which is the standard big-endian IEEE 754 representation.
     */
    private fun decodeFloat(registers: IntArray): Float {
        val bytes = ByteArray(4)
        bytes[0] = (registers[1] shr 8).toByte()    // C
        bytes[1] = (registers[1] and 0xFF).toByte() // D
        bytes[2] = (registers[0] shr 8).toByte()    // A
        bytes[3] = (registers[0] and 0xFF).toByte() // B
        return ByteBuffer.wrap(bytes).float
    }

    // ─── USB Serial Port Helper ──────────────────────────────────────────────

    /**
     * Opens the first available USB serial port.
     * Supports: CP210x, FTDI, CH340, PL2303 (auto-detected by default prober).
     *
     * Requires USB permission to have been granted before calling.
     * See: UsbManager.requestPermission() in your Activity.
     */
    private fun openPort(): UsbSerialPort? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (drivers.isEmpty()) {
            Log.e(TAG, "No USB serial devices found. Is the adapter plugged in?")
            return null
        }

        val driver     = drivers[0]
        val connection = usbManager.openDevice(driver.device) ?: run {
            Log.e(TAG, "Cannot open USB device — USB permission not granted yet.")
            return null
        }

        return driver.ports[0].also { port ->
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Reads a single float value from [address] (reads 2 registers, decodes BADC).
     * Opens and closes the USB port for each call.
     * Returns null on any communication or parsing error.
     */
    suspend fun readFloat(address: Int): Float? = withContext(Dispatchers.IO) {
        var port: UsbSerialPort? = null
        try {
            port = openPort() ?: return@withContext null

            val request    = buildReadInputRegistersRequest(address, 2)
            port.write(request, WRITE_TIMEOUT_MS)

            // 2-register RTU response is always 9 bytes
            val buffer     = ByteArray(9)
            val bytesRead  = port.read(buffer, READ_TIMEOUT_MS)

            if (bytesRead < 9) {
                Log.e(TAG, "Incomplete response at address $address: got $bytesRead/9 bytes")
                return@withContext null
            }

            val registers = parseResponse(buffer, 2) ?: return@withContext null
            decodeFloat(registers)

        } catch (e: Exception) {
            Log.e(TAG, "readFloat($address) failed: ${e.message}")
            null
        } finally {
            port?.close()
        }
    }

    /**
     * Reads all four energy registers in one port session (more efficient than
     * calling readFloat() four times, avoids repeated open/close overhead).
     * Returns null only if the port cannot be opened at all.
     */
    suspend fun readAllRegisters(): ModbusData = withContext(Dispatchers.IO) {
        var port: UsbSerialPort? = null
        try {
            port = openPort()

            fun transact(address: Int): Float? {
                if (port == null) return null
                return try {
                    val req       = buildReadInputRegistersRequest(address, 2)
                    port.write(req, WRITE_TIMEOUT_MS)
                    val buf       = ByteArray(9)
                    val n         = port.read(buf, READ_TIMEOUT_MS)
                    if (n < 9) return null
                    val regs      = parseResponse(buf, 2) ?: return null
                    decodeFloat(regs)
                } catch (e: Exception) {
                    Log.e(TAG, "transact($address): ${e.message}")
                    null
                }
            }

            ModbusData(
                activeImport   = transact(1401),
                activeExport   = transact(1403),
                reactiveImport = transact(1421),
                reactiveExport = transact(1423)
            )
        } catch (e: Exception) {
            Log.e(TAG, "readAllRegisters failed: ${e.message}")
            ModbusData(null, null, null, null)
        } finally {
            port?.close()
        }
    }
}

data class ModbusData(
    val activeImport: Float?,
    val activeExport: Float?,
    val reactiveImport: Float?,
    val reactiveExport: Float?
)
