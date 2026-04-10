package com.modbus.rtu

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ModbusUiState(
    val activeImport: Float?   = null,
    val activeExport: Float?   = null,
    val reactiveImport: Float? = null,
    val reactiveExport: Float? = null,
    val isQueryInProgress: Boolean = false,
    val queryStatus: String = "Idle",
    val error: String?         = null
)

/**
 * Uses AndroidViewModel (not plain ViewModel) so it can pass
 * application context to ModbusRtuRepository without leaking an Activity.
 */
class ModbusRtuViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private var slaveId: Int = 1
    private var baudRate: Int = 9600
    private var functionCode: Int = 0x04
    private var parity: Int = UsbSerialPort.PARITY_NONE
    private var stopBits: Int = UsbSerialPort.STOPBITS_1
    private var registerAddressOffset: Int = 0

    private var repo = ModbusRtuRepository(
        context = appContext,
        slaveId = slaveId,
        baudRate = baudRate,
        functionCode = functionCode,
        parity = parity,
        stopBits = stopBits,
        registerAddressOffset = registerAddressOffset
    )
    private var pollingJob: Job? = null

    private val _state = MutableStateFlow(ModbusUiState())
    val state: StateFlow<ModbusUiState> = _state

    fun getSlaveId(): Int = slaveId

    fun getBaudRate(): Int = baudRate

    fun getFunctionCode(): Int = functionCode

    fun getParity(): Int = parity

    fun getStopBits(): Int = stopBits

    fun getRegisterAddressOffset(): Int = registerAddressOffset

    fun updateCommunicationSettings(
        newSlaveId: Int,
        newBaudRate: Int,
        newFunctionCode: Int,
        newParity: Int,
        newStopBits: Int,
        newRegisterAddressOffset: Int
    ) {
        if (newSlaveId !in 1..247) {
            _state.value = _state.value.copy(queryStatus = "Invalid Slave ID (1..247)")
            return
        }
        if (newBaudRate <= 0) {
            _state.value = _state.value.copy(queryStatus = "Invalid baud rate")
            return
        }
        if (newFunctionCode != 0x03 && newFunctionCode != 0x04) {
            _state.value = _state.value.copy(queryStatus = "Invalid function code")
            return
        }
        if (newParity !in listOf(UsbSerialPort.PARITY_NONE, UsbSerialPort.PARITY_EVEN, UsbSerialPort.PARITY_ODD)) {
            _state.value = _state.value.copy(queryStatus = "Invalid parity")
            return
        }
        if (newStopBits !in listOf(UsbSerialPort.STOPBITS_1, UsbSerialPort.STOPBITS_2)) {
            _state.value = _state.value.copy(queryStatus = "Invalid stop bits")
            return
        }
        if (newRegisterAddressOffset !in listOf(0, -1)) {
            _state.value = _state.value.copy(queryStatus = "Invalid register offset")
            return
        }

        slaveId = newSlaveId
        baudRate = newBaudRate
        functionCode = newFunctionCode
        parity = newParity
        stopBits = newStopBits
        registerAddressOffset = newRegisterAddressOffset
        repo = ModbusRtuRepository(
            context = appContext,
            slaveId = slaveId,
            baudRate = baudRate,
            functionCode = functionCode,
            parity = parity,
            stopBits = stopBits,
            registerAddressOffset = registerAddressOffset
        )

        _state.value = _state.value.copy(
            queryStatus = "Settings applied: slave=$slaveId, baud=$baudRate, fc=0x${functionCode.toString(16)}, parity=$parity, stop=$stopBits, offset=$registerAddressOffset"
        )
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return

        pollingJob = viewModelScope.launch {
            while (isActive) {
                _state.value = _state.value.copy(
                    isQueryInProgress = true,
                    queryStatus = "Sending query..."
                )

                val data = repo.readAllRegisters { status ->
                    _state.value = _state.value.copy(
                        isQueryInProgress = true,
                        queryStatus = status
                    )
                }

                val hasAnyData = listOf(
                    data.activeImport,
                    data.activeExport,
                    data.reactiveImport,
                    data.reactiveExport
                ).any { it != null }

                _state.value = ModbusUiState(
                    activeImport   = data.activeImport,
                    activeExport   = data.activeExport,
                    reactiveImport = data.reactiveImport,
                    reactiveExport = data.reactiveExport,
                    isQueryInProgress = false,
                    queryStatus = if (hasAnyData) "Query success" else "Query failed",
                    error          = if (!hasAnyData) "No response from device" else null
                )
                delay(1000L)
            }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
