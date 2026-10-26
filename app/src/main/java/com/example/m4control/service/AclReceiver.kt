package com.example.m4control.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.m4control.device.DeviceController

/** Updates link state and marks cached values stale. No socket work here. */
class AclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        DeviceController.init(context)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> DeviceController.onAcl(device.address, true)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> DeviceController.onAcl(device.address, false)
        }
    }
}
