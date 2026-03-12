package com.localcall.app.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class UpnpMappedPort(
    val protocol: String,
    val externalPort: Int,
    val internalPort: Int
)

class UpnpPortMapper(private val context: Context) {

    companion object {
        private const val TAG = "UpnpPortMapper"
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DEFAULT_TIMEOUT_MS = 2500
        private const val DEFAULT_SOCKET_TIMEOUT_MS = 4000

        private val SERVICE_TYPES = listOf(
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1"
        )
    }

    var enabled: Boolean = true
    var descriptionPrefix: String = "LocalCallApp"
    var leaseDurationSec: Int = 0
    var discoveryTimeoutMs: Int = DEFAULT_TIMEOUT_MS

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    private var controlUrl: String? = null
    private var serviceType: String? = null
    private var localIp: String? = null
    private val mappings = mutableListOf<UpnpMappedPort>()
    private var mappingJob: Job? = null

    init {
        descriptionPrefix = context.packageName.ifBlank { "LocalCallApp" }
    }

    fun refreshMappings(
        tcpPort: Int,
        udpPort: Int,
        localIpHint: String? = null,
        mapTcp: Boolean = true,
        mapUdp: Boolean = true
    ) {
        if (!enabled) {
            stopMappings()
            return
        }
        mappingJob?.cancel()
        mappingJob = scope.launch {
            synchronized(lock) {
                removeMappingsLocked()
                val discovered = discoverGatewayLocked()
                if (!discovered) {
                    Log.w(TAG, "UPnP gateway not found")
                    return@synchronized
                }

                localIp = localIpHint?.takeIf { it.isNotBlank() } ?: detectLocalIp()
                val ip = localIp
                if (ip.isNullOrBlank()) {
                    Log.w(TAG, "UPnP local IP not detected")
                    return@synchronized
                }

                if (mapTcp) {
                    addMappingLocked("TCP", tcpPort, tcpPort, ip)
                }
                if (mapUdp) {
                    addMappingLocked("UDP", udpPort, udpPort, ip)
                }

                if (mappings.isNotEmpty()) {
                    getExternalIpLocked()?.let { ext ->
                        Log.i(TAG, "UPnP mapped. External IP: $ext")
                    }
                }
            }
        }
    }

    fun stopMappings() {
        mappingJob?.cancel()
        mappingJob = scope.launch {
            synchronized(lock) {
                removeMappingsLocked()
                controlUrl = null
                serviceType = null
                localIp = null
            }
        }
    }

    fun release() {
        mappingJob?.cancel()
        Thread {
            synchronized(lock) {
                removeMappingsLocked()
                controlUrl = null
                serviceType = null
                localIp = null
            }
        }.start()
        scope.cancel()
    }

    private fun discoverGatewayLocked(): Boolean {
        val timeout = discoveryTimeoutMs.coerceAtLeast(1000)
        val locations = linkedSetOf<String>()

        for (st in SERVICE_TYPES) {
            val request = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: $st\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.UTF_8)

            val socket = DatagramSocket()
            try {
                socket.soTimeout = timeout
                socket.broadcast = true
                socket.send(
                    DatagramPacket(
                        request,
                        request.size,
                        java.net.InetAddress.getByName(SSDP_HOST),
                        SSDP_PORT
                    )
                )
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeout) {
                    val buf = ByteArray(4096)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val response = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    val headers = parseSsdpHeaders(response)
                    val location = headers["location"]
                    if (!location.isNullOrBlank()) {
                        locations.add(location)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UPnP SSDP error: ${e.message}")
            } finally {
                socket.close()
            }
        }

        for (location in locations) {
            val discovered = discoverControlUrl(location)
            if (discovered != null) {
                controlUrl = discovered.first
                serviceType = discovered.second
                Log.i(TAG, "UPnP gateway discovered: $controlUrl")
                return true
            }
        }
        return false
    }

