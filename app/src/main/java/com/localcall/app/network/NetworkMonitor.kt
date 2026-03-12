package com.localcall.app.network

import android.content.*
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Monitors WiFi network connection and provides callbacks when connected/disconnected.
 * Also provides utility methods to get network information.
 */
class NetworkMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false
    
    private var _isConnected = false
    private var _currentIp: String? = null
    private var _subnetMask: Int? = null
    private var _broadcastAddress: String? = null
    
    val isConnected: Boolean get() = _isConnected
    val currentIp: String? get() = _currentIp
    val subnetMask: Int? get() = _subnetMask
    val broadcastAddress: String? get() = _broadcastAddress
    
    // Callbacks
    var onWifiConnected: ((String) -> Unit)? = null
    var onWifiDisconnected: (() -> Unit)? = null
    var onIpChanged: ((String) -> Unit)? = null
    
    /**
     * Start monitoring network changes
     */
    fun startMonitoring() {
        if (isRegistered) {
            Log.w(TAG, "Already monitoring")
            return
        }
        
        Log.d(TAG, "Starting network monitoring")
        
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "WiFi network available")
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                
                if (isWifi && hasInternet) {
                    updateNetworkInfo(network)
                }
            }
            
            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) {
                updateNetworkInfo(network)
            }
            
            override fun onLost(network: Network) {
                Log.d(TAG, "WiFi network lost")
                handleDisconnect()
            }
        }
        
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            isRegistered = true
            
            // Check current state
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    updateNetworkInfo(activeNetwork)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }
    
    /**
     * Stop monitoring network changes
     */
    fun stopMonitoring() {
        if (!isRegistered) return
        
        Log.d(TAG, "Stopping network monitoring")
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister: ${e.message}")
            }
        }
        networkCallback = null
        isRegistered = false
    }
    
    private fun updateNetworkInfo(network: Network) {
        try {
            val linkProps = connectivityManager.getLinkProperties(network)
            val ip = linkProps?.linkAddresses?.firstOrNull { 
                it.address is Inet4Address 
            }?.address
            
            if (ip != null) {
                val oldIp = _currentIp
                val wasConnected = _isConnected
                _currentIp = ip.hostAddress
                _isConnected = true
                
                // Calculate broadcast address
                _subnetMask = linkProps?.linkAddresses?.firstOrNull { 
                    it.address is Inet4Address 
                }?.prefixLength
                
                _broadcastAddress = calculateBroadcastAddress(ip, _subnetMask ?: 24)
                
                Log.d(TAG, "WiFi connected: IP=$_currentIp, Broadcast=$_broadcastAddress")
                
                if (oldIp != _currentIp) {
                    onIpChanged?.invoke(_currentIp!!)
                }
                
                if (!wasConnected) {
                    onWifiConnected?.invoke(_currentIp!!)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update network info: ${e.message}")
        }
    }
    
    private fun handleDisconnect() {
        _isConnected = false
        _currentIp = null
        _subnetMask = null
        _broadcastAddress = null
        onWifiDisconnected?.invoke()
    }
    
    private fun calculateBroadcastAddress(ip: InetAddress, prefixLength: Int): String {
        val ipBytes = ip.address
        val mask = (-1L shl (32 - prefixLength))
        val ipLong = ipBytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val broadcastLong = (ipLong or (mask.inv() and 0xFFFFFFFFL))
        
        return listOf(
            (broadcastLong shr 24) and 0xFF,
            (broadcastLong shr 16) and 0xFF,
            (broadcastLong shr 8) and 0xFF,
            broadcastLong and 0xFF
        ).joinToString(".")
    }
    
    /**
     * Get all possible IP addresses in the current subnet
     */
    fun getSubnetIps(): List<String> {
        val ip = _currentIp ?: return emptyList()
        val mask = _subnetMask ?: 24
        
        return generateSubnetIps(ip, mask)
    }
    
    private fun generateSubnetIps(ip: String, prefixLength: Int): List<String> {
        val ipParts = ip.split(".").map { it.toLong() }
        val ipLong = ipParts.fold(0L) { acc, part -> (acc shl 8) or part }
        
        val networkMask = (-1L shl (32 - prefixLength)) and 0xFFFFFFFFL
        val networkLong = ipLong and networkMask
        
        val hostCount = (1L shl (32 - prefixLength)) - 2
        if (hostCount <= 0L) return listOf(ip)
        
        val ips = mutableListOf<String>()
        for (i in 1L..hostCount) {
            val hostLong = networkLong or i
            val hostIp = listOf(
                (hostLong shr 24) and 0xFF,
                (hostLong shr 16) and 0xFF,
                (hostLong shr 8) and 0xFF,
                hostLong and 0xFF
            ).joinToString(".")
            ips.add(hostIp)
        }
        
        return ips
    }
}
