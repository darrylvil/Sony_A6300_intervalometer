package com.example.a6300test

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Shared state and log, read by the screen and written by the service. */
object Session {
    private val sb = StringBuilder()
    private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.UK)

    @Volatile var running = false
    @Volatile var abort = false
    @Volatile var status = "Idle"
    @Volatile var version = 0

    @Synchronized
    fun log(s: String) {
        val line = fmt.format(Date()) + "  " + s
        android.util.Log.d("A6300", line)
        sb.append(line).append('\n')
        if (sb.length > 20000) sb.delete(0, sb.length - 15000)
        try {
            logFile?.appendText(line + "\n")
        } catch (e: Exception) {
            // the on-screen log still works
        }
        version++
    }

    @Synchronized
    fun text(): String = sb.toString()

    @Synchronized
    fun setLogFile(f: File?) {
        logFile = f
    }
}

/**
 * Runs the whole exposure sequence as a foreground service, so Android keeps
 * it alive with the screen off.
 */
class SequenceService : Service() {

    data class Params(val delay: Int, val exp: Int, val frames: Int, val gap: Int, val iso: String)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ABORT -> {
                if (Session.running) {
                    Session.abort = true
                    Session.log("Abort requested")
                } else {
                    stopSelf()
                }
            }
            ACTION_START -> {
                startForegroundNow()
                if (Session.running) return START_NOT_STICKY
                val p = Params(
                    intent.getIntExtra(EXTRA_DELAY, 0),
                    intent.getIntExtra(EXTRA_EXP, 60),
                    intent.getIntExtra(EXTRA_FRAMES, 1),
                    intent.getIntExtra(EXTRA_GAP, 3),
                    intent.getStringExtra(EXTRA_ISO) ?: "1600"
                )
                Session.running = true
                Session.abort = false
                acquireLocks()
                thread {
                    try {
                        runSequence(p)
                    } catch (e: Exception) {
                        Session.log("ERROR: $e")
                        Session.status = "Error: $e"
                    } finally {
                        releaseLocks()
                        Session.running = false
                        stopForeground(true)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Exposure sequence", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("A6300 sequence running")
            .setContentText("Keeping the camera link alive")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a6300:sequence").apply {
            acquire(8 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "a6300:wifi").apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------
    // The sequence
    // ------------------------------------------------------------------
    private fun runSequence(p: Params) {
        val log = { s: String -> Session.log(s) }
        val cam = CameraClient(applicationContext)
        val summary = mutableListOf<String>()
        val seqStart = now()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        val f = File(getExternalFilesDir(null), "session_$stamp.txt")
        Session.setLogFile(f)

        log("== Sequence: ${p.frames} x ${p.exp} s, gap ${p.gap} s, ISO ${p.iso}, start delay ${p.delay} s ==")
        log("Log file: ${f.absolutePath}")
        Session.status = "Connecting to camera"
        if (!cam.connect(log)) {
            Session.status = "Could not connect to the camera"
            return
        }
        log("Storage: " + cam.rpc("getStorageInformation").take(300))

        val useBulb = p.exp > 30
        val shutter = if (useBulb) "BULB" else pickShutter(cam, p.exp)
        if (shutter == null) {
            Session.status = "Could not read the camera's shutter speeds (dial on M?)"
            log(Session.status)
            return
        }
        Session.status = "Waiting for the camera to be ready"
        if (!cam.waitFor(45000) { _, apis -> apis.contains("setIsoSpeedRate") && apis.contains("setShutterSpeed") }) {
            Session.status = "Camera not ready for settings (dial on M?) - nothing was shot"
            log(Session.status)
            return
        }
        if (!ensureSettings(cam, p.iso, shutter)) {
            Session.status = "Could not set ISO/shutter - nothing was shot"
            log(Session.status)
            return
        }
        val needed = if (useBulb) "startBulbShooting" else "actTakePicture"
        if (!cam.waitFor(15000) { _, apis -> apis.contains(needed) }) {
            Session.status = "Camera did not become ready (dial on M?)"
            log(Session.status)
            return
        }

        // Optional start delay (lets vibrations die down).
        val delayEnd = now() + p.delay * 1000L
        while (now() < delayEnd && !Session.abort) {
            Session.status = "Starting in ${(delayEnd - now()) / 1000 + 1} s"
            Thread.sleep(250)
        }

        var done = 0
        for (i in 1..p.frames) {
            if (Session.abort) break
            Session.status = "Frame $i/${p.frames}: waiting for camera"
            if (!cam.waitFor(60000) { st, _ -> st == "IDLE" }) {
                log("Camera not idle after 60 s - stopping the sequence.")
                break
            }
            if (!ensureSettings(cam, p.iso, shutter)) {
                log("ISO/shutter could not be confirmed - stopping the sequence.")
                Session.status = "Stopped: could not confirm ISO/shutter"
                break
            }
            log("-- Frame $i of ${p.frames} --")
            val line = if (useBulb) bulbFrame(cam, p, i) else normalFrame(cam, p, i, shutter)
            if (line == null) break
            summary.add(line)
            done++

            if (i < p.frames && !Session.abort) {
                val gapEnd = now() + p.gap * 1000L
                while (now() < gapEnd && !Session.abort) {
                    Session.status = "Frame $i done. Next in ${(gapEnd - now()) / 1000 + 1} s"
                    Thread.sleep(250)
                }
            }
        }

        log("== Summary: $done of ${p.frames} frames, total ${(now() - seqStart) / 1000} s ==")
        summary.forEach { log(it) }
        Session.status = if (Session.abort) "Aborted after $done frames" else "Finished: $done of ${p.frames} frames"
        log(Session.status)
    }

    /** Makes sure the camera really has the wanted ISO and shutter speed, re-applying them if not. */
    private fun ensureSettings(cam: CameraClient, iso: String, shutter: String): Boolean {
        val okIso = cam.currentValue("getIsoSpeedRate") == iso ||
            setAndVerify(cam, "setIsoSpeedRate", "getIsoSpeedRate", iso)
        val okShutter = cam.currentValue("getShutterSpeed") == shutter ||
            setAndVerify(cam, "setShutterSpeed", "getShutterSpeed", shutter)
        if (okIso && okShutter) Session.log("Settings confirmed: ISO $iso, shutter $shutter")
        return okIso && okShutter
    }

    private fun setAndVerify(cam: CameraClient, setMethod: String, getMethod: String, value: String): Boolean {
        for (attempt in 1..5) {
            val r = cam.rpc(setMethod, JSONArray().put(value))
            if (cam.hasResult(r)) {
                val end = now() + 6000
                while (now() < end) {
                    if (cam.currentValue(getMethod) == value) {
                        Session.log("$setMethod $value confirmed (attempt $attempt)")
                        return true
                    }
                    Thread.sleep(500)
                }
            } else {
                Session.log("$setMethod $value attempt $attempt failed: ${r.take(100)}")
            }
            Thread.sleep(2000)
        }
        return false
    }

    /** Picks the camera's own shutter speed closest to the wanted number of seconds. */
    private fun pickShutter(cam: CameraClient, wanted: Int): String? {
        val list = cam.supportedShutterSpeeds()
        var best: String? = null
        var bestDiff = Double.MAX_VALUE
        for (s in list) {
            val sec = seconds(s) ?: continue
            val d = Math.abs(sec - wanted)
            if (d < bestDiff) {
                bestDiff = d
                best = s
            }
        }
        if (best != null) Session.log("Closest camera shutter speed to $wanted s: $best (camera offers ${list.size} speeds)")
        return best
    }

    private fun seconds(s: String): Double? {
        if (s == "BULB") return null
        if (s.endsWith("\"")) return s.dropLast(1).toDoubleOrNull()
        if (s.contains("/")) {
            val a = s.split("/")
            val n = a[0].toDoubleOrNull() ?: return null
            val d = a[1].toDoubleOrNull() ?: return null
            return if (d == 0.0) null else n / d
        }
        return s.toDoubleOrNull()
    }

    // ---- Frame types ---------------------------------------------------

    /** Frames up to 30 s: the camera times and ends the exposure itself. */
    private fun normalFrame(cam: CameraClient, p: Params, i: Int, shutter: String): String? {
        val t0 = now()
        Session.status = "Frame $i/${p.frames}: exposing ($shutter, camera-timed)"
        var r = cam.rpc("actTakePicture", JSONArray(), "1.0", 60000)
        var tries = 0
        while (r.contains("40403") && tries < 40) {
            tries++
            Session.status = "Frame $i/${p.frames}: exposing ($shutter), ${(now() - t0) / 1000} s"
            r = cam.rpc("awaitTakePicture", JSONArray(), "1.0", 60000)
        }
        if (!cam.hasResult(r)) {
            Session.log("Frame $i: no confirmation from the camera ($r). Waiting for it to settle.")
            cam.waitFor(120000) { st, _ -> st == "IDLE" }
        }
        val file = fileName(r)
        val line = "Frame %d: %s, took %.1f s, file=%s".format(i, shutter, (now() - t0) / 1000.0, file)
        Session.log(line)
        return line
    }

    /** Frames over 30 s: bulb. The app must close the shutter, so it retries until the camera confirms. */
    private fun bulbFrame(cam: CameraClient, p: Params, i: Int): String? {
        val t0 = now()
        val sr = cam.rpc("startBulbShooting", JSONArray(), "1.0", 20000)
        if (!cam.hasResult(sr)) {
            Session.log("startBulbShooting failed: ${sr.take(200)}")
            Session.status = "Could not start the exposure"
            return null
        }
        val opened = cam.waitFor(8000) { st, _ -> st == "StillCapturing" }
        Session.log("Shutter open after %.1f s (confirmed=%b)".format((now() - t0) / 1000.0, opened))

        val holdEnd = t0 + p.exp * 1000L
        var lastReport = now()
        while (now() < holdEnd && !Session.abort) {
            Thread.sleep(250)
            Session.status = "Frame $i/${p.frames}: exposing ${(now() - t0) / 1000} / ${p.exp} s"
            if (now() - lastReport >= 30000) {
                Session.log("  t=%d s status=%s".format((now() - t0) / 1000, cam.readEvent().status))
                lastReport = now()
            }
        }

        val tStop = now()
        Session.status = "Frame $i/${p.frames}: closing shutter"
        val stopped = stopBulb(cam)
        val stopDelay = (now() - tStop) / 1000.0
        val held = (now() - t0) / 1000.0

        var pic = cam.rpc("awaitTakePicture", JSONArray(), "1.0", 30000)
        var tries = 0
        while (pic.contains("40403") && tries < 20) {
            tries++
            pic = cam.rpc("awaitTakePicture", JSONArray(), "1.0", 30000)
        }
        val idle = cam.waitFor(120000) { st, _ -> st == "IDLE" }
        val suspect = if (stopDelay > 5.0 || !stopped) "  ** SUSPECT: stop was delayed **" else ""
        val line = "Frame %d: wanted %d s, open about %.1f s (stop took %.1f s), idle=%b, file=%s%s".format(
            i, p.exp, held, stopDelay, idle, fileName(pic), suspect
        )
        Session.log(line)
        return if (stopped) line else null
    }

    /** Keeps trying to close a bulb exposure, through Wi-Fi drops, for up to 15 minutes. */
    private fun stopBulb(cam: CameraClient): Boolean {
        val deadline = now() + 15 * 60 * 1000L
        var attempts = 0
        while (now() < deadline) {
            attempts++
            val ev = cam.readEvent()
            if (ev.ok) {
                if (ev.status != "StillCapturing" && !ev.apis.contains("stopBulbShooting")) {
                    Session.log("Exposure is closed (status ${ev.status})")
                    return true
                }
                val r = cam.rpc("stopBulbShooting", JSONArray(), "1.0", 20000)
                Session.log("stopBulbShooting (attempt $attempts) -> ${r.take(120)}")
                if (cam.hasResult(r)) return true
            } else {
                cam.bindWifi()
                Session.status = "CONNECTION LOST - retrying to close the shutter ($attempts)"
                if (attempts == 1 || attempts % 10 == 0) Session.log("Camera unreachable, retrying (attempt $attempts)")
            }
            Thread.sleep(2000)
        }
        Session.log("GAVE UP closing the shutter after 15 minutes - CHECK THE CAMERA")
        Session.status = "COULD NOT CLOSE THE SHUTTER - CHECK THE CAMERA"
        return false
    }

    private fun fileName(resp: String): String =
        Regex("[^/\\\\]+\\.(?:JPG|jpg|ARW)").find(resp)?.value ?: "?"

    companion object {
        const val ACTION_START = "com.example.a6300test.START"
        const val ACTION_ABORT = "com.example.a6300test.ABORT"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_EXP = "exp"
        const val EXTRA_FRAMES = "frames"
        const val EXTRA_GAP = "gap"
        const val EXTRA_ISO = "iso"
        private const val CHANNEL = "sequence"
    }
}
