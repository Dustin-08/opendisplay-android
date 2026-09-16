package com.example.opendisplayandroid

import android.app.Activity
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Minimal OpenDisplay *receiver* for Android — turns this tablet into the
 * "extra display" side of the OpenDisplay protocol (see PROTOCOL.md in the
 * peetzweg/opendisplay repo). The unmodified Mac sender app should discover
 * this as if it were an iPhone/iPad, because we advertise the same Bonjour
 * service type and speak the same wire format.
 *
 * This is a first-draft scaffold, NOT tested against real hardware (built
 * without access to an Android device/emulator or a Mac). Read the TODOs —
 * a few things (SPS-driven decoder sizing, clock sync, reconnect edge cases)
 * are simplified and will need debugging on your actual devices.
 *
 * Implements from PROTOCOL.md:
 *  - Section 3: length-prefixed framing (4-byte BE length + payload)
 *  - Section 4: JSON vs video demux heuristic
 *  - Section 5: H.264 Annex B video, telemetry prefix, SPS/PPS on keyframes
 *  - Section 6.1: hello / ping / touch / kf (scroll/pencil/proximity are NOT
 *    implemented yet — easy to add following the same pattern as touch)
 *  - Appendix A "minimal receiver" + "minimal sender" checklist
 *
 * Deliberately NOT implemented yet (see PROTOCOL.md if you want to add them):
 *  - UDP cursor side channel (6.3) — cursor stays on TCP only, which is fine
 *  - Clock sync / pong handling (8.1) — only needed for latency stats
 *  - hello.maxEncodeWide/High decode-ceiling negotiation (6.5)
 *  - Cable upgrade (6.4) — WiFi only for now
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "OpenDisplayReceiver"
        private const val PORT = 9000
        private const val SERVICE_TYPE = "_opensidecar._tcp."
    }

    private lateinit var surfaceView: SurfaceView
    private var surface: android.view.Surface? = null

    private var serverSocket: ServerSocket? = null
    private var currentSocket: Socket? = null
    private var outStream: OutputStream? = null
    private val running = AtomicBoolean(true)

    // All outbound frames go through this single-thread executor so writes
    // never interleave and stay in the order the caller submitted them
    // (important for touch phase ordering: began/moved/ended).
    private val writer = Executors.newSingleThreadExecutor()

    private var decoder: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    // Stable per-install id (PROTOCOL.md 2.1) — regenerated each launch here;
    // TODO persist this in SharedPreferences so it survives app restarts.
    private val deviceId = UUID.randomUUID().toString()

    private lateinit var nsdManager: NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, event -> handleTouch(event); true }

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        thread(name = "opendisplay-accept") { runServer() }
    }

    override fun onDestroy() {
        running.set(false)
        try { registrationListener?.let { nsdManager.unregisterService(it) } } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
        try { currentSocket?.close() } catch (e: Exception) {}
        resetDecoder()
        writer.shutdownNow()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null }

    // ---------------------------------------------------------------
    // Discovery (PROTOCOL.md 2.1) — advertise like an iPhone/iPad would
    // ---------------------------------------------------------------

    private fun registerNsd(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = android.os.Build.MODEL ?: "Android Tablet"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("pv", "3")
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD registered as ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    // ---------------------------------------------------------------
    // Transport (PROTOCOL.md section 1) — we listen, the Mac connects
    // ---------------------------------------------------------------

    private fun runServer() {
        try {
            serverSocket = ServerSocket(PORT)
            registerNsd(PORT)
            Log.i(TAG, "Listening on :$PORT")
            while (running.get()) {
                val socket = serverSocket!!.accept()
                Log.i(TAG, "Sender connected from ${socket.inetAddress}")
                // A new inbound connection replaces the current one (section 1).
                try { currentSocket?.close() } catch (e: Exception) {}
                resetDecoder()
                currentSocket = socket
                socket.tcpNoDelay = true // section 1: SHOULD disable Nagle
                handleConnection(socket)
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Server error", e)
        }
    }

    private fun handleConnection(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        outStream = output

        sendHello() // MUST be first message on every new connection
        startPing(socket)

        try {
            while (running.get() && !socket.isClosed) {
                val frame = readFrame(input) ?: break
                handleFrame(frame)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection lost: ${e.message}")
        } finally {
            if (outStream === output) outStream = null
        }
    }

    // ---------------------------------------------------------------
    // Framing (PROTOCOL.md section 3): [4-byte BE length][payload]
    // ---------------------------------------------------------------

    private fun readFrame(input: InputStream): ByteArray? {
        val lenBytes = readExactly(input, 4) ?: return null
        val len = ((lenBytes[0].toInt() and 0xFF) shl 24) or
                ((lenBytes[1].toInt() and 0xFF) shl 16) or
                ((lenBytes[2].toInt() and 0xFF) shl 8) or
                (lenBytes[3].toInt() and 0xFF)
        // Sanity cap — spec allows sender frames up to "low megabytes".
        if (len <= 0 || len > 8 * 1024 * 1024) return null
        return readExactly(input, len)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun writeFrame(output: OutputStream, payload: ByteArray) {
        val len = payload.size
        val header = byteArrayOf(
            ((len shr 24) and 0xFF).toByte(),
            ((len shr 16) and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun sendControl(json: JSONObject) {
        val output = outStream ?: return
        writer.execute {
            try { writeFrame(output, json.toString().toByteArray(Charsets.UTF_8)) }
            catch (e: Exception) { Log.w(TAG, "send failed: ${e.message}") }
        }
    }

    // ---------------------------------------------------------------
    // Channel demux (PROTOCOL.md section 4, deprecated heuristic but
    // still what pv<=3 speaks)
    // ---------------------------------------------------------------

    private fun handleFrame(frame: ByteArray) {
        val looksLikeJson = frame.size < 32768 && frame.isNotEmpty() &&
                frame[0] == '{'.code.toByte() && frame.none { it == 0.toByte() }
        if (looksLikeJson) handleControlMessage(frame) else handleVideoFrame(frame)
    }

    // ---------------------------------------------------------------
    // Control messages (PROTOCOL.md section 6)
    // ---------------------------------------------------------------

    private fun sendHello() {
        val metrics = resources.displayMetrics
        sendControl(JSONObject().apply {
            put("type", "hello")
            put("pixelsWide", metrics.widthPixels)
            put("pixelsHigh", metrics.heightPixels)
            put("scale", metrics.density.toDouble())
            put("device", "Android")
            put("id", deviceId)
            put("pv", 3)
        })
    }

    private fun startPing(socket: Socket) {
        thread(name = "opendisplay-ping") {
            while (running.get() && !socket.isClosed) {
                sendControl(JSONObject().apply {
                    put("type", "ping")
                    put("t", System.currentTimeMillis())
                })
                try { Thread.sleep(2000) } catch (e: InterruptedException) { return@thread }
            }
        }
    }

    private fun requestKeyframe() {
        sendControl(JSONObject().apply { put("type", "kf") })
    }

    private fun handleControlMessage(frame: ByteArray) {
        try {
            val json = JSONObject(String(frame, Charsets.UTF_8))
            when (json.optString("type")) {
                // TODO 8.1: reply nothing (we're the receiver — WE send ping,
                // Mac replies "pong"); use pong.t/mt here if you want e2e
                // latency stats like the official iOS app shows.
                "pong" -> {}
                "welcome" -> Log.i(TAG, "sender welcome: pv=${json.optInt("pv")} min=${json.optInt("min")}")
                "updateRequired" -> Log.w(TAG, "sender requires update: ${json.optString("message")}")
                "sleeping", "closing" -> Log.i(TAG, "sender ending session (${json.optString("type")})")
                else -> {} // unknown types MUST be ignored, per spec
            }
        } catch (e: Exception) {
            // not valid JSON — MUST be ignored, not fatal, per spec section 6
        }
    }

    // ---------------------------------------------------------------
    // Video (PROTOCOL.md section 5) — H.264 Annex B -> MediaCodec -> Surface
    // ---------------------------------------------------------------

    private fun handleVideoFrame(frame: ByteArray) {
        // Strip the optional leading telemetry JSON ({"cap":..,"snd":..})
        // that precedes the first Annex B start code (section 5.1).
        val firstStart = findStartCode(frame, 0)
        if (firstStart < 0) return
        val videoBytes = if (firstStart > 0) frame.copyOfRange(firstStart, frame.size) else frame

        extractParamSets(videoBytes) // keyframes re-carry fresh SPS/PPS

        val dec = decoder
        if (dec == null) {
            if (sps != null && pps != null) createDecoder()
            else requestKeyframe() // need a keyframe to get SPS/PPS
            return
        }
        feedDecoder(dec, videoBytes)
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            i++
        }
        return -1
    }

    private fun extractParamSets(data: ByteArray) {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                starts.add(i); i += 4
            } else i++
        }
        for (idx in starts.indices) {
            val nalStart = starts[idx]
            val payloadStart = nalStart + 4
            val end = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            if (payloadStart >= end) continue
            when (data[payloadStart].toInt() and 0x1F) {
                7 -> sps = data.copyOfRange(nalStart, end) // SPS, start code kept for csd-0
                8 -> pps = data.copyOfRange(nalStart, end) // PPS, start code kept for csd-1
            }
        }
    }

    private fun createDecoder() {
        try {
            // TODO: parse the real width/height out of the SPS (section 5.2
            // says receivers MUST take dimensions from the SPS, not hello).
            // 1920x1080 is just a starting guess; MediaCodec corrects itself
            // via INFO_OUTPUT_FORMAT_CHANGED once real frames arrive, but a
            // wildly wrong initial size can make some devices reject configure().
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
            Log.i(TAG, "decoder created")
        } catch (e: Exception) {
            Log.e(TAG, "decoder setup failed", e)
            requestKeyframe()
        }
    }

    private fun resetDecoder() {
        try { decoder?.stop() } catch (e: Exception) {}
        try { decoder?.release() } catch (e: Exception) {}
        decoder = null
        sps = null
        pps = null
    }

    private fun feedDecoder(codec: MediaCodec, nalData: ByteArray) {
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)
                buf?.clear()
                buf?.put(nalData)
                codec.queueInputBuffer(inIndex, 0, nalData.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, true) // true = render straight to the Surface
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode error, requesting keyframe", e)
            resetDecoder()
            requestKeyframe()
        }
    }

    // ---------------------------------------------------------------
    // Touch input (PROTOCOL.md 6.1 "touch") — the whole point of this app
    // ---------------------------------------------------------------

    private fun handleTouch(event: MotionEvent) {
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> "began"
            MotionEvent.ACTION_MOVE -> "moved"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> "ended"
            MotionEvent.ACTION_CANCEL -> "cancelled"
            else -> return
        }
        // Coordinates are normalized 0..1 against VIDEO space (section 7).
        // Since the decoded video fills this SurfaceView, view size stands
        // in for video size here.
        val nx = (event.x / surfaceView.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val ny = (event.y / surfaceView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        sendControl(JSONObject().apply {
            put("type", "touch")
            put("phase", phase)
            put("x", nx.toDouble())
            put("y", ny.toDouble())
            // t (sender-clock timestamp) omitted — we haven't implemented
            // clock sync (8.1) yet, and the spec says senders MUST tolerate
            // an absent t.
        })
    }
}
