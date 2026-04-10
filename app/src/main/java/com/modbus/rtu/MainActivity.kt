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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

private const val ACTION_USB_PERMISSION = "com.example.rtu.USB_PERMISSION"

class MainActivity : ComponentActivity() {

    // ─── USB Permission Handling ─────────────────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (!granted) {
                    Toast.makeText(
                        context,
                        "USB permission denied. Please allow access to continue.",
                        Toast.LENGTH_LONG
                    ).show()                }
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
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register USB permission receiver with the required flag
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Request USB OTG permission for any attached device
        requestUsbPermissions()

        setContent {
            MaterialTheme {
                ModbusRtuScreen()
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
fun ModbusRtuScreen(vm: ModbusRtuViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.startPolling()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Modbus RTU Live Data", style = MaterialTheme.typography.headlineMedium)

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
