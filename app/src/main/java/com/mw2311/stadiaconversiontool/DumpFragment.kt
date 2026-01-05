package com.mw2311.stadiaconversiontool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment

class DumpFragment : Fragment() {

    private var progressBar: ProgressBar? = null
    private var txtDumpStatus: TextView? = null
    private var btnDump: Button? = null
    private val ACTION_USB_PERMISSION = "com.mw2311.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Logic to handle permission for dump
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // FIXED: Ensure fragment_dump exists in your layout folder
        return inflater.inflate(R.layout.fragment_dump, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // FIXED: Registration flag
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context?.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }

        // FIXED: Matching IDs from your layout
        progressBar = view.findViewById(R.id.progress_bar_dump)
        txtDumpStatus = view.findViewById(R.id.txt_dump_status)
        btnDump = view.findViewById(R.id.btn_start_dump)
    }
}