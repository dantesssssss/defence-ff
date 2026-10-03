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
import com.da4a.smartcity.beacon.BeaconService
import com.da4a.smartcity.beacon.Permissions
import com.da4a.smartcity.ui.BeaconScreen
import com.da4a.smartcity.ui.theme.SmartCityTheme

class MainActivity : ComponentActivity() {

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
        requestPermissions()

        setContent {
            SmartCityTheme {
                val engine = BeaconService.engine
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
            BeaconService.send(this, BeaconService.ACTION_SEARCH)
        } else if (askToEnable) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
}
