package com.example.m4control

import android.app.Application
import com.example.m4control.device.DeviceController

class M4App : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceController.init(this)
    }
}
