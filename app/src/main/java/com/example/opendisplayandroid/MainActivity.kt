package com.example.opendisplayandroid

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    private lateinit var cursorOverlay: CursorOverlayView
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
        cursorOverlay = CursorOverlayView(this)

        // Video on the bottom, cursor overlay on top, both filling the
        // window. SurfaceView punches a hole through the window by default
        // (no setZOrderOnTop calls here), so a normal View added after it in
        // the same FrameLayout composites above it — no special Z handling
        // needed for the overlay to render over the decoded video.
        val root = FrameLayout(this)
        val matchParent = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(surfaceView, matchParent)
        root.addView(cursorOverlay, matchParent)
        setContentView(root)

        surfaceView.holder.addCallback(this)
        // Touch listener lives on the overlay (the topmost view) since it's
        // the one actually receiving touch events now.
        cursorOverlay.setOnTouchListener { _, event -> handleTouch(event); true }

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
        requestKeyframe() // ask for a full frame right away instead of
        // waiting on the sender's periodic keyframe
        // interval — this is what was causing the long
        // black-screen delay on an idle desktop
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
                "sleeping", "closing" -> {
                    Log.i(TAG, "sender ending session (${json.optString("type")})")
                    cursorOverlay.hide()
                }
                "cursor" -> handleCursorMessage(json)
                // Unknown types MUST be ignored per spec, but we log the raw
                // JSON here — if "cursor" turns out to use a different type
                // string on the wire, this line will show us the real one.
                else -> Log.d(TAG, "unhandled control message: $json")
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
            // Section 5.2 says receivers MUST take dimensions from the SPS,
            // not from hello. We now parse them for real instead of guessing
            // 1920x1080 and hoping MediaCodec self-corrects — a wildly wrong
            // initial size makes some devices reject configure() outright.
            val (w, h) = sps?.let { parseSpsDimensions(it) } ?: run {
                Log.w(TAG, "SPS dimension parse failed, falling back to 1920x1080 guess")
                1920 to 1080
            }
            Log.i(TAG, "configuring decoder at ${w}x${h}")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
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

    // ---------------------------------------------------------------
    // SPS parsing (H.264 spec section 7.3.2.1.1 / 7.4.2.1.1) — just enough
    // exp-golomb decoding to recover pic width/height + cropping. Handles
    // baseline/main/high profiles; scaling-matrix lists are skipped (their
    // values don't affect width/height) rather than fully decoded.
    // ---------------------------------------------------------------

    private fun parseSpsDimensions(spsNal: ByteArray): Pair<Int, Int>? {
        return try {
            // spsNal still has its 4-byte Annex B start code + 1-byte NAL
            // header (see extractParamSets) — RBSP payload starts at index 5.
            val rbsp = unescapeRbsp(spsNal, 5)
            val br = BitReader(rbsp)

            val profileIdc = br.readBits(8)
            br.skipBits(8)  // constraint_set0..5_flag (6 bits) + reserved (2 bits)
            br.skipBits(8)  // level_idc
            br.readUE()     // seq_parameter_set_id

            var chromaFormatIdc = 1
            if (profileIdc in HIGH_PROFILES_WITH_CHROMA_INFO) {
                chromaFormatIdc = br.readUE()
                if (chromaFormatIdc == 3) br.skipBits(1) // separate_colour_plane_flag
                br.readUE() // bit_depth_luma_minus8
                br.readUE() // bit_depth_chroma_minus8
                br.skipBits(1) // qpprime_y_zero_transform_bypass_flag
                if (br.readBit() == 1) { // seq_scaling_matrix_present_flag
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until count) {
                        if (br.readBit() == 1) skipScalingList(br, if (i < 6) 16 else 64)
                    }
                }
            }

            br.readUE() // log2_max_frame_num_minus4
            when (br.readUE()) { // pic_order_cnt_type
                0 -> br.readUE() // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    br.skipBits(1) // delta_pic_order_always_zero_flag
                    br.readSE() // offset_for_non_ref_pic
                    br.readSE() // offset_for_top_to_bottom_field
                    val numRefFrames = br.readUE()
                    repeat(numRefFrames) { br.readSE() }
                }
                // type 2: nothing further to read
            }

            br.readUE() // max_num_ref_frames
            br.skipBits(1) // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbsMinus1 = br.readUE()
            val picHeightInMapUnitsMinus1 = br.readUE()
            val frameMbsOnlyFlag = br.readBit()
            if (frameMbsOnlyFlag == 0) br.skipBits(1) // mb_adaptive_frame_field_flag
            br.skipBits(1) // direct_8x8_inference_flag

            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (br.readBit() == 1) { // frame_cropping_flag
                cropLeft = br.readUE()
                cropRight = br.readUE()
                cropTop = br.readUE()
                cropBottom = br.readUE()
            }

            var width = (picWidthInMbsMinus1 + 1) * 16
            var height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16

            val cropUnitX: Int
            val cropUnitY: Int
            if (chromaFormatIdc == 0) {
                cropUnitX = 1
                cropUnitY = 2 - frameMbsOnlyFlag
            } else {
                val subWidthC = if (chromaFormatIdc == 3) 1 else 2
                val subHeightC = if (chromaFormatIdc == 1) 2 else 1
                cropUnitX = subWidthC
                cropUnitY = subHeightC * (2 - frameMbsOnlyFlag)
            }
            width -= cropUnitX * (cropLeft + cropRight)
            height -= cropUnitY * (cropTop + cropBottom)

            if (width <= 0 || height <= 0) null else width to height
        } catch (e: Exception) {
            Log.w(TAG, "SPS parse error: ${e.message}")
            null
        }
    }

    private fun skipScalingList(br: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        repeat(size) {
            if (nextScale != 0) {
                val deltaScale = br.readSE()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    /** Strips Annex B emulation-prevention 0x03 bytes, starting after the NAL header. */
    private fun unescapeRbsp(nal: ByteArray, headerOffset: Int): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeroRun = 0
        for (i in headerOffset until nal.size) {
            val b = nal[i]
            if (zeroRun >= 2 && b.toInt() == 0x03) {
                zeroRun = 0
                continue
            }
            out.write(b.toInt())
            zeroRun = if (b.toInt() == 0) zeroRun + 1 else 0
        }
        return out.toByteArray()
    }

    /** Profile IDCs whose SPS carries the extra chroma/bit-depth/scaling fields (7.3.2.1.1). */
    private val HIGH_PROFILES_WITH_CHROMA_INFO =
        setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Minimal MSB-first bit reader for exp-golomb SPS parsing. */
    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0
        fun readBit(): Int {
            val bytePos = bitPos / 8
            if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS bit overrun")
            val bit = (data[bytePos].toInt() shr (7 - (bitPos % 8))) and 1
            bitPos++
            return bit
        }
        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or readBit() }
            return v
        }
        fun skipBits(n: Int) { repeat(n) { readBit() } }
        fun readUE(): Int {
            var leadingZeroBits = 0
            while (readBit() == 0) {
                leadingZeroBits++
                if (leadingZeroBits > 32) throw IllegalStateException("bad exp-golomb code")
            }
            if (leadingZeroBits == 0) return 0
            val suffix = readBits(leadingZeroBits)
            return (1 shl leadingZeroBits) - 1 + suffix
        }
        fun readSE(): Int {
            val codeNum = readUE()
            val sign = if (codeNum % 2 == 0) -1 else 1
            return sign * ((codeNum + 1) / 2)
        }
    }

    private fun resetDecoder() {
        try { decoder?.stop() } catch (e: Exception) {}
        try { decoder?.release() } catch (e: Exception) {}
        decoder = null
        sps = null
        pps = null
        cursorOverlay.hide() // stale cursor position shouldn't survive a reconnect
    }

    // ---------------------------------------------------------------
    // Local cursor echo — the sender renders the pointer off the video
    // path (see release notes: "local cursor echo — pointer rendered
    // on-device off the video path") and instead pushes small JSON control
    // messages with the pointer's position (and optionally a sprite image).
    //
    // NOTE: the exact field names below are a best-effort guess — the
    // upstream PROTOCOL.md section for this wasn't available to check
    // directly. handleControlMessage() logs the raw JSON for any message
    // type it doesn't recognize, so if the cursor never appears, check
    // logcat for "unhandled control message" and we can fix the field
    // names to match exactly what's actually on the wire.
    // ---------------------------------------------------------------

    private fun handleCursorMessage(json: JSONObject) {
        try {
            val visible = if (json.has("visible")) json.optBoolean("visible", true) else true
            val x = firstDouble(json, "x", "cursorX", "px")
            val y = firstDouble(json, "y", "cursorY", "py")
            if (x == null || y == null) {
                Log.d(TAG, "cursor message missing x/y, raw: $json")
                return
            }

            var bitmap: Bitmap? = null
            val imageB64 = firstString(json, "image", "sprite", "imageData")
            if (imageB64 != null) {
                try {
                    val bytes = Base64.decode(imageB64, Base64.DEFAULT)
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    Log.w(TAG, "cursor sprite decode failed: ${e.message}")
                }
            }
            // Hotspot as a 0..1 fraction of the sprite's own size; defaults
            // to the top-left corner if the sender doesn't send one, since
            // most arrow-style pointers hotspot near their tip, not center.
            val hotX = (firstDouble(json, "hotX", "hotspotX", "anchorX") ?: 0.0).toFloat()
            val hotY = (firstDouble(json, "hotY", "hotspotY", "anchorY") ?: 0.0).toFloat()

            cursorOverlay.update(visible, x.toFloat(), y.toFloat(), bitmap, hotX, hotY)
        } catch (e: Exception) {
            Log.w(TAG, "cursor message parse error: ${e.message}")
        }
    }

    private fun firstDouble(json: JSONObject, vararg keys: String): Double? {
        for (k in keys) if (json.has(k)) return json.optDouble(k)
        return null
    }

    private fun firstString(json: JSONObject, vararg keys: String): String? {
        for (k in keys) if (json.has(k)) return json.optString(k)
        return null
    }

    /**
     * Transparent overlay drawn on top of the SurfaceView. Shows either a
     * decoded cursor sprite bitmap (if the sender provides one) or a plain
     * dot fallback, positioned from normalized 0..1 coordinates against this
     * view's own size (which matches the video's displayed size 1:1).
     */
    private class CursorOverlayView(context: Context) : View(context) {
        @Volatile private var visible = false
        @Volatile private var xFrac = 0.5f
        @Volatile private var yFrac = 0.5f
        @Volatile private var bitmap: Bitmap? = null
        @Volatile private var hotXFrac = 0f
        @Volatile private var hotYFrac = 0f

        private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val dotOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun update(visible: Boolean, xFrac: Float, yFrac: Float, bitmap: Bitmap?, hotXFrac: Float, hotYFrac: Float) {
            this.visible = visible
            this.xFrac = xFrac
            this.yFrac = yFrac
            if (bitmap != null) this.bitmap = bitmap // keep last sprite if none sent this time
            this.hotXFrac = hotXFrac
            this.hotYFrac = hotYFrac
            postInvalidate()
        }

        fun hide() {
            visible = false
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!visible) return
            val px = xFrac * width
            val py = yFrac * height
            val bmp = bitmap
            if (bmp != null) {
                canvas.drawBitmap(bmp, px - bmp.width * hotXFrac, py - bmp.height * hotYFrac, null)
            } else {
                val r = 10f
                canvas.drawCircle(px, py, r, dotFill)
                canvas.drawCircle(px, py, r, dotOutline)
            }
        }
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
        // The overlay is the same size as the SurfaceView underneath it
        // (both match_parent in the same FrameLayout), so its dimensions
        // stand in for video size here.
        val nx = (event.x / cursorOverlay.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val ny = (event.y / cursorOverlay.height.coerceAtLeast(1)).coerceIn(0f, 1f)
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