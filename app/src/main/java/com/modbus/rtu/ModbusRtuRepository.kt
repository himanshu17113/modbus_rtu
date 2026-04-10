package com.modbus.rtu

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.delay
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
    private val baudRate: Int = 9600,
    private val functionCode: Int = 0x04,
    private val parity: Int = UsbSerialPort.PARITY_NONE,
    private val stopBits: Int = UsbSerialPort.STOPBITS_1,
    private val registerAddressOffset: Int = 0
) {
    companion object {
        private const val TAG = "ModbusRTU"
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 1000
        private const val INTER_REQUEST_DELAY_MS = 30L
        private const val FLUSH_READ_TIMEOUT_MS = 30
    }

    // ─── CRC-16 (Modbus) ────────────────────────────────────────────────────

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { each -> "%02X".format(each.toInt() and 0xFF) }

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
     * Builds a Modbus RTU Read Registers request frame.
     *
     * Frame: [slaveId][fc][addrHi][addrLo][countHi][countLo][crcLo][crcHi]
     * where fc is 0x03 (Holding) or 0x04 (Input).
     */
    private fun buildReadRegistersRequest(address: Int, count: Int): ByteArray {
        val effectiveAddress = address + registerAddressOffset
        val frame = ByteArray(6)
        frame[0] = slaveId.toByte()
        frame[1] = (functionCode and 0xFF).toByte()
        frame[2] = ((effectiveAddress shr 8) and 0xFF).toByte()
        frame[3] = (effectiveAddress and 0xFF).toByte()
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
     * Parses a Modbus RTU read-registers response.
     *
     * Response: [slaveId][fc][byteCount][dataBytes...][crcLo][crcHi]
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
        if (functionCode == ((this.functionCode and 0xFF) or 0x80)) {
            Log.e(TAG, "Modbus exception response, error code: ${response[2].toInt() and 0xFF}")
            return null
        }
        if (functionCode != (this.functionCode and 0xFF)) {
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
            port.setParameters(baudRate, 8, stopBits, parity)
            // Some USB-UART adapters require these lines asserted for stable comms.
            runCatching { port.dtr = true }
            runCatching { port.rts = true }
        }
    }

    private fun flushInput(port: UsbSerialPort) {
        val scratch = ByteArray(256)
        repeat(4) {
            val n = runCatching { port.read(scratch, FLUSH_READ_TIMEOUT_MS) }.getOrDefault(0)
            if (n <= 0) return
        }
    }

    private fun readExact(port: UsbSerialPort, expectedBytes: Int, timeoutMs: Int): ByteArray? {
        val out = ByteArray(expectedBytes)
        var offset = 0
        val startedAt = System.currentTimeMillis()

        while (offset < expectedBytes) {
            val elapsed = System.currentTimeMillis() - startedAt
            val remaining = timeoutMs - elapsed.toInt()
            if (remaining <= 0) break

            val chunk = ByteArray(expectedBytes - offset)
            val n = runCatching { port.read(chunk, remaining) }.getOrDefault(0)
            if (n > 0) {
                chunk.copyInto(out, destinationOffset = offset, startIndex = 0, endIndex = n)
                offset += n
            }
        }

        return if (offset == expectedBytes) out else null
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

            val request    = buildReadRegistersRequest(address, 2)
            flushInput(port)
            Log.d(TAG, "TX fc=0x${functionCode.toString(16)} parity=$parity stopBits=$stopBits reqAddr=$address effAddr=${address + registerAddressOffset} : ${request.toHexString()}")
            port.write(request, WRITE_TIMEOUT_MS)
            delay(INTER_REQUEST_DELAY_MS)

            // 2-register RTU response is always 9 bytes
            val buffer = readExact(port, expectedBytes = 9, timeoutMs = READ_TIMEOUT_MS)
            if (buffer == null) {
                Log.e(TAG, "Incomplete response at address $address: timeout waiting for 9 bytes")
                return@withContext null
            }
            Log.d(TAG, "RX addr=$address : ${buffer.toHexString()}")

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
    suspend fun readAllRegisters(onQueryStatus: ((String) -> Unit)? = null): ModbusData = withContext(Dispatchers.IO) {
        var port: UsbSerialPort? = null
        try {
            onQueryStatus?.invoke("Opening USB port...")
            port = openPort()

            fun transact(label: String, address: Int): Float? {
                if (port == null) return null
                return try {
                    onQueryStatus?.invoke("Reading $label (register $address)...")
                    val req       = buildReadRegistersRequest(address, 2)
                    flushInput(port)
                    Log.d(TAG, "TX $label fc=0x${functionCode.toString(16)} parity=$parity stopBits=$stopBits reqAddr=$address effAddr=${address + registerAddressOffset} : ${req.toHexString()}")
                    port.write(req, WRITE_TIMEOUT_MS)
                    Thread.sleep(INTER_REQUEST_DELAY_MS)

                    val buf = readExact(port, expectedBytes = 9, timeoutMs = READ_TIMEOUT_MS)
                    if (buf == null) {
                        onQueryStatus?.invoke("$label failed: incomplete response")
                        return null
                    }
                    Log.d(TAG, "RX $label addr=$address : ${buf.toHexString()}")

                    val regs = parseResponse(buf, 2)
                    if (regs == null) {
                        onQueryStatus?.invoke("$label failed: invalid frame/CRC")
                        return null
                    }

                    val value = decodeFloat(regs)
                    onQueryStatus?.invoke("$label OK")
                    value
                } catch (e: Exception) {
                    Log.e(TAG, "transact($address): ${e.message}")
                    onQueryStatus?.invoke("$label failed: ${e.message ?: "unknown error"}")
                    null
                }
            }

            ModbusData(
                activeImport   = transact("Active Import", 1401),
                activeExport   = transact("Active Export", 1403),
                reactiveImport = transact("Reactive Import", 1421),
                reactiveExport = transact("Reactive Export", 1423)
            )
        } catch (e: Exception) {
            Log.e(TAG, "readAllRegisters failed: ${e.message}")
            onQueryStatus?.invoke("Query failed: ${e.message ?: "unknown error"}")
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
