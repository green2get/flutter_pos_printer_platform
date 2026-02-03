package com.sersoluciones.flutter_pos_printer_platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.sersoluciones.flutter_pos_printer_platform.R
import java.nio.charset.Charset
import java.util.*
import kotlin.math.min

class USBPrinterService private constructor(private var mHandler: Handler?) {

    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null

    private var mPermissionIndent: PendingIntent? = null

    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null

    private var mUsbDeviceReceiver: BroadcastReceiver? = null

    var state: Int = STATE_USB_NONE

    fun setHandler(handler: Handler?) {
        mHandler = handler
    }

    private inner class UsbPermissionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val usbDevice: UsbDevice? =
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                        Log.i(
                            LOG_TAG,
                            "USB_PERMISSION granted=$granted deviceId=${usbDevice?.deviceId} " +
                                    "vid=${usbDevice?.vendorId} pid=${usbDevice?.productId}"
                        )

                        if (granted && usbDevice != null) {
                            mUsbDevice = usbDevice
                            state = STATE_USB_CONNECTED
                            mHandler?.obtainMessage(STATE_USB_CONNECTED)?.sendToTarget()
                        } else {
                            Toast.makeText(
                                context,
                                mContext?.getString(R.string.user_refuse_perm) + ": ${usbDevice?.deviceName}",
                                Toast.LENGTH_LONG
                            ).show()
                            state = STATE_USB_NONE
                            mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (mUsbDevice != null) {
                        Toast.makeText(context, mContext?.getString(R.string.device_off), Toast.LENGTH_LONG).show()
                        closeConnectionIfExists()
                        state = STATE_USB_NONE
                        mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(LOG_TAG, "USB_DEVICE_ATTACHED")
                }
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager

        if (mUsbDeviceReceiver == null) {
            mUsbDeviceReceiver = UsbPermissionReceiver()
        }

        // ✅ Android 14+ ต้อง explicit Intent + FLAG_IMMUTABLE
        val intent = Intent(mContext, mUsbDeviceReceiver!!.javaClass).apply {
            action = ACTION_USB_PERMISSION
            setPackage(mContext!!.packageName) // กัน implicit เพิ่มความชัวร์
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        mPermissionIndent = PendingIntent.getBroadcast(mContext, 0, intent, flags)

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        Log.v(LOG_TAG, "ESC/POS Printer initialized")
    }

    fun dispose() {
        try {
            if (mUsbDeviceReceiver != null && mContext != null) {
                mContext!!.unregisterReceiver(mUsbDeviceReceiver)
            }
        } catch (_: Exception) {
        }
        mUsbDeviceReceiver = null
    }

    fun closeConnectionIfExists() {
        if (mUsbDeviceConnection != null) {
            try {
                mUsbDeviceConnection!!.releaseInterface(mUsbInterface)
            } catch (_: Exception) {
            }
            try {
                mUsbDeviceConnection!!.close()
            } catch (_: Exception) {
            }
            mUsbInterface = null
            mEndPoint = null
            mUsbDevice = null
            mUsbDeviceConnection = null
        }
    }

    val deviceList: List<UsbDevice>
        get() {
            if (mUSBManager == null) {
                Toast.makeText(mContext, mContext?.getString(R.string.not_usb_manager), Toast.LENGTH_LONG).show()
                return emptyList()
            }
            return ArrayList(mUSBManager!!.deviceList.values)
        }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        if ((mUsbDevice == null) || (mUsbDevice!!.vendorId != vendorId) || (mUsbDevice!!.productId != productId)) {
            synchronized(printLock) {
                closeConnectionIfExists()
                val usbDevices: List<UsbDevice> = deviceList
                for (usbDevice: UsbDevice in usbDevices) {
                    if ((usbDevice.vendorId == vendorId) && (usbDevice.productId == productId)) {
                        Log.v(
                            LOG_TAG,
                            "Request for device: vendor_id=${usbDevice.vendorId}, product_id=${usbDevice.productId}"
                        )

                        if (mUSBManager!!.hasPermission(usbDevice)) {
                            Log.i(LOG_TAG, "Already has permission for device ${usbDevice.deviceName}")
                            mUsbDevice = usbDevice
                            state = STATE_USB_CONNECTED
                            mHandler?.obtainMessage(STATE_USB_CONNECTED)?.sendToTarget()
                            return true
                        }

                        mUSBManager!!.requestPermission(usbDevice, mPermissionIndent)
                        state = STATE_USB_CONNECTING
                        mHandler?.obtainMessage(STATE_USB_CONNECTING)?.sendToTarget()
                        return true
                    }
                }
                return false
            }
        } else {
            mHandler?.obtainMessage(state)?.sendToTarget()
        }
        return true
    }

