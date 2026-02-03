package com.sersoluciones.flutter_pos_printer_platform.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Inside USB Broadcast action $action")

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                Log.d(TAG, "USB_DEVICE_ATTACHED device=$device")

                requestPermissionSafely(context, device)
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                Log.d(TAG, "USB_DEVICE_DETACHED device=$device")
            }

            ACTION_USB_PERMISSION -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "ACTION_USB_PERMISSION granted=$granted device=$device")
            }
        }
    }

    private fun requestPermissionSafely(context: Context, device: UsbDevice?) {
        if (device == null) return

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Already has permission vid=${device.vendorId} pid=${device.productId}")
            return
        }

        val permissionIntent = Intent(context, UsbReceiver::class.java).apply {
            action = ACTION_USB_PERMISSION
            setPackage(context.packageName)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, 0, permissionIntent, flags)

        try {
            usbManager.requestPermission(device, pendingIntent)
            Log.d(TAG, "requestPermission fired vid=${device.vendorId} pid=${device.productId}")
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed: $e")
        }
    }

    companion object {
        private const val TAG = "UsbReceiver"
        private const val ACTION_USB_PERMISSION =
            "com.sersoluciones.flutter_pos_printer_platform.USB_PERMISSION"
    }
}
