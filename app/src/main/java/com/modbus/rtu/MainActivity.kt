package com.modbus.rtu
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialPort
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

private const val ACTION_USB_PERMISSION = "com.example.rtu.USB_PERMISSION"

class MainActivity : ComponentActivity() {

    private val isOtgConnected = mutableStateOf(false)
    private val connectedDeviceName = mutableStateOf<String?>(null)
    private val usbPermissionStatus = mutableStateOf("Permission unknown")

    // ─── USB Permission Handling ─────────────────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (!granted) {
                        usbPermissionStatus.value = "Permission denied"
                        Toast.makeText(
                            context,
                            "USB permission denied. Please allow access to continue.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        usbPermissionStatus.value = "Permission granted"
                    }

                    val usbManager = context.getSystemService(USB_SERVICE) as UsbManager
                    isOtgConnected.value = usbManager.deviceList.isNotEmpty()
                    connectedDeviceName.value = usbManager.deviceList.values.firstOrNull()?.let { device ->
                        device.productName ?: device.deviceName
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val usbManager = context.getSystemService(USB_SERVICE) as UsbManager
                    isOtgConnected.value = usbManager.deviceList.isNotEmpty()
                    connectedDeviceName.value = usbManager.deviceList.values.firstOrNull()?.let { device ->
                        device.productName ?: device.deviceName
                    }
                    usbPermissionStatus.value = "Permission required"
                    // Device just plugged in → now request permission
                    requestUsbPermissions()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val usbManager = context.getSystemService(USB_SERVICE) as UsbManager
                    isOtgConnected.value = usbManager.deviceList.isNotEmpty()
                    connectedDeviceName.value = usbManager.deviceList.values.firstOrNull()?.let { device ->
                        device.productName ?: device.deviceName
                    }
                    usbPermissionStatus.value = "No device connected"
                }
            }
        }
    }

    private fun requestUsbPermissions() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.deviceList.values.forEach { device: UsbDevice ->
            if (!usbManager.hasPermission(device)) {
                usbPermissionStatus.value = "Permission required"
                usbManager.requestPermission(device, permissionIntent)
            } else {
                usbPermissionStatus.value = "Permission granted"
            }
        }

        if (usbManager.deviceList.isEmpty()) {
            usbPermissionStatus.value = "No device connected"
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        isOtgConnected.value = usbManager.deviceList.isNotEmpty()
        connectedDeviceName.value = usbManager.deviceList.values.firstOrNull()?.let { device ->
            device.productName ?: device.deviceName
        }
        usbPermissionStatus.value = when {
            usbManager.deviceList.isEmpty() -> "No device connected"
            usbManager.deviceList.values.all { usbManager.hasPermission(it) } -> "Permission granted"
            else -> "Permission required"
        }

        // Register USB permission receiver with the required flag
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }

        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Request USB OTG permission for any attached device
        requestUsbPermissions()

        setContent {
            val otgConnected by isOtgConnected
            val deviceName by connectedDeviceName
            val permissionStatus by usbPermissionStatus
            MaterialTheme {
                ModbusRtuScreen(
                    otgConnected = otgConnected,
                    deviceName = deviceName,
                    permissionStatus = permissionStatus
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}

// ─── Composable UI ───────────────────────────────────────────────────────────

@Composable
fun ModbusRtuScreen(
    otgConnected: Boolean,
    deviceName: String?,
    permissionStatus: String,
    vm: ModbusRtuViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var slaveIdInput by rememberSaveable { mutableStateOf(vm.getSlaveId().toString()) }
    var baudRateInput by rememberSaveable { mutableStateOf(vm.getBaudRate().toString()) }
    var functionCodeInput by rememberSaveable { mutableStateOf(vm.getFunctionCode()) }
    var parityInput by rememberSaveable { mutableStateOf(vm.getParity()) }
    var stopBitsInput by rememberSaveable { mutableStateOf(vm.getStopBits()) }
    var registerOffsetInput by rememberSaveable { mutableStateOf(vm.getRegisterAddressOffset()) }

    LaunchedEffect(Unit) {
        vm.startPolling()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    permissionStatus == "Permission granted" -> Color(0xFFC8E6C9)
                    permissionStatus == "Permission denied" -> Color(0xFFFFCDD2)
                    else -> Color(0xFFFFF3E0)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "🔐 USB Permission: $permissionStatus",
                modifier = Modifier.padding(12.dp),
                color = when {
                    permissionStatus == "Permission granted" -> Color(0xFF1B5E20)
                    permissionStatus == "Permission denied" -> Color(0xFFB71C1C)
                    else -> Color(0xFFE65100)
                }
            )
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (otgConnected) Color(0xFFC8E6C9) else Color(0xFFFFE0B2)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (otgConnected) {
                    val name = deviceName?.takeIf { it.isNotBlank() } ?: "USB Device"
                    "🔌 USB: OTG Connected ($name)"
                } else {
                    "🔌 USB: OTG Not Connected"
                },
                modifier = Modifier.padding(12.dp),
                color = if (otgConnected) Color(0xFF1B5E20) else Color(0xFFE65100)
            )
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    state.isQueryInProgress -> Color(0xFFBBDEFB)
                    state.queryStatus == "Query success" -> Color(0xFFC8E6C9)
                    state.queryStatus == "Query failed" -> Color(0xFFFFCDD2)
                    else -> Color(0xFFE0E0E0)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "📡 Query Status: ${state.queryStatus}",
                modifier = Modifier.padding(12.dp),
                color = when {
                    state.isQueryInProgress -> Color(0xFF0D47A1)
                    state.queryStatus == "Query success" -> Color(0xFF1B5E20)
                    state.queryStatus == "Query failed" -> Color(0xFFB71C1C)
                    else -> Color(0xFF424242)
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Show Logs", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = showLogs,
                onCheckedChange = { showLogs = it }
            )
        }

        if (showLogs) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Recent Logs", style = MaterialTheme.typography.titleSmall)
                        TextButton(
                            onClick = {
                                val text = state.logs.takeLast(6).joinToString("\n")
                                clipboardManager.setText(AnnotatedString(text))
                            },
                            enabled = state.logs.isNotEmpty()
                        ) {
                            Text("Copy Logs")
                        }
                    }
                    if (state.logs.isEmpty()) {
                        Text("No logs yet", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.logs.takeLast(6).forEach { logLine ->
                            Text("• $logLine", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Text("Modbus RTU Live Data", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Communication Settings", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = slaveIdInput,
                    onValueChange = { slaveIdInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Slave ID") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = baudRateInput,
                    onValueChange = { baudRateInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Baud Rate") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Function Code", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = functionCodeInput == 0x03,
                        onClick = { functionCodeInput = 0x03 },
                        label = { Text("0x03 Holding") }
                    )
                    FilterChip(
                        selected = functionCodeInput == 0x04,
                        onClick = { functionCodeInput = 0x04 },
                        label = { Text("0x04 Input") }
                    )
                }

                Text("Parity", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = parityInput == UsbSerialPort.PARITY_NONE,
                        onClick = { parityInput = UsbSerialPort.PARITY_NONE },
                        label = { Text("None") }
                    )
                    FilterChip(
                        selected = parityInput == UsbSerialPort.PARITY_EVEN,
                        onClick = { parityInput = UsbSerialPort.PARITY_EVEN },
                        label = { Text("Even") }
                    )
                    FilterChip(
                        selected = parityInput == UsbSerialPort.PARITY_ODD,
                        onClick = { parityInput = UsbSerialPort.PARITY_ODD },
                        label = { Text("Odd") }
                    )
                }

                Text("Stop Bits", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = stopBitsInput == UsbSerialPort.STOPBITS_1,
                        onClick = { stopBitsInput = UsbSerialPort.STOPBITS_1 },
                        label = { Text("1") }
                    )
                    FilterChip(
                        selected = stopBitsInput == UsbSerialPort.STOPBITS_2,
                        onClick = { stopBitsInput = UsbSerialPort.STOPBITS_2 },
                        label = { Text("2") }
                    )
                }

                Text("Register Address Offset", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = registerOffsetInput == 0,
                        onClick = { registerOffsetInput = 0 },
                        label = { Text("0 (as-is)") }
                    )
                    FilterChip(
                        selected = registerOffsetInput == -1,
                        onClick = { registerOffsetInput = -1 },
                        label = { Text("-1 (1-based map)") }
                    )
                }

                Button(
                    onClick = {
                        val newSlave = slaveIdInput.toIntOrNull()
                        val newBaud = baudRateInput.toIntOrNull()
                        if (newSlave != null && newBaud != null) {
                            vm.updateCommunicationSettings(
                                newSlave,
                                newBaud,
                                functionCodeInput,
                                parityInput,
                                stopBitsInput,
                                registerOffsetInput
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Settings")
                }
            }
        }

        // Show error banner if device is not responding
        state.error?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠ $msg",
                    modifier = Modifier.padding(12.dp),
                    color = Color(0xFFB71C1C)
                )
            }
        }

        DataCard("Active Import",   state.activeImport,   "kWh")
        DataCard("Active Export",   state.activeExport,   "kWh")
        DataCard("Reactive Import", state.reactiveImport, "kVARh")
        DataCard("Reactive Export", state.reactiveExport, "kVARh")
    }
}

@Composable
fun DataCard(title: String, value: Float?, unit: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text  = if (value != null) "%.2f $unit".format(value) else "Waiting...",
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}
