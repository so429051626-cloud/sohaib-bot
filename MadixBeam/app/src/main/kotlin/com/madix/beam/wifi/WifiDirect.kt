package com.madix.beam.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why direct Wi-Fi transfers cannot start right now. */
enum class WifiReadiness { READY, UNSUPPORTED, PERMISSION_REQUIRED, WIFI_OFF, LOCATION_OFF }

/** A Wi-Fi P2P operation failed with the framework [code] (see [WifiP2pManager.ERROR]). */
class WifiDirectException(val code: Int, message: String = "Wi-Fi Direct operation failed ($code)") : IOException(message)

/** Runtime permissions needed for Wi-Fi Direct on the running Android version. */
object WifiPermissions {
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    fun granted(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/** Randomly generated credentials of a Wi-Fi Direct group hosted by the receiver. */
class GroupCredentials(val networkName: String, val passphrase: String) {
    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

        fun generate(random: SecureRandom = SecureRandom()): GroupCredentials {
            fun pick(count: Int) = buildString { repeat(count) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            // Android requires the "DIRECT-xy" prefix; the full name stays within 32 bytes.
            return GroupCredentials("DIRECT-${pick(2)}-MadixBeam-${pick(4)}", pick(16))
        }
    }
}

/**
 * Coroutine wrapper around [WifiP2pManager]. Hosting and joining use explicit credentials
 * (Android 10+), so no device discovery or connection-confirmation dialog is involved.
 */
@SuppressLint("MissingPermission")
class WifiDirectController(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var channel: WifiP2pManager.Channel? = null

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manager != null &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun readiness(): WifiReadiness = when {
        !isSupported -> WifiReadiness.UNSUPPORTED
        !WifiPermissions.granted(appContext) -> WifiReadiness.PERMISSION_REQUIRED
        wifiManager?.isWifiEnabled != true -> WifiReadiness.WIFI_OFF
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            locationManager != null && !LocationManagerCompat.isLocationEnabled(locationManager) -> WifiReadiness.LOCATION_OFF
        else -> WifiReadiness.READY
    }

    private fun requireChannel(): WifiP2pManager.Channel {
        channel?.let { return it }
        val p2p = manager ?: throw WifiDirectException(WifiP2pManager.P2P_UNSUPPORTED)
        val created = p2p.initialize(appContext, Looper.getMainLooper(), null)
            ?: throw WifiDirectException(WifiP2pManager.P2P_UNSUPPORTED)
        channel = created
        return created
    }

    /** Creates a group owned by this device, preferring the 5 GHz band for throughput. */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun createGroup(credentials: GroupCredentials) {
        val p2p = manager ?: throw WifiDirectException(WifiP2pManager.P2P_UNSUPPORTED)
        val c = requireChannel()
        attempt { p2p.removeGroup(c, it) }
        var lastCode = WifiP2pManager.ERROR
        for (band in listOf(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)) {
            repeat(3) {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(credentials.networkName)
                    .setPassphrase(credentials.passphrase)
                    .setGroupOperatingBand(band)
                    .enablePersistentMode(false)
                    .build()
                try {
                    action { p2p.createGroup(c, config, it) }
                    return
                } catch (e: WifiDirectException) {
                    lastCode = e.code
                    if (e.code == WifiP2pManager.BUSY) {
                        attempt { p2p.removeGroup(c, it) }
                        delay(700)
                    } else {
                        delay(300)
                    }
                }
            }
        }
        throw WifiDirectException(lastCode)
    }

    /**
     * Joins the group described by [credentials] and returns the group owner's address.
     * @throws WifiDirectException when the group cannot be joined within [timeoutMillis].
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun joinGroup(credentials: GroupCredentials, timeoutMillis: Long = 30_000): InetAddress {
        val p2p = manager ?: throw WifiDirectException(WifiP2pManager.P2P_UNSUPPORTED)
        val c = requireChannel()
        attempt { p2p.removeGroup(c, it) }
        val config = WifiP2pConfig.Builder()
            .setNetworkName(credentials.networkName)
            .setPassphrase(credentials.passphrase)
            .enablePersistentMode(false)
            .build()
        val owner = withTimeoutOrNull(timeoutMillis) {
            while (!attempt { p2p.connect(c, config, it) }) delay(900)
            var address: InetAddress? = null
            while (address == null) {
                val info = connectionInfo(p2p, c)
                if (info != null && info.groupFormed && !info.isGroupOwner) address = info.groupOwnerAddress
                if (address == null) delay(400)
            }
            address
        }
        return owner ?: throw WifiDirectException(WifiP2pManager.ERROR, "Timed out joining the receiver's network")
    }

    /** Leaves or dissolves the current group; failures are ignored. */
    suspend fun removeGroup() {
        val p2p = manager ?: return
        val c = channel ?: return
        attempt { p2p.removeGroup(c, it) }
    }

    fun close() {
        val c = channel ?: return
        channel = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { c.close() }
    }

    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                block(object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) continuation.resumeWithException(WifiDirectException(reason))
                    }
                })
            }
        }
    }

    /** Runs an action and reports success without throwing framework failures. */
    private suspend fun attempt(block: (WifiP2pManager.ActionListener) -> Unit): Boolean = try {
        action(block)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun connectionInfo(p2p: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pInfo? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                p2p.requestConnectionInfo(c) { info -> if (continuation.isActive) continuation.resume(info) }
            }
        }
}

/** Socket helpers for the direct link. */
object LinkNetwork {
    private const val SOCKET_BUFFER = 1 shl 20

    /** Opens the listening socket on all interfaces with an ephemeral port. */
    fun openServer(): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        receiveBufferSize = SOCKET_BUFFER
        bind(InetSocketAddress(0), 4)
    }

    fun tune(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        runCatching { socket.sendBufferSize = SOCKET_BUFFER }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER }
    }

    /** Private IPv4 addresses of the Wi-Fi (station) interfaces, excluding Wi-Fi Direct. */
    fun lanAddresses(): List<Inet4Address> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") }
            .filter { it.name.startsWith("wlan") || it.name.startsWith("swlan") || it.name.startsWith("eth") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .take(2)
    }.getOrDefault(emptyList())

    /** The connected Wi-Fi network, used to route LAN connections even without internet access. */
    @Suppress("DEPRECATION")
    fun wifiNetwork(context: Context): Network? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return runCatching {
            connectivity.allNetworks.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrNull()
    }

    suspend fun connect(address: InetAddress, port: Int, timeoutMillis: Int, network: Network? = null): Socket =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                tune(socket)
                network?.bindSocket(socket)
                socket.connect(InetSocketAddress(address, port), timeoutMillis)
                socket
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
}
