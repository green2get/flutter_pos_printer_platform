package com.sersoluciones.flutter_pos_printer_platform

import java.util.concurrent.CompletableFuture
import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothConnection
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothConstants
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothService
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothService.Companion.TAG
import com.sersoluciones.flutter_pos_printer_platform.usb.USBPrinterService
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.BinaryMessenger

class FlutterPosPrinterPlaformPluginHandlers internal constructor(private val plugin: FlutterPosPrinterPlatformPlugin) :
    PluginRegistry.RequestPermissionsResultListener, PluginRegistry.ActivityResultListener, MethodChannel.MethodCallHandler {
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        try{
            when (requestCode) {
                PERMISSION_ENABLE_BLUETOOTH -> {
                    plugin.setRequestPermissionBT(false)

                    Log.d(TAG, "PERMISSION_ENABLE_BLUETOOTH PERMISSION_GRANTED resultCode $resultCode")
                    if (resultCode == Activity.RESULT_OK)
                        if (plugin.isOnScan())
                            if (plugin.isUsingBle()) plugin.scanBleDevice() else  plugin.scanBluDevice()

                }
            }
        } catch(error: Exception){
            Log.w(TAG, "$error")
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        Log.d(TAG, " --- requestCode $requestCode")
        try{
            when (requestCode) {
                PERMISSION_ALL -> {
                    var grant = true
                    grantResults.forEach { permission ->

                        val permissionGranted = grantResults.isNotEmpty() &&
                                permission == PackageManager.PERMISSION_GRANTED
                        Log.d(TAG, " --- requestCode $requestCode permission $permission permissionGranted $permissionGranted")
                        if (!permissionGranted) grant = false

                    }
                    if (!grant) {
                        Toast.makeText(plugin.getContext(), R.string.not_permissions, Toast.LENGTH_LONG).show()
                    } else {
                        if (plugin.verifyAndCheckBluetoothStatus() && plugin.isOnScan())
                            if (plugin.isUsingBle()) plugin.scanBleDevice() else plugin.scanBluDevice()
                    }
                    return true
                }
            }
        } catch(error: Exception){
            Log.w(TAG, "$error")
        } 
        return false
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        plugin.initializeBluetoothAndUsbPrinters()
        // Log.d(TAG, "Calling at method \"${call.method}\"; BluetoothService: ${plugin.getBluetoothService()}; Adapter: ${plugin.getAdapter()}; Done: ${plugin.getFutureCompletionState()}")
        // isScan = false
        plugin.setScanStatus(false)
        when {
            call.method.equals("getBluetoothList") -> plugin.onMethodCallGetBluetoothList(call, result)
            call.method.equals("getBluetoothLeList") -> plugin.onMethodCallGetBluetoothLeList(call, result)
            call.method.equals("onStartConnection") -> plugin.onMethodCallOnStartConnection(call, result)
            call.method.equals("disconnect") -> plugin.onMethodCallDisconnect(call, result)
            call.method.equals("sendDataByte") -> plugin.onMethodCallSendDataByte(call, result)
            call.method.equals("sendText") -> plugin.onMethodCallSendText(call, result)
            call.method.equals("getList") -> plugin.onMethodCallGetList(call, result)
            call.method.equals("connectPrinter") -> plugin.onMethodCallConnectPrinter(call, result)
            call.method.equals("close") -> plugin.onMethodCallClose(call, result)
            call.method.equals("printText") -> plugin.onMethodCallPrintText(call, result)
            call.method.equals("printRawData") -> plugin.onMethodCallPrintRawData(call, result)
            call.method.equals("printBytes") -> plugin.onMethodCallPrintBytes(call, result)
            call.method.equals("printImage") -> plugin.onMethodCallPrintImage(call, result)
            call.method.equals("printLogo") -> plugin.onMethodCallPrintLogo(call, result)
            else -> {
                result.notImplemented()
            }
        }
    }

     companion object {
        const val PERMISSION_ALL = 1
        const val PERMISSION_ENABLE_BLUETOOTH = 999
    }
}

/** FlutterPosPrinterPlatformPlugin */
class FlutterPosPrinterPlatformPlugin : FlutterPlugin, ActivityAware {

