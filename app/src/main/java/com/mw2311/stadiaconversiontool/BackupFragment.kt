package com.mw2311.stadiaconversiontool

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import java.io.InputStream
import java.io.FileOutputStream

class BackupFragment : Fragment(), MainActivity.OnStatusChangedListener {

    private lateinit var progressBar: ProgressBar
    private lateinit var txtBackupStatus: TextView
    private lateinit var btnEssentials: Button
    private lateinit var btnFull: Button
    private var targetSize = 0L

    companion object {
        private const val ESSENTIALS = 262144L // 256KB
        private const val FULL = 16777216L     // 16MB
    }

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.data?.let { uri ->
            runBackup(uri, targetSize)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_backup, container, false)
        progressBar = view.findViewById(R.id.progress_bar_backup)
        txtBackupStatus = view.findViewById(R.id.txt_backup_status)
        btnEssentials = view.findViewById(R.id.btn_start_backup)
        btnFull = view.findViewById(R.id.btn_start_full_backup)

        btnEssentials.setOnClickListener { performPreDumpChecks(ESSENTIALS, "stadia_essentials.bin") }
        btnFull.setOnClickListener { performPreDumpChecks(FULL, "stadia_full_dump.bin") }

        return view
    }

    private fun performPreDumpChecks(size: Long, defaultName: String) {
        val mainActivity = activity as? MainActivity ?: return
        if (mainActivity.getConnectionState() != 2) {
            Toast.makeText(context, "Hardware Locked: Enter Bootloader Mode", Toast.LENGTH_SHORT).show()
            return
        }
        targetSize = size
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, defaultName)
        }
        saveLauncher.launch(intent)
    }

    private fun runBackup(uri: Uri, size: Long) {
        val mainActivity = activity as? MainActivity ?: return
        val connection = mainActivity.getUsbConnection() ?: return
        val device = mainActivity.getActiveDevice() ?: return
        val handler = Handler(Looper.getMainLooper())

        progressBar.visibility = View.VISIBLE
        txtBackupStatus.text = "Handshaking with hardware..."

        Thread {
            var pfd: ParcelFileDescriptor? = null
            try {
                val usbIntf = device.getInterface(0)
                if (!connection.claimInterface(usbIntf, true)) throw Exception("USB Interface Busy")

                // --- IMPROVED AUTOMATIC RETRY HANDSHAKE ---
                var handshaked = false
                for (i in 1..5) { // Try 5 times to wake up the controller
                    val handshake = connection.controlTransfer(0xC0, 0x01, 0, 0, ByteArray(0), 0, 1000)
                    if (handshake >= 0) {
                        handshaked = true
                        break
                    }
                    Thread.sleep(200) // Wait for hardware to spin up
                }

                if (!handshaked) throw Exception("Hardware failed to respond to wake-up signal")

                // Open file handle only after hardware is confirmed awake
                pfd = context?.contentResolver?.openFileDescriptor(uri, "rwt")
                val fileDescriptor = pfd?.fileDescriptor ?: throw Exception("PFD Null")
                val fos = FileOutputStream(fileDescriptor)

                val buffer = ByteArray(4096)
                var readTotal = 0L

                fos.use { stream ->
                    while (readTotal < size) {
                        val result = connection.controlTransfer(
                            0xC0, 0x01,
                            (readTotal shr 16).toInt() and 0xFFFF,
                            readTotal.toInt() and 0xFFFF,
                            buffer, buffer.size, 10000 // 10s Timeout
                        )

                        if (result > 0) {
                            stream.write(buffer, 0, result)
                            readTotal += result
                            // Update UI every 64KB to maintain speed
                            if (readTotal % 65536 == 0L || readTotal >= size) {
                                handler.post {
                                    progressBar.progress = (readTotal.toFloat() / size * 100).toInt()
                                    txtBackupStatus.text = "Dumping: ${progressBar.progress}%"
                                }
                            }
                        } else {
                            // Small retry delay for hardware lag
                            Thread.sleep(50)
                            continue
                        }
                    }
                    stream.flush()
                    fileDescriptor.sync()
                }

                val isValid = verifyDumpIntegrity(uri, size)
                handler.post {
                    if (isValid) {
                        txtBackupStatus.text = "SUCCESS: Backup Verified"
                        txtBackupStatus.setTextColor(Color.parseColor("#2E7D32"))
                    } else {
                        txtBackupStatus.text = "INVALID: Backup Corrupt"
                        txtBackupStatus.setTextColor(Color.RED)
                    }
                }

            } catch (e: Exception) {
                handler.post {
                    txtBackupStatus.text = "ERROR: ${e.message}"
                    txtBackupStatus.setTextColor(Color.RED)
                }
            } finally {
                pfd?.close()
                try { connection.releaseInterface(device.getInterface(0)) } catch (e: Exception) {}
            }
        }.start()
    }

    private fun verifyDumpIntegrity(uri: Uri, expectedSize: Long): Boolean {
        return try {
            context?.contentResolver?.openInputStream(uri)?.use { stream ->
                val header = ByteArray(16)
                val read = stream.read(header)
                if (read < 16) return false

                var hasData = false
                for (b in header) if (b != 0.toByte()) { hasData = true; break }
                hasData
            } ?: false
        } catch (e: Exception) { false }
    }

    override fun onStatusUpdated(state: Int, info: String) {
        txtBackupStatus.text = info
        val enabled = (state == 2)
        btnEssentials.isEnabled = enabled
        btnFull.isEnabled = enabled
        btnEssentials.alpha = if (enabled) 1.0f else 0.3f
        btnFull.alpha = if (enabled) 1.0f else 0.3f
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.setOnStatusChangedListener(this)
        val mainActivity = (activity as? MainActivity)
        onStatusUpdated(mainActivity?.getConnectionState() ?: 0, "Initializing...")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.setOnStatusChangedListener(null)
    }
}