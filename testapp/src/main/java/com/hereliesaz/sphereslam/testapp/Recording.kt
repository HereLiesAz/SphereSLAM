package com.hereliesaz.sphereslam.testapp

import com.hereliesaz.sphereslam.attitude.AttitudeSample
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Everything the session was given for one camera frame: the display-upright luma, intrinsics,
 * timestamp, the attitude sample and angles, and the camera-from-device rotation. Live tracking and
 * replay feed the session from this, so a replay sees exactly what the live session saw.
 */
class FrameRecord(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    /** fx, fy, cx, cy of the upright frame. */
    val intrinsics: FloatArray,
    val cameraFromDevice: FloatArray,
    val attitude: AttitudeSample?,
    val headingDeg: Float?,
    val elevationDeg: Float?,
    val rollDeg: Float?,
    val convertNs: Long,
    val luma: ByteArray,
)

/** A target capture: the reference image the session was given and which check it was for. */
class CaptureRecord(val timestampNs: Long, val checkId: String, val side: Int, val crop: ByteArray)

/** Events between frames, in the order they happened. */
sealed class RecordEvent(val timestampNs: Long) {
    class Capture(val capture: CaptureRecord) : RecordEvent(capture.timestampNs)

    /** The session was started afresh ([reason] `tracking` for a restart, `knobs` for a settings change). */
    class Restart(timestampNs: Long, val reason: String) : RecordEvent(timestampNs)

    /** The live knob values from here on. */
    class Knobs(timestampNs: Long, val values: Map<String, Float>) : RecordEvent(timestampNs)

    /** The writer fell behind and dropped [frames] frames here. */
    class Gap(timestampNs: Long, val frames: Int) : RecordEvent(timestampNs)
}

/**
 * The recording file: `SSLREC1\n`, a UTF header (JSON: build, device, camera, timestamp source,
 * start time, knob values), then records — a type byte and its fields — until end of file:
 *
 * - FRAME: timestamp, size, intrinsics, camera-from-device, attitude, angles, convert time, and the
 *   luma deflated (fast setting; camera noise keeps it near 2:1).
 * - CAPTURE: the reference crop, deflated, and its check id.
 * - RESTART, KNOBS, GAP: see [RecordEvent].
 *
 * Little that can be wrong: a truncated last record (the app died mid-write) is ignored on reading.
 */
object RecordingFormat {
    const val MAGIC = "SSLREC1\n"
    const val FRAME: Int = 1
    const val CAPTURE: Int = 2
    const val RESTART: Int = 3
    const val KNOBS: Int = 4
    const val GAP: Int = 5

    fun knobsJson(values: Map<String, Float>): String = JSONObject().apply { values.forEach { (k, v) -> put(k, v.toDouble()) } }.toString()

    fun knobsFrom(json: String): Map<String, Float> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.getDouble(it).toFloat() }
    }.getOrDefault(emptyMap())
}

/** Header fields of a recording. */
class RecordingHeader(
    val build: String,
    val device: String,
    val cameraId: String,
    val realtimeTimestamps: Boolean,
    val startedAt: String,
    val knobs: Map<String, Float>,
) {
    fun toJson(): String = JSONObject()
        .put("build", build)
        .put("device", device)
        .put("cameraId", cameraId)
        .put("realtimeTimestamps", realtimeTimestamps)
        .put("startedAt", startedAt)
        .put("knobs", JSONObject(RecordingFormat.knobsJson(knobs)))
        .toString()

    companion object {
        fun fromJson(json: String): RecordingHeader {
            val o = JSONObject(json)
            return RecordingHeader(
                o.optString("build"), o.optString("device"), o.optString("cameraId"),
                o.optBoolean("realtimeTimestamps"), o.optString("startedAt"),
                RecordingFormat.knobsFrom(o.optJSONObject("knobs")?.toString() ?: "{}"),
            )
        }
    }
}

/**
 * Writes a recording on its own thread, so compressing ~300 KB frames never holds up the camera.
 * [frame] and the event calls only queue; when the queue is full (the writer has fallen 1.5 s behind)
 * frames are dropped and a GAP record says how many. Stops by itself at [maxDurationMs] or
 * [maxBytes]; [stop] finishes the queue and closes the file.
 */
class RecordingWriter(
    val file: File,
    header: RecordingHeader,
    private val maxDurationMs: Long = MAX_DURATION_MS,
    private val maxBytes: Long = MAX_BYTES,
) {
    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        @Volatile var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private val counting = Counting(BufferedOutputStream(FileOutputStream(file), 1 shl 16))
    private val out = DataOutputStream(counting)
    private val queue = ArrayBlockingQueue<Any>(QUEUE_FRAMES)
    private val deflater = Deflater(Deflater.BEST_SPEED)
    private var deflated = ByteArray(1 shl 16)
    @Volatile private var dropped = 0
    @Volatile private var firstNs = Long.MIN_VALUE
    @Volatile private var lastNs = Long.MIN_VALUE
    @Volatile var frames = 0
        private set
    @Volatile var full = false
        private set
    @Volatile private var stopping = false
    private val thread = Thread({ drain() }, "sphereslam-test-recorder")

    val bytes: Long get() = counting.count
    val durationMs: Long get() = if (firstNs == Long.MIN_VALUE) 0L else (lastNs - firstNs) / 1_000_000L

    init {
        out.write(RecordingFormat.MAGIC.toByteArray(Charsets.US_ASCII))
        out.writeUTF(header.toJson())
        thread.start()
    }

    /** Queues [f]; false when it was dropped (writer behind, recording full or stopped). */
    fun frame(f: FrameRecord): Boolean {
        if (stopping || full) return false
        if (firstNs == Long.MIN_VALUE) firstNs = f.timestampNs
        lastNs = f.timestampNs
        if ((f.timestampNs - firstNs) / 1_000_000L >= maxDurationMs || bytes >= maxBytes) {
            full = true
            return false
        }
        if (!queue.offer(f)) { dropped++; return false }
        frames++
        return true
    }

    fun event(e: RecordEvent) {
        if (stopping) return
        // Events are rare and must not be lost: wait briefly for room.
        queue.offer(e, 500, TimeUnit.MILLISECONDS)
    }

    /** Finishes writing what is queued and closes the file. */
    fun stop() {
        if (stopping) return
        stopping = true
        queue.offer(STOP, 2, TimeUnit.SECONDS)
        thread.join(10_000L)
        runCatching { out.close() }
        deflater.end()
    }

    private fun drain() {
        try {
            while (true) {
                val item = queue.take()
                if (item === STOP) break
                val gap = dropped
                if (gap > 0) {
                    dropped = 0
                    writeEvent(RecordEvent.Gap(lastNs, gap))
                }
                when (item) {
                    is FrameRecord -> writeFrame(item)
                    is RecordEvent -> writeEvent(item)
                }
            }
            out.flush()
        } catch (_: Exception) {
            // Disk full or the file went away: the recording ends here; the reader takes what is written.
        }
    }

    private fun compress(data: ByteArray): Int {
        deflater.reset()
        deflater.setInput(data)
        deflater.finish()
        if (deflated.size < data.size + 64) deflated = ByteArray(data.size + 64)
        var n = 0
        while (!deflater.finished()) n += deflater.deflate(deflated, n, deflated.size - n)
        return n
    }

    private fun writeFrame(f: FrameRecord) {
        out.writeByte(RecordingFormat.FRAME)
        out.writeLong(f.timestampNs)
        out.writeInt(f.width)
        out.writeInt(f.height)
        for (v in f.intrinsics) out.writeFloat(v)
        for (v in f.cameraFromDevice) out.writeFloat(v)
        val a = f.attitude
        out.writeBoolean(a != null)
        if (a != null) {
            for (v in a.quaternion) out.writeFloat(v)
            out.writeLong(a.timestampNs)
            out.writeInt(a.accuracy)
        }
        writeOptional(f.headingDeg); writeOptional(f.elevationDeg); writeOptional(f.rollDeg)
        out.writeLong(f.convertNs)
        val n = compress(f.luma)
        out.writeInt(n)
        out.write(deflated, 0, n)
    }

    private fun writeEvent(e: RecordEvent) {
        when (e) {
            is RecordEvent.Capture -> {
                out.writeByte(RecordingFormat.CAPTURE)
                out.writeLong(e.timestampNs)
                out.writeUTF(e.capture.checkId)
                out.writeInt(e.capture.side)
                val n = compress(e.capture.crop)
                out.writeInt(n)
                out.write(deflated, 0, n)
            }
            is RecordEvent.Restart -> { out.writeByte(RecordingFormat.RESTART); out.writeLong(e.timestampNs); out.writeUTF(e.reason) }
            is RecordEvent.Knobs -> { out.writeByte(RecordingFormat.KNOBS); out.writeLong(e.timestampNs); out.writeUTF(RecordingFormat.knobsJson(e.values)) }
            is RecordEvent.Gap -> { out.writeByte(RecordingFormat.GAP); out.writeLong(e.timestampNs); out.writeInt(e.frames) }
        }
    }

    private fun writeOptional(v: Float?) {
        out.writeBoolean(v != null)
        if (v != null) out.writeFloat(v)
    }

    companion object {
        /** About 1.5 s of frames at 30 fps. */
        const val QUEUE_FRAMES = 45
        const val MAX_DURATION_MS = 5 * 60_000L
        const val MAX_BYTES = 2_000_000_000L
        private val STOP = Any()
    }
}

/**
 * Reads a recording: [open] gives the [header]; [index] lists every frame and event (one pass that
 * skips the pixel data) and [frame] reads one frame's pixels.
 */
class RecordingReader private constructor(val file: File, val header: RecordingHeader, private val dataStart: Long) {

    /** One frame's place in the file and its timestamp. */
    class FrameIndex(val timestampNs: Long, val offset: Long)

    class Index(val frames: List<FrameIndex>, val events: List<RecordEvent>) {
        val durationMs: Long get() = if (frames.size < 2) 0L else (frames.last().timestampNs - frames.first().timestampNs) / 1_000_000L
        val captures: Int get() = events.count { it is RecordEvent.Capture }
    }

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private val inflater = Inflater()
    private var compressed = ByteArray(1 shl 16)
    private val raf = RandomAccessFile(file, "r")

    /** Indexes the file: every frame's timestamp and offset, every event in full. A record cut short is dropped. */
    fun index(): Index {
        val frames = ArrayList<FrameIndex>()
        val events = ArrayList<RecordEvent>()
        val length = file.length()
        val counting = Counting(FileInputStream(file).buffered(1 shl 16))
        DataInputStream(counting).use { input ->
            input.skipFully(dataStart)
            try {
                while (true) {
                    val start = counting.count
                    val type = input.read()
                    if (type < 0) break
                    when (type) {
                        RecordingFormat.FRAME -> {
                            val ts = input.readLong()
                            input.skipFully(8L + 16 + 36)
                            if (input.readBoolean()) input.skipFully(16L + 8 + 4)
                            repeat(3) { if (input.readBoolean()) input.skipFully(4) }
                            input.skipFully(8)
                            input.skipFully(input.readInt().toLong())
                            // skip() can run past the end of a cut file without failing.
                            if (counting.count > length) break
                            frames += FrameIndex(ts, start)
                        }
                        RecordingFormat.CAPTURE -> {
                            val ts = input.readLong()
                            val id = input.readUTF()
                            val side = input.readInt()
                            val n = input.readInt()
                            val bytes = ByteArray(n).also { input.readFully(it) }
                            events += RecordEvent.Capture(CaptureRecord(ts, id, side, inflate(bytes, n, side * side)))
                        }
                        RecordingFormat.RESTART -> events += RecordEvent.Restart(input.readLong(), input.readUTF())
                        RecordingFormat.KNOBS -> events += RecordEvent.Knobs(input.readLong(), RecordingFormat.knobsFrom(input.readUTF()))
                        RecordingFormat.GAP -> events += RecordEvent.Gap(input.readLong(), input.readInt())
                        else -> break // corrupt: stop at the last good record
                    }
                }
            } catch (_: EOFException) {
                // The app died mid-write: the cut record never made it into the index.
            }
        }
        return Index(frames, events)
    }

    /** The frame at [entry], pixels inflated. */
    fun frame(entry: FrameIndex): FrameRecord {
        raf.seek(entry.offset + 1)
        val ts = raf.readLong()
        val w = raf.readInt()
        val h = raf.readInt()
        val k = FloatArray(4) { raf.readFloat() }
        val cfd = FloatArray(9) { raf.readFloat() }
        val attitude = if (raf.readBoolean()) {
            val q = FloatArray(4) { raf.readFloat() }
            val ats = raf.readLong()
            val acc = raf.readInt()
            AttitudeSample(q, ats, acc)
        } else null
        fun opt(): Float? = if (raf.readBoolean()) raf.readFloat() else null
        val heading = opt(); val elevation = opt(); val roll = opt()
        val convert = raf.readLong()
        val n = raf.readInt()
        if (compressed.size < n) compressed = ByteArray(n)
        raf.readFully(compressed, 0, n)
        return FrameRecord(ts, w, h, k, cfd, attitude, heading, elevation, roll, convert, inflate(compressed, n, w * h))
    }

    private fun inflate(data: ByteArray, n: Int, size: Int): ByteArray {
        inflater.reset()
        inflater.setInput(data, 0, n)
        val outBytes = ByteArray(size)
        var got = 0
        while (got < size && !inflater.finished()) {
            val r = inflater.inflate(outBytes, got, size - got)
            if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            got += r
        }
        return outBytes
    }

    fun close() {
        runCatching { raf.close() }
        inflater.end()
    }

    companion object {
        /** The reader for [file], or null when it is not a recording. */
        fun open(file: File): RecordingReader? = runCatching {
            val counting = Counting(FileInputStream(file).buffered())
            val header = DataInputStream(counting).use { input ->
                val magic = ByteArray(RecordingFormat.MAGIC.length).also { input.readFully(it) }
                if (String(magic, Charsets.US_ASCII) != RecordingFormat.MAGIC) return null
                RecordingHeader.fromJson(input.readUTF())
            }
            RecordingReader(file, header, counting.count)
        }.getOrNull()

        private fun DataInputStream.skipFully(n: Long) {
            var left = n
            while (left > 0) {
                val s = skip(left)
                if (s <= 0) {
                    if (read() < 0) throw EOFException()
                    left--
                } else {
                    left -= s
                }
            }
        }
    }
}
