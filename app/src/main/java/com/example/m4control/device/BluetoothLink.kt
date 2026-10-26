package com.example.m4control.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Android Bluetooth access: permission, bonded devices, audio-link state and the control socket. */
@SuppressLint("MissingPermission") // every entry point checks hasPermission() first
class BluetoothLink(private val context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var a2dp: BluetoothA2dp? = null
    @Volatile private var headset: BluetoothHeadset? = null

    /** Audio-link state from ACL broadcasts, keyed by address; consulted before the profile proxies. */
    private val acl = HashMap<String, Boolean>()

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Profile proxies give us a public way to ask "is the audio link up" for one device. */
    fun bindProfiles() {
        if (!hasPermission() || adapter == null) return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                when (profile) {
                    BluetoothProfile.A2DP -> a2dp = proxy as BluetoothA2dp
                    BluetoothProfile.HEADSET -> headset = proxy as BluetoothHeadset
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                when (profile) {
                    BluetoothProfile.A2DP -> a2dp = null
                    BluetoothProfile.HEADSET -> headset = null
                }
            }
        }
        if (a2dp == null) adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
        if (headset == null) adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
    }

    fun onAcl(address: String, connected: Boolean) = synchronized(acl) { acl[address] = connected }

    data class Bonded(val name: String, val address: String, val isHeadset: Boolean)

    fun bondedDevices(): List<Bonded> {
        if (!hasPermission()) return emptyList()
        return adapter?.bondedDevices.orEmpty().map {
            Bonded(it.name ?: it.address, it.address, it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO)
        }.sortedWith(compareByDescending<Bonded> { it.name.contains("MOMENTUM 4", ignoreCase = true) }.thenByDescending { it.isHeadset }.thenBy { it.name })
    }

    fun device(address: String): BluetoothDevice? =
        if (!hasPermission()) null else adapter?.bondedDevices?.firstOrNull { it.address == address }

    /** True/false when known; null when we can't tell yet (proxies not bound and no broadcast seen). */
    fun isAudioConnected(address: String): Boolean? {
        if (!hasPermission()) return null
        val dev = device(address) ?: return false
        val profiles = listOfNotNull(a2dp, headset)
        if (profiles.isNotEmpty()) {
            return profiles.any { it.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTED }
        }
        return synchronized(acl) { acl[address] }
    }

    /**
     * Opens the control socket via SDP for the vendor UUID only. connect() blocks and ignores
     * coroutine cancellation, so a watchdog closes the candidate socket at the deadline.
     */
    suspend fun connect(address: String, scope: CoroutineScope, timeoutMs: Long): BluetoothSocket =
        withContext(Dispatchers.IO) {
            val dev = device(address) ?: throw IOException("device not bonded")
            val socket = dev.createInsecureRfcommSocketToServiceRecord(CONTROL_UUID)
            var timedOut = false
            val watchdog = scope.launch {
                delay(timeoutMs)
                timedOut = true
                runCatching { socket.close() }
            }
            try {
                // No cancelDiscovery(): it needs BLUETOOTH_SCAN, and this app never scans.
                socket.connect()
                socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw IOException(if (timedOut) "connect timed out" else (e.message ?: "connect failed"), e)
            } finally {
                watchdog.cancel()
            }
        }

    companion object {
        val CONTROL_UUID: UUID = UUID.fromString("a2129ff3-081b-4c45-8afe-469d9c4842ec")
    }
}