    private fun discoverControlUrl(location: String): Pair<String, String>? {
        val xml = fetchText(location) ?: return null
        val normalized = xml.replace("\n", " ").replace("\r", " ")
        val serviceRegex = Regex(
            "<service>\\s*(.*?)\\s*</service>",
            setOf(RegexOption.IGNORE_CASE)
        )
        val serviceTypeRegex = Regex("<serviceType>\\s*([^<]+)\\s*</serviceType>", RegexOption.IGNORE_CASE)
        val controlUrlRegex = Regex("<controlURL>\\s*([^<]+)\\s*</controlURL>", RegexOption.IGNORE_CASE)

        for (match in serviceRegex.findAll(normalized)) {
            val chunk = match.groupValues[1]
            val st = serviceTypeRegex.find(chunk)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (!SERVICE_TYPES.contains(st)) continue
            val rawControlUrl = controlUrlRegex.find(chunk)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val absolute = resolveControlUrl(location, rawControlUrl)
            return absolute to st
        }
        return null
    }

    private fun resolveControlUrl(location: String, controlUrl: String): String {
        if (controlUrl.startsWith("http://", true) || controlUrl.startsWith("https://", true)) {
            return controlUrl
        }
        val base = URL(location)
        return URL(base, controlUrl).toString()
    }

    private fun addMappingLocked(protocol: String, externalPort: Int, internalPort: Int, internalIp: String) {
        val ok = soapAction(
            action = "AddPortMapping",
            args = mapOf(
                "NewRemoteHost" to "",
                "NewExternalPort" to externalPort.toString(),
                "NewProtocol" to protocol,
                "NewInternalPort" to internalPort.toString(),
                "NewInternalClient" to internalIp,
                "NewEnabled" to "1",
                "NewPortMappingDescription" to "$descriptionPrefix-$protocol-$externalPort",
                "NewLeaseDuration" to leaseDurationSec.coerceAtLeast(0).toString()
            )
        )
        if (ok) {
            mappings.add(
                UpnpMappedPort(
                    protocol = protocol,
                    externalPort = externalPort,
                    internalPort = internalPort
                )
            )
            Log.i(TAG, "UPnP mapped $protocol $externalPort -> $internalPort")
        } else {
            Log.w(TAG, "UPnP failed to map $protocol $externalPort")
        }
    }

    private fun removeMappingsLocked() {
        if (mappings.isEmpty()) return
        val snapshot = mappings.toList()
        mappings.clear()
        snapshot.forEach { mapping ->
            val ok = soapAction(
                action = "DeletePortMapping",
                args = mapOf(
                    "NewRemoteHost" to "",
                    "NewExternalPort" to mapping.externalPort.toString(),
                    "NewProtocol" to mapping.protocol
                )
            )
            if (ok) {
                Log.i(TAG, "UPnP unmapped ${mapping.protocol} ${mapping.externalPort}")
            }
        }
    }

    private fun getExternalIpLocked(): String? {
        val response = soapActionWithResponse("GetExternalIPAddress", emptyMap()) ?: return null
        val regex = Regex("<NewExternalIPAddress>\\s*([^<]+)\\s*</NewExternalIPAddress>", RegexOption.IGNORE_CASE)
        return regex.find(response)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun soapAction(action: String, args: Map<String, String>): Boolean {
        return soapActionWithResponse(action, args) != null
    }

    private fun soapActionWithResponse(action: String, args: Map<String, String>): String? {
        val control = controlUrl ?: return null
        val service = serviceType ?: return null

        val bodyArgs = buildString {
            args.forEach { (key, value) ->
                append("<$key>${escapeXml(value)}</$key>")
            }
        }

        val body = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="$service">$bodyArgs</u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val conn = openConnection(control) ?: return null
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"$service#$action\"")
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body)
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                null
            } else {
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "UPnP SOAP $action failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection? {
        return try {
            val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
            conn.connectTimeout = DEFAULT_SOCKET_TIMEOUT_MS
            conn.readTimeout = DEFAULT_SOCKET_TIMEOUT_MS
            conn
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchText(url: String): String? {
        val conn = openConnection(url) ?: return null
        return try {
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "UPnP fetch $url failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSsdpHeaders(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        raw.split("\r\n", "\n").forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim().lowercase(Locale.US)
            val value = line.substring(idx + 1).trim()
            map[key] = try {
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                value
            }
        }
        return map
    }

    private fun detectLocalIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { iface -> !iface.isLoopback && iface.isUp && !iface.isVirtual }
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { addr ->
                    addr is Inet4Address &&
                        !addr.isLoopbackAddress &&
                        addr.hostAddress?.startsWith("169.") == false
                }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
