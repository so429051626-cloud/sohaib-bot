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

    /** Requirements for hosting a local hotspot or group on this device. */
    fun hostReadiness(): WifiReadiness = when {
        wifiManager == null -> WifiReadiness.UNSUPPORTED
        !WifiPermissions.granted(appContext) -> WifiReadiness.PERMISSION_REQUIRED
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            locationManager != null && !LocationManagerCompat.isLocationEnabled(locationManager) -> WifiReadiness.LOCATION_OFF
        else -> WifiReadiness.READY
    }

    val isWifiEnabled: Boolean get() = wifiManager?.isWifiEnabled == true

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

    private val MOBILE_PREFIXES = listOf("rmnet", "ccmni", "pdp", "v4-", "tun", "dummy", "ppp", "clat", "ims", "wwan")

    /**
     * Private IPv4 addresses of this device's Wi-Fi interfaces (station, hotspot and Wi-Fi
     * Direct), most relevant first. Cellular and VPN interfaces are excluded.
     */
    fun lanAddresses(includeDirect: Boolean = false): List<Inet4Address> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .filter { iface -> MOBILE_PREFIXES.none { iface.name.startsWith(it) } }
            .filter { includeDirect || !it.name.startsWith("p2p") }
            .sortedBy { if (it.name.startsWith("wlan0")) 1 else 0 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .distinct()
            .take(4)
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

/** Credentials of the local hotspot hosted by the receiver. */
class HotspotInfo(val ssid: String, val passphrase: String, val security: Int)

/** The local hotspot could not be started; [reason] is a [WifiManager.LocalOnlyHotspotCallback] code or -1. */
class HotspotException(val reason: Int, message: String = "Local hotspot failed ($reason)") : IOException(message)

/**
 * Hosts a local-only Wi-Fi hotspot: a real Wi-Fi network with a system-generated name and
 * password that other devices can join. It carries no internet traffic and stays up only while
 * this application holds the reservation.
 */
@SuppressLint("MissingPermission")
class LocalHotspot(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var onStopped: (() -> Unit)? = null

    val isSupported: Boolean get() = wifiManager != null

    suspend fun start(): HotspotInfo {
        val manager = wifiManager ?: throw HotspotException(-1, "Wi-Fi unavailable")
        stop()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(started: WifiManager.LocalOnlyHotspotReservation) {
                        if (!continuation.isActive) {
                            runCatching { started.close() }
                            return
                        }
                        val info = runCatching { describe(started) }.getOrNull()
                        if (info == null || info.ssid.isEmpty()) {
                            runCatching { started.close() }
                            continuation.resumeWithException(HotspotException(-1, "Hotspot configuration unavailable"))
                        } else {
                            reservation = started
                            continuation.resume(info)
                        }
                    }

                    override fun onStopped() {
                        reservation = null
                        onStopped?.invoke()
                    }

                    override fun onFailed(reason: Int) {
                        if (continuation.isActive) continuation.resumeWithException(HotspotException(reason))
                    }
                }
                try {
                    manager.startLocalOnlyHotspot(callback, android.os.Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(HotspotException(-1, e.message ?: "Hotspot unavailable"))
                }
            }
        }
    }

    fun stop() {
        val current = reservation ?: return
        reservation = null
        runCatching { current.close() }
    }

    @Suppress("DEPRECATION")
    private fun describe(reservation: WifiManager.LocalOnlyHotspotReservation): HotspotInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = reservation.softApConfiguration
            val security = when (config.securityType) {
                android.net.wifi.SoftApConfiguration.SECURITY_TYPE_OPEN -> SECURITY_OPEN
                android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> SECURITY_WPA2
                android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> SECURITY_WPA3_TRANSITION
                android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> SECURITY_WPA3
                else -> SECURITY_OWE
            }
            return HotspotInfo(config.ssid.orEmpty().removeSurrounding("\""), config.passphrase.orEmpty(), security)
        }
        val config = reservation.wifiConfiguration ?: throw HotspotException(-1, "Hotspot configuration unavailable")
        val passphrase = config.preSharedKey.orEmpty().removeSurrounding("\"")
        return HotspotInfo(
            ssid = config.SSID.orEmpty().removeSurrounding("\""),
            passphrase = passphrase,
            security = if (passphrase.isEmpty()) SECURITY_OPEN else SECURITY_WPA2,
        )
    }

    companion object {
        // Mirrors LinkInvitation security constants.
        const val SECURITY_OPEN = 0
        const val SECURITY_WPA2 = 1
        const val SECURITY_WPA3_TRANSITION = 2
        const val SECURITY_WPA3 = 3
        const val SECURITY_OWE = 4
    }
}

/**
 * Joins a hotspot through a peer-to-peer [android.net.wifi.WifiNetworkSpecifier] request. The
 * system asks the user to confirm the connection once; the network stays connected while the
 * request is held and is released by [release].
 */
class HotspotJoiner(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun join(ssid: String, passphrase: String, security: Int, timeoutMillis: Int = 60_000): Network {
        release()
        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            when (security) {
                LocalHotspot.SECURITY_OPEN -> Unit
                LocalHotspot.SECURITY_OWE -> setIsEnhancedOpen(true)
                LocalHotspot.SECURITY_WPA3 -> setWpa3Passphrase(passphrase)
                else -> setWpa2Passphrase(passphrase)
            }
        }.build()
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (continuation.isActive) continuation.resume(network)
                    }

                    override fun onUnavailable() {
                        if (continuation.isActive) {
                            continuation.resumeWithException(WifiDirectException(WifiP2pManager.ERROR, "Network not joined"))
                        }
                    }
                }
                callback = networkCallback
                continuation.invokeOnCancellation { release() }
                try {
                    connectivity.requestNetwork(request, networkCallback, timeoutMillis)
                } catch (e: Exception) {
                    callback = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(WifiDirectException(WifiP2pManager.ERROR, e.message ?: "Request refused"))
                    }
                }
            }
        }
    }

    /** Candidate addresses of the hotspot owner: the gateway and DHCP server of [network]. */
    fun ownerAddresses(network: Network): List<InetAddress> {
        val properties = connectivity.getLinkProperties(network) ?: return emptyList()
        val candidates = LinkedHashSet<InetAddress>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            properties.dhcpServerAddress?.let { if (it is Inet4Address) candidates += it }
        }
        properties.routes.mapNotNull { it.gateway }.filterIsInstance<Inet4Address>()
            .filter { !it.isAnyLocalAddress }
            .forEach { candidates += it }
        return candidates.toList()
    }

    fun release() {
        val current = callback ?: return
        callback = null
        runCatching { connectivity.unregisterNetworkCallback(current) }
    }
}
