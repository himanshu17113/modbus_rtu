package com.modbus.rtu

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ModbusUiState(
    val activeImport: Float?   = null,
    val activeExport: Float?   = null,
    val reactiveImport: Float? = null,
    val reactiveExport: Float? = null,
    val error: String?         = null
)

/**
 * Uses AndroidViewModel (not plain ViewModel) so it can pass
 * application context to ModbusRtuRepository without leaking an Activity.
 */
class ModbusRtuViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ModbusRtuRepository(
        context  = application.applicationContext,
        slaveId  = 1,
        baudRate = 9600
    )

    private val _state = MutableStateFlow(ModbusUiState())
    val state: StateFlow<ModbusUiState> = _state

    fun startPolling() {
        viewModelScope.launch {
            while (true) {
                val data = repo.readAllRegisters()
                _state.value = ModbusUiState(
                    activeImport   = data.activeImport,
                    activeExport   = data.activeExport,
                    reactiveImport = data.reactiveImport,
                    reactiveExport = data.reactiveExport,
                    error          = if (data.activeImport == null) "No response from device" else null
                )
                delay(1000L)
            }
        }
    }
}