    private fun openConnection(): Boolean {
        if (mUsbDevice == null) {
            Log.e(LOG_TAG, "USB Device is not initialized")
            return false
        }
        if (mUSBManager == null) {
            Log.e(LOG_TAG, "USB Manager is not initialized")
            return false
        }

        if (mUsbDeviceConnection != null && mEndPoint != null && mUsbInterface != null) {
            Log.i(LOG_TAG, "USB Connection already connected")
            return true
        }

        if (!mUSBManager!!.hasPermission(mUsbDevice)) {
            Log.e(LOG_TAG, "No permission to access USB device. requestPermission again.")
            state = STATE_USB_CONNECTING
            mHandler?.obtainMessage(STATE_USB_CONNECTING)?.sendToTarget()
            mUSBManager!!.requestPermission(mUsbDevice, mPermissionIndent)
            return false
        }

        val usbInterface = mUsbDevice!!.getInterface(0)

        // หา bulk OUT endpoint
        var outEp: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT
            ) {
                outEp = ep
                break
            }
        }

        if (outEp == null) {
            Log.e(LOG_TAG, "No BULK OUT endpoint found")
            return false
        }

        val usbDeviceConnection = mUSBManager!!.openDevice(mUsbDevice)
        if (usbDeviceConnection == null) {
            Log.e(LOG_TAG, "Failed to open USB Connection (openDevice returned null)")
            return false
        }

        val claimed = usbDeviceConnection.claimInterface(usbInterface, true)
        if (!claimed) {
            usbDeviceConnection.close()
            Log.e(LOG_TAG, "Failed to claim interface")
            return false
        }

        Toast.makeText(mContext, mContext?.getString(R.string.connected_device), Toast.LENGTH_SHORT).show()

        mEndPoint = outEp
        mUsbInterface = usbInterface
        mUsbDeviceConnection = usbDeviceConnection
        return true
    }

    fun printText(text: String): Boolean {
        Log.v(LOG_TAG, "Printing text")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                synchronized(printLock) {
                    val bytes: ByteArray = text.toByteArray(Charset.forName("UTF-8"))
                    val b: Int = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Return code: $b")
                }
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "Failed to connect to device")
            false
        }
    }

    fun printRawData(data: String): Boolean {
        Log.v(LOG_TAG, "Printing raw data")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                synchronized(printLock) {
                    val bytes: ByteArray = Base64.decode(data, Base64.DEFAULT)
                    val b: Int = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Return code: $b")
                }
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "Failed to connect to device")
            false
        }
    }

    fun printBytes(bytes: ArrayList<Int>): Boolean {
        Log.v(LOG_TAG, "Printing bytes")
        val isConnected = openConnection()
        if (!isConnected) {
            Log.v(LOG_TAG, "Failed to connected to device")
            return false
        }

        if (mEndPoint == null || mUsbDeviceConnection == null) {
            Log.e(LOG_TAG, "Endpoint/Connection is null after openConnection()")
            return false
        }

        val chunkSize = mEndPoint!!.maxPacketSize
        Log.v(LOG_TAG, "Max Packet Size: $chunkSize")
        Log.v(LOG_TAG, "Connected to device")

        Thread {
            synchronized(printLock) {
                val byteData = ByteArray(bytes.size)
                for (i in bytes.indices) byteData[i] = bytes[i].toByte()

                var b = 0
                if (byteData.size > chunkSize) {
                    var offset = 0
                    while (offset < byteData.size) {
                        val end = min(offset + chunkSize, byteData.size)
                        val buffer = Arrays.copyOfRange(byteData, offset, end)
                        b = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, buffer, buffer.size, 100000)
                        offset = end
                    }
                } else {
                    b = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, byteData, byteData.size, 100000)
                }
                Log.i(LOG_TAG, "Return code: $b")
            }
        }.start()

        return true
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var mInstance: USBPrinterService? = null

        private const val LOG_TAG = "ESC POS Printer"

        private const val ACTION_USB_PERMISSION =
            "com.sersoluciones.flutter_pos_printer_platform.USB_PERMISSION"

        // Constants that indicate the current connection state
        const val STATE_USB_NONE = 0
        const val STATE_USB_CONNECTING = 2
        const val STATE_USB_CONNECTED = 3

        private val printLock = Any()

        fun getInstance(handler: Handler): USBPrinterService {
            if (mInstance == null) {
                mInstance = USBPrinterService(handler)
            }
            return mInstance!!
        }
    }
}
