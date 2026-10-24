package com.example.a6300test

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Talks to the A6300 over Sony's Camera Remote API (discovery by SSDP, then JSON over HTTP).
 * All methods block, so call them from a background thread.
 */
class CameraClient(private val ctx: Context) {

    var url: String? = null
    private var rpcId = 1

    data class Event(val status: String, val apis: List<String>, val ok: Boolean)

    // ---- Wi-Fi ----------------------------------------------------------

    /** Makes this app's traffic use the Wi-Fi network (the camera has no internet). */
    fun bindWifi(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return false
        return cm.bindProcessToNetwork(wifi)
    }

    // ---- Connect --------------------------------------------------------

    fun connect(log: (String) -> Unit): Boolean {
        if (!bindWifi()) {
            log("No Wi-Fi network found. Join the camera's Wi-Fi first.")
            return false
        }
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("a6300ssdp")
        lock.setReferenceCounted(false)
        lock.acquire()
        var location: String? = null
        try {
            val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n" +
                "USER-AGENT: Android/11 A6300Intervalometer/1.0\r\n\r\n").toByteArray()
            DatagramSocket().use { s ->
                s.soTimeout = 1000
                val group = InetAddress.getByName("239.255.255.250")
                val end = System.currentTimeMillis() + 10000
                var lastSend = 0L
                val buf = ByteArray(2048)
                while (location == null && System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() - lastSend > 2000) {
                        s.send(DatagramPacket(msg, msg.size, group, 1900))
                        lastSend = System.currentTimeMillis()
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val text = String(p.data, 0, p.length)
                        Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.let {
                            location = it.groupValues[1]
                        }
                    } catch (e: SocketTimeoutException) {
                        // keep waiting
                    }
                }
            }
        } finally {
            lock.release()
        }
        val loc = location
        if (loc == null) {
            log("No camera replied. Is the camera in remote mode and the phone on its Wi-Fi?")
            return false
        }
        val dd = try {
            httpGet(loc)
        } catch (e: Exception) {
            log("Could not read the camera's device description: $e")
            return false
        }
        val m = Regex(
            "<av:X_ScalarWebAPI_ServiceType>camera</av:X_ScalarWebAPI_ServiceType>\\s*" +
                "<av:X_ScalarWebAPI_ActionList_URL>(.*?)</av:X_ScalarWebAPI_ActionList_URL>",
            RegexOption.DOT_MATCHES_ALL
        ).find(dd)
        if (m == null) {
            log("Camera service not found in the device description.")
            return false
        }
        url = m.groupValues[1].trim().trimEnd('/') + "/camera"

        if (namesFrom(rpc("getAvailableApiList")).contains("startRecMode")) {
            log("startRecMode -> " + rpc("startRecMode"))
            Thread.sleep(2000)
        }
        val ev = readEvent()
        log("Connected: $url (status ${ev.status}, ${ev.apis.size} APIs available)")
        return ev.ok
    }

    // ---- Status ---------------------------------------------------------

    fun readEvent(): Event {
        val r = rpc("getEvent", JSONArray().put(false), "1.0", 5000)
        return try {
            val res = JSONObject(r).getJSONArray("result")
            var status = "?"
            var names: List<String> = emptyList()
            for (i in 0 until res.length()) {
                val o = res.optJSONObject(i) ?: continue
                when (o.optString("type")) {
                    "cameraStatus" -> status = o.optString("cameraStatus")
                    "availableApiList" -> {
                        val a = o.optJSONArray("names")
                        if (a != null) names = (0 until a.length()).map { a.getString(it) }
                    }
                }
            }
            Event(status, names, status != "?")
        } catch (e: Exception) {
            Event("?", emptyList(), false)
        }
    }

    /** Polls the camera until [cond] is true or the time runs out. Rebinds Wi-Fi if the camera is unreachable. */
    fun waitFor(timeoutMs: Long, cond: (String, List<String>) -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val ev = readEvent()
            if (ev.ok && cond(ev.status, ev.apis)) return true
            if (!ev.ok) bindWifi()
            Thread.sleep(400)
        }
        return false
    }

    fun supportedShutterSpeeds(): List<String> {
        return try {
            val arr = JSONObject(rpc("getSupportedShutterSpeed")).getJSONArray("result").getJSONArray(0)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Current value of a setting via its get call, e.g. getIsoSpeedRate -> "1600". Null if unavailable. */
    fun currentValue(getMethod: String): String? {
        return try {
            JSONObject(rpc(getMethod)).getJSONArray("result").getString(0)
        } catch (e: Exception) {
            null
        }
    }

    // ---- HTTP -----------------------------------------------------------

    fun hasResult(resp: String) = resp.contains("\"result\"")

    private fun namesFrom(resp: String): List<String> {
        return try {
            val arr = JSONObject(resp).optJSONArray("result")?.optJSONArray(0) ?: return emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 10000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    @Synchronized
    private fun nextId(): Int = rpcId++

    fun rpc(
        method: String,
        params: JSONArray = JSONArray(),
        version: String = "1.0",
        readTimeoutMs: Int = 15000
    ): String {
        val u = url ?: return "NO CAMERA URL"
        val body = JSONObject()
            .put("method", method)
            .put("params", params)
            .put("id", nextId())
            .put("version", version)
            .toString()
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${c.responseCode}"
        } catch (e: Exception) {
            "REQUEST FAILED: $e"
        }
    }
}
