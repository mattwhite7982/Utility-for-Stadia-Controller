package com.mw2311.stadiaconversiontool

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    interface OnStatusChangedListener {
        fun onStatusUpdated(state: Int, info: String)
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var usbManager: UsbManager
    private var statusMenuItem: MenuItem? = null
    private var statusListener: OnStatusChangedListener? = null
    private lateinit var connectionStatusChip: Chip

    private var connectionState = 0 // 0: None, 1: Locked, 2: Unlock Mode
    private var activeDevice: UsbDevice? = null
    private var firmwareInfo = "No Controller Connected"

    override fun onCreate(savedInstanceState: Bundle?) {
        // Removed dynamic color application to enforce custom blue theme
        // DynamicColors.applyToActivityIfAvailable(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout)
        val navView: NavigationView = findViewById(R.id.nav_view)
        connectionStatusChip = findViewById(R.id.chip_connection_status)

        val toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_backup -> replaceFragment(BackupFragment(), "Backup Firmware")
                R.id.nav_flash -> replaceFragment(FlashFragment(), "Flash Firmware")
                R.id.nav_guide -> replaceFragment(GuideFragment(), "Unlock Guide")
                R.id.nav_settings -> replaceFragment(SettingsFragment(), "Settings")
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Fixes Blank Screen: Ensures a fragment is loaded on start
        if (savedInstanceState == null) {
            replaceFragment(BackupFragment(), "Backup Firmware")
        }

        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                updateConnectionStatus()
                handler.postDelayed(this, 2000)
            }
        })
    }

    fun setOnStatusChangedListener(listener: OnStatusChangedListener?) {
        this.statusListener = listener
    }

    fun getConnectionState(): Int = connectionState
    fun getActiveDevice(): UsbDevice? = activeDevice

    fun getUsbConnection(): UsbDeviceConnection? {
        val device = activeDevice ?: return null
        if (!usbManager.hasPermission(device)) {
            val intent = Intent("com.mw2311.USB_PERMISSION").apply { setPackage(packageName) }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
            usbManager.requestPermission(device, permissionIntent)
            return null
        }
        return usbManager.openDevice(device)
    }

    private fun updateConnectionStatus() {
        val deviceList = usbManager.deviceList
        var found = false
        var chipText = "Controller Disconnected"
        var chipColor = Color.parseColor("#B0BEC5") // Grey for Disconnected

        for (device in deviceList.values) {
            if (device.vendorId == 0x18D1 || device.vendorId == 0x1FC9) {
                found = true
                activeDevice = device
                // Detect Bootloader vs Standard Mode via PID
                if (device.productId == 0x0135 || device.productId == 0x000C) {
                    connectionState = 2
                    firmwareInfo = "Bootloader Mode Active"
                    chipText = "Controller Connected" // Flash Mode
                    chipColor = Color.parseColor("#4CAF50") // Green
                } else {
                    connectionState = 1
                    firmwareInfo = "Hardware Locked"
                    chipText = "Controller Locked" // Normal Mode
                    chipColor = Color.parseColor("#FFC107") // Amber/Yellow
                }
                break
            }
        }
        if (!found) {
            connectionState = 0
            activeDevice = null
            firmwareInfo = "No Controller Connected"
            chipText = "Controller Disconnected"
            chipColor = Color.parseColor("#B0BEC5")
        }

        runOnUiThread {
            statusMenuItem?.let { item ->
                val color = when (connectionState) {
                    2 -> Color.GREEN
                    1 -> Color.YELLOW
                    else -> Color.RED
                }
                item.icon?.setTint(color)
                item.title = firmwareInfo
            }
            
            connectionStatusChip.text = chipText
            connectionStatusChip.chipBackgroundColor = ColorStateList.valueOf(chipColor)

            // Dispatch update to active Fragment
            statusListener?.onStatusUpdated(connectionState, firmwareInfo)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar_menu, menu)
        statusMenuItem = menu.findItem(R.id.action_status)
        return true
    }

    private fun replaceFragment(fragment: Fragment, title: String) {
        supportActionBar?.title = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}