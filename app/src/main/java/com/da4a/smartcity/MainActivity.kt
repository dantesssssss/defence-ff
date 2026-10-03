package com.da4a.smartcity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.da4a.smartcity.beacon.BeaconService
import com.da4a.smartcity.beacon.Permissions
import com.da4a.smartcity.ui.BeaconScreen
import com.da4a.smartcity.ui.EmergencyScreen
import com.da4a.smartcity.ui.theme.SmartCityTheme

class MainActivity : ComponentActivity() {

    /** Set when opened from the Emergency widget. */
    private var emergencyRequested by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startIfReady() }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startIfReady(askToEnable = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status bar icons over the black (or green) background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        emergencyRequested = intent?.action == ACTION_EMERGENCY
        requestPermissions()

        setContent {
            SmartCityTheme {
                val engine = BeaconService.engine
                // After "I'm safe" the service is already gone while the app animates closed;
                // keep the screen as it was instead of flashing another state.
                val closing = isFinishing
                if (closing || emergencyRequested || engine?.emergency == true) {
                    EmergencyScreen(
                        running = closing || (engine?.running == true && engine.emergency),
                        proximity = engine?.proximity?.states ?: emptyMap(),
                        onRetry = ::requestPermissions,
                        onSafe = ::stopEmergency,
                    )
                } else {
                    BeaconScreen(
                        ownPressurePa = engine?.pressure?.pressurePa,
                        peers = engine?.scanner?.peers?.values ?: emptyList(),
                        proximity = engine?.proximity?.states ?: emptyMap(),
                        running = engine?.running == true,
                        onRetry = ::requestPermissions,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_EMERGENCY) {
            emergencyRequested = true
            requestPermissions()
        }
    }

    override fun onDestroy() {
        // Searching belongs to the open app; an emergency keeps running without it.
        if (!isChangingConfigurations && BeaconService.engine?.emergency != true) {
            stopService(Intent(this, BeaconService::class.java))
        }
        super.onDestroy()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(Permissions.ALL.toTypedArray())
    }

    /** Starts the service once Bluetooth can run, asking to switch it on first if needed. */
    private fun startIfReady(askToEnable: Boolean = true) {
        if (!Permissions.BLUETOOTH.all { Permissions.granted(this, it) }) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (adapter.isEnabled) {
            BeaconService.send(this, if (emergencyRequested) BeaconService.ACTION_EMERGENCY else BeaconService.ACTION_SEARCH)
        } else if (askToEnable) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    /** The victim is safe: end the SOS entirely and go back to the home screen. */
    private fun stopEmergency() {
        finishAndRemoveTask()
        stopService(Intent(this, BeaconService::class.java))
    }

    companion object {
        /** Intent action the Emergency widget opens the app with. */
        const val ACTION_EMERGENCY = "com.da4a.smartcity.EMERGENCY"
    }
}
