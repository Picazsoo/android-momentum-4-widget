package cz.metlicka.m4control

import android.app.Application
import cz.metlicka.m4control.device.DeviceController

class M4App : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceController.init(this)
    }
}