    private val usbHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {

                USBPrinterService.STATE_USB_CONNECTED -> {
                    eventUSBSink?.success(2)
                }
                USBPrinterService.STATE_USB_CONNECTING -> {
                    eventUSBSink?.success(1)
                }
                USBPrinterService.STATE_USB_NONE -> {
                    eventUSBSink?.success(0)
                }
            }
        }
    }

    private val bluetoothHandler = object : Handler(Looper.getMainLooper()) {
        private val bluetoothStatus: Int
            get() = BluetoothService.bluetoothConnection?.state ?: 99

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                BluetoothConstants.MESSAGE_STATE_CHANGE -> {
                    when (bluetoothStatus) {
                        BluetoothConstants.STATE_CONNECTED -> {
                            Log.w(TAG, " -------------------------- connection BT STATE_CONNECTED ")
                            if (msg.obj != null)
                                try {
                                    val result = msg.obj as Result?
                                    result?.success(true)
                                } catch (e: Exception) {
                                }
                            eventSink?.success(2)
                            var blsv = bluetoothService
                            if(blsv != null) blsv.removeReconnectHandlers()
                        }
                        BluetoothConstants.STATE_CONNECTING -> {
                            Log.w(TAG, "##connection BT STATE_CONNECTING ")
                            eventSink?.success(1)
                        }
                        BluetoothConstants.STATE_NONE -> {
                            Log.w(TAG, "##connection BT STATE_NONE ")
                            eventSink?.success(0)
                            var blsv = bluetoothService
                            if(blsv != null) blsv.autoConnectBt()

                        }
                        BluetoothConstants.STATE_FAILED -> {
                            Log.w(TAG, " -------------------------- connection BT STATE_FAILED ")
                            if (msg.obj != null)
                                try {
                                    val result = msg.obj as Result?
                                    result?.success(false)
                                } catch (e: Exception) {
                                }
                            eventSink?.success(0)
                        }
                    }
                }
                BluetoothConstants.MESSAGE_WRITE -> {
                    //                val readBuf = msg.obj as ByteArray
                    //                Log.d("bluetooth", "envia bt: ${String(readBuf)}")
                }
                BluetoothConstants.MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    var readMessage = String(readBuf, 0, msg.arg1)
                    readMessage = readMessage.trim { it <= ' ' }
                    Log.d("bluetooth", "receive bt: $readMessage")
                }
                BluetoothConstants.MESSAGE_DEVICE_NAME -> {
                    val deviceName = msg.data.getString(BluetoothConstants.DEVICE_NAME)
                    Log.d("bluetooth", " ------------- deviceName $deviceName -----------------")
                }

                BluetoothConstants.MESSAGE_TOAST -> {
                    val bundle = msg.data
                    bundle?.getInt(BluetoothConnection.TOAST)?.let {
//                        var context = getContext()
//                        Toast.makeText(context, context!!.getString(it), Toast.LENGTH_SHORT).show()
                    }
                }
                BluetoothConstants.MESSAGE_START_SCANNING -> {

                }

                BluetoothConstants.MESSAGE_STOP_SCANNING -> {

                }
                99 -> {

                }

            }
        }

    }

    private var pluginBindingFuture: CompletableFuture<Void> = CompletableFuture()
    private var activityBindingFuture: CompletableFuture<Void> = CompletableFuture()

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {

        flutterPluginBinding = binding
        if(!pluginBindingFuture.isDone()){
            pluginBindingFuture.complete(null)
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        messageChannel?.setStreamHandler(null)
        messageUSBChannel?.setStreamHandler(null)
        bluetoothService?.setHandler(null)
        adapter?.setHandler(null)

        messageChannel = null
        messageUSBChannel = null
        channel = null
        bluetoothService = null
        adapter = null

        pluginBindingFuture = CompletableFuture()
        activityBindingFuture = CompletableFuture()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityPluginBinding = binding
        
        var h = FlutterPosPrinterPlaformPluginHandlers(this)
        handlers = h

        binding.addRequestPermissionsResultListener(h)
        binding.addActivityResultListener(h)

        var flBinding = flutterPluginBinding
        if (flBinding != null) {
            channel = MethodChannel(flBinding.binaryMessenger, methodChannel)
            channel!!.setMethodCallHandler(handlers)
            messageChannel = EventChannel(flBinding.binaryMessenger, eventChannelBT)
            messageChannel!!.setStreamHandler(object : EventChannel.StreamHandler {

                override fun onListen(p0: Any?, sink: EventChannel.EventSink) {
                    eventSink = sink
                }

                override fun onCancel(p0: Any?) {
                    eventSink = null
                }
            })
            messageUSBChannel = EventChannel(flBinding.binaryMessenger, eventChannelUSB)
            messageUSBChannel!!.setStreamHandler(object : EventChannel.StreamHandler {

                override fun onListen(p0: Any?, sink: EventChannel.EventSink) {
                    eventUSBSink = sink
                }

                override fun onCancel(p0: Any?) {
                    eventUSBSink = null
                }
            })
        }

        if(!activityBindingFuture.isDone()) {
            activityBindingFuture.complete(null)
        }

        initializeBluetoothAndUsbPrinters()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        bluetoothService?.setActivity(null)

        var h = handlers
        if(h != null){
            activityPluginBinding?.removeRequestPermissionsResultListener(h)
            activityPluginBinding?.removeActivityResultListener(h)
        }

        activityPluginBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityPluginBinding = binding
       
        var h = handlers
        if(h != null){
            binding.addRequestPermissionsResultListener(h)
            binding.addActivityResultListener(h)
        }

        bluetoothService?.setActivity(binding.activity)
    }

    override fun onDetachedFromActivity() {
        bluetoothService?.setActivity(null)

        var h = handlers
        if(h != null){
            activityPluginBinding?.removeRequestPermissionsResultListener(h)
            activityPluginBinding?.removeActivityResultListener(h)
        }

        activityPluginBinding = null
    }


   internal fun initializeBluetoothAndUsbPrinters(){
        if(adapter != null && bluetoothService != null) return

        if(!pluginBindingFuture.isDone()) pluginBindingFuture.get()
        if(!activityBindingFuture.isDone()) activityBindingFuture.get()
        
        if(adapter == null){
            adapter = USBPrinterService.getInstance(usbHandler)
            adapter!!.init(getContext()!!)
        }

        if(bluetoothService == null) {
            bluetoothService = BluetoothService.getInstance(bluetoothHandler,  getContext()!!)
            bluetoothService!!.setActivity(getActivityPluginBinding()!!.activity)
        }

        initializeBluetoothAndUsbPrinters()
    }

    /////
    internal fun isUsingBle(): Boolean {
        return isBle
    }

    internal fun isOnScan(): Boolean {
        return isScan
    }

    internal fun scanBleDevice(){
        var ch = channel
        var blsv = bluetoothService
        if(ch != null && blsv != null) blsv.scanBleDevice(ch)
    }

    internal fun scanBluDevice(){
        var ch = channel
        var blsv = bluetoothService
        if(ch != null && blsv != null) blsv.scanBluDevice(ch)
    }

    internal fun setRequestPermissionBT(v: Boolean) {
        requestPermissionBT = v
    }

    internal fun getContext(): Context?{
        return flutterPluginBinding?.applicationContext
    }

    internal fun getBluetoothService(): BluetoothService?{
        return bluetoothService
    }

     internal fun getAdapter(): USBPrinterService?{
        return adapter
    }

    internal fun getActivityPluginBinding(): ActivityPluginBinding?{
        return activityPluginBinding
    }

    internal fun getChannel(): MethodChannel?{
        return channel
    }

    internal fun getFutureCompletionState(): Boolean{
        return pluginBindingFuture.isDone() && activityBindingFuture.isDone()
    }
    ////


    /**
     *
     */

    private fun verifyIsBluetoothIsOn(): Boolean {
        if (checkPermissions()) {
            if (bluetoothService?.mBluetoothAdapter?.isEnabled != true) {
                if (requestPermissionBT) return false
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                var currentActivity = activityPluginBinding?.activity
                currentActivity?.let { startActivityForResult(it, enableBtIntent, PERMISSION_ENABLE_BLUETOOTH, null) }
                requestPermissionBT = true
                return false
            }
        } else return false
        return true
    }

    private fun getUSBDeviceList(result: Result) {
        var adpt = adapter
        if(adpt == null) {
            var list: List<UsbDevice> = listOf()
            result.success(list)
            return
        }
        val usbDevices: List<UsbDevice> = adpt.deviceList
        val list = ArrayList<HashMap<*, *>>()
        for (usbDevice in usbDevices) {
            val deviceMap: HashMap<String?, String?> = HashMap()
            deviceMap["name"] = usbDevice.deviceName
            deviceMap["manufacturer"] = usbDevice.manufacturerName
            deviceMap["product"] = usbDevice.productName
            deviceMap["deviceId"] = usbDevice.deviceId.toString()
            deviceMap["vendorId"] = usbDevice.vendorId.toString()
            deviceMap["productId"] = usbDevice.productId.toString()
            list.add(deviceMap)
        }
        result.success(list)
    }

    private fun connectPrinter(vendorId: Int?, productId: Int?, result: Result) {
        if (vendorId == null || productId == null) return
        adapter?.setHandler(usbHandler)
        if (adapter?.selectDevice(vendorId, productId) != true) {
            result.success(false)
        } else {
            result.success(true)
        }
    }

    private fun closeConn(result: Result) {
        adapter?.setHandler(usbHandler)
        adapter?.closeConnectionIfExists()
        result.success(true)
    }

    private fun printText(text: String?, result: Result) {
        if (text.isNullOrEmpty()) return
        adapter?.setHandler(usbHandler)
        adapter?.printText(text)
        result.success(true)
    }

    private fun printRawData(base64Data: String?, result: Result) {
        if (base64Data.isNullOrEmpty()) return
        adapter?.setHandler(usbHandler)
        adapter?.printRawData(base64Data)
        result.success(true)
    }

    private fun printBytes(bytes: ArrayList<Int>?, result: Result) {
        if (bytes == null) return
        adapter?.setHandler(usbHandler)
        adapter?.printBytes(bytes)
        result.success(true)
    }

    private fun printBitmap(bitmap: Bitmap, orientation: Int, result: Result) {
        bluetoothService?.setHandler(bluetoothHandler)
        bluetoothService?.printBitmap(bitmap, orientation)
        result.success(true)
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            // Manifest.permission.BLUETOOTH,
            // Manifest.permission.BLUETOOTH_ADMIN,
        )

        if (Build.VERSION.SDK_INT > 30) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        var context = getContext()
        if (!hasPermissions(context, *permissions.toTypedArray())) {
            var currentActivity = activityPluginBinding?.activity
            ActivityCompat.requestPermissions(currentActivity!!, permissions.toTypedArray(), PERMISSION_ALL)
            return false
        }
        return true
    }

    private fun hasPermissions(context: Context?, vararg permissions: String?): Boolean {
        if (context != null) {
            for (permission in permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission!!) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
        }
        return true
    }

    internal fun verifyAndCheckBluetoothStatus(): Boolean {
        return verifyIsBluetoothIsOn()
    }

    internal fun onMethodCallGetBluetoothList(@NonNull call: MethodCall, @NonNull result: Result) {
        isScan = true
        isBle = false
        // Log.d(TAG, "Bluetooth Service: $bluetoothService; Application Context: $context; Activity: $currentActivity")
        if (verifyIsBluetoothIsOn()) {
            bluetoothService?.cleanHandlerBtBle()
            var ch = channel
            if(ch != null) bluetoothService?.scanBluDevice(ch)
            result.success(null)
        }
    }

    internal fun onMethodCallGetBluetoothLeList(@NonNull call: MethodCall, @NonNull result: Result) {
        isBle = true
        isScan = true
        if (verifyIsBluetoothIsOn()) {
            var ch = channel
            if(ch != null) bluetoothService?.scanBleDevice(ch)
            result.success(null)
        }
    }

    internal fun onMethodCallDisconnect(@NonNull call: MethodCall, @NonNull result: Result){
        try {
            bluetoothService?.setHandler(bluetoothHandler)
            bluetoothService?.bluetoothDisconnect()
            result.success(true)
        } catch (e: Exception) {
            result.success(false)
        }
    }

    internal fun onMethodCallOnStartConnection(@NonNull call: MethodCall, @NonNull result: Result){
        val address: String? = call.argument("address")
        val isBle: Boolean? = call.argument("isBle")
        val autoConnect: Boolean = if (call.hasArgument("autoConnect")) call.argument("autoConnect")!! else false
        if (verifyIsBluetoothIsOn()) {
            bluetoothService?.setHandler(bluetoothHandler)
            var context = getContext()
            bluetoothService?.onStartConnection(context!!, address!!, result, isBle = isBle!!, autoConnect = autoConnect)
        } else {
            result.success(false)
        }
    }

    internal fun onMethodCallSendDataByte(@NonNull call: MethodCall, @NonNull result: Result){
        if (verifyIsBluetoothIsOn()) {
            bluetoothService?.setHandler(bluetoothHandler)
            val listInt: ArrayList<Int>? = call.argument("bytes")
            val ints = listInt!!.toIntArray()
            val bytes = ints.foldIndexed(ByteArray(ints.size)) { i, a, v -> a.apply { set(i, v.toByte()) } }
            val res = bluetoothService?.sendDataByte(bytes)
            result.success(res)
        } else {
            result.success(false)
        }
    }

    internal fun onMethodCallSendText(@NonNull call: MethodCall, @NonNull result: Result){
        if (verifyIsBluetoothIsOn()) {
            val text: String? = call.argument("text")
            bluetoothService?.sendData(text!!)
            result.success(true)
        } else {
            result.success(false)
        }
    }

    internal fun onMethodCallGetList(@NonNull call: MethodCall, @NonNull result: Result){
        bluetoothService?.cleanHandlerBtBle()
        getUSBDeviceList(result)
    }

    internal fun onMethodCallConnectPrinter(@NonNull call: MethodCall, @NonNull result: Result){
        val vendor: Int? = call.argument("vendor")
        val product: Int? = call.argument("product")
        connectPrinter(vendor, product, result)
    }

    internal fun onMethodCallClose(@NonNull call: MethodCall, @NonNull result: Result){
       closeConn(result)
    }

    internal fun onMethodCallPrintText(@NonNull call: MethodCall, @NonNull result: Result){
        val text: String? = call.argument("text")
        printText(text, result)
    }

    internal fun onMethodCallPrintRawData(@NonNull call: MethodCall, @NonNull result: Result){
        val raw: String? = call.argument("raw")
        printRawData(raw, result)
    }

    internal fun onMethodCallPrintBytes(@NonNull call: MethodCall, @NonNull result: Result){
        val bytes: ArrayList<Int>? = call.argument("bytes")
        printBytes(bytes, result)
    }

    internal fun onMethodCallPrintImage(@NonNull call: MethodCall, @NonNull result: Result){
        val listInt: ArrayList<Int>? = call.argument("bytes")
        var orientation: Int? = call.argument("orientation")
        orientation = orientation ?: 0
        val ints = listInt!!.toIntArray()
        val bytes = ints.foldIndexed(ByteArray(ints.size)) { i, a, v -> a.apply { set(i, v.toByte()) } }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        printBitmap(bitmap, orientation, result)
    }

    internal fun onMethodCallPrintLogo(@NonNull call: MethodCall, @NonNull result: Result){
        // align center
        // setAlign(1)
        var context = getContext()
        val bitmap = BitmapFactory.decodeResource(context?.resources, R.drawable.ic_launcher)
        // orientation portrait
        printBitmap(bitmap, 1, result)
    }

    internal fun setScanStatus(v: Boolean) {
        isScan = v
    }

    companion object {
        const val PERMISSION_ALL = 1
        const val PERMISSION_ENABLE_BLUETOOTH = 999
        const val methodChannel = "com.sersoluciones.flutter_pos_printer_platform"
        const val eventChannelBT = "com.sersoluciones.flutter_pos_printer_platform/bt_state"
        const val eventChannelUSB = "com.sersoluciones.flutter_pos_printer_platform/usb_state"

        /// The MethodChannel that will the communication between Flutter and native Android
        ///
        /// This local reference serves to register the plugin with the Flutter Engine and unregister it
        /// when the Flutter Engine is detached from the Activity
        private var channel: MethodChannel? = null
        private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null

        private var messageChannel: EventChannel? = null
        private var messageUSBChannel: EventChannel? = null
        private var eventSink: EventChannel.EventSink? = null

        // Declare our eventSink later it will be initialized
        private var eventUSBSink: EventChannel.EventSink? = null
        // private var context: Context? = null
        // private var currentActivity: Activity? = null
        private var activityPluginBinding: ActivityPluginBinding? = null
        private var requestPermissionBT: Boolean = false
        private var isBle: Boolean = false
        private var isScan: Boolean = false
        private var adapter: USBPrinterService? = null
        private var bluetoothService: BluetoothService? = null
        private var handlers: FlutterPosPrinterPlaformPluginHandlers? = null
    }
}
