package link.mccoy88f.zephyr

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader

/**
 * A Blu-ray's MPEG-TS (.m2ts, as many films' remuxes are): packets of 192 bytes, a time of 4 bytes before each TS
 * packet of 188, which Media3 can't read. Read through this the times are left out, and the player sees plain MPEG-TS:
 * its byte n·188 + k is the file's n·192 + 4 + k, so seeking and the length work as for any TS file.
 */
@OptIn(UnstableApi::class)
internal class M2ts(private val upstream: DataSource) : DataSource {
    private var at = 0 // where in its 192-byte packet the file is read
    private val time = ByteArray(TIME)

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = M2ts(upstream.createDataSource())
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val from = file(dataSpec.position)
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
            else file(dataSpec.position + dataSpec.length - 1) + 1 - from
        at = (from % PACKET).toInt()
        val got = upstream.open(dataSpec.buildUpon().setPosition(from).setLength(length).build())
        return if (got == C.LENGTH_UNSET.toLong()) got else ts(from + got) - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (at < TIME) {
            val r = upstream.read(time, 0, TIME - at)
            if (r == C.RESULT_END_OF_INPUT) return r
            at += r
        }
        val r = upstream.read(buffer, offset, minOf(length, PACKET - at))
        if (r > 0) at = (at + r) % PACKET
        return r
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    companion object {
        private const val PACKET = 192
        private const val TIME = 4
        private const val TS = PACKET - TIME

        /** The file's byte for the player's [ts]. */
        fun file(ts: Long) = ts / TS * PACKET + TIME + ts % TS

        /** The player's bytes in the file's first [file] (the times left out). */
        fun ts(file: Long) = file / PACKET * TS + maxOf(0L, file % PACKET - TIME)

        /**
         * How far the clock (PCR) is looked for, from the start and from the end, for the length and seeking: a
         * Blu-ray's comes every 2,600 packets or so, past the 600 Media3 looks through (then: no length, no seeking).
         */
        const val SEARCH = 8000 * TS

        /** What reads a Blu-ray's MPEG-TS: as Media3 does, with its DTS sound too (see Hdmv) and its clock looked for further. */
        fun extractors() = ExtractorsFactory {
            arrayOf<Extractor>(TsExtractor(TsExtractor.MODE_SINGLE_PMT, 0, DefaultSubtitleParserFactory(), TimestampAdjuster(0), Hdmv(), SEARCH))
        }
    }
}

/**
 * A Blu-ray's sound types as Media3 knows them: its DTS (0x82) is left out unless asked for, its DTS-HD (0x85 High
 * Resolution, 0x86 Master Audio, as most remuxes' Italian track is) is taken for a broadcast's ad markers (SCTE-35's
 * 0x86), its LPCM (0x80) for DigiCipher's MPEG-2 video, and its Dolby TrueHD (0x83) isn't read at all. Here DTS and
 * DTS-HD play as DTS-HD (decoded by FFmpeg, or passed through to a receiver), LPCM as PCM (see LpcmReader), and
 * TrueHD as the AC-3 the disc carries inside it for players without TrueHD (see Ac3Core).
 */
@OptIn(UnstableApi::class)
internal class Hdmv(
    private val inner: TsPayloadReader.Factory = DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS),
) : TsPayloadReader.Factory by inner {
    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? = when (streamType) {
        LPCM -> PesReader(LpcmReader(esInfo.language, esInfo.roleFlags))
        TRUEHD -> PesReader(Ac3Core(Ac3Reader(esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T)))
        DTS_HD_HRA, DTS_HD_MA -> inner.createPayloadReader(TsExtractor.TS_STREAM_TYPE_DTS_HD, esInfo)
        else -> inner.createPayloadReader(streamType, esInfo)
    }

    companion object {
        const val LPCM = 0x80
        const val TRUEHD = 0x83
        const val DTS_HD_HRA = 0x85
        const val DTS_HD_MA = 0x86
    }
}

/**
 * A Blu-ray's TrueHD track: its packets of AC-3 (the disc's own copy of the sound, for players without TrueHD) read
 * by [inner], an AC-3 reader; its TrueHD packets left out. A packet is AC-3 when it's whole AC-3 frames: their sync
 * word first, and as long as the frames its header tells.
 */
@OptIn(UnstableApi::class)
internal class Ac3Core(private val inner: ElementaryStreamReader) : ElementaryStreamReader {
    private var timeUs = C.TIME_UNSET
    private var flags = 0
    private var packet = ByteArray(4096)
    private var size = 0

    override fun seek() {
        size = 0
        inner.seek()
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) =
        inner.createTracks(extractorOutput, idGenerator)

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        timeUs = pesTimeUs
        this.flags = flags
        size = 0
    }

    override fun consume(data: ParsableByteArray) {
        val n = data.bytesLeft()
        if (size + n > packet.size) packet = packet.copyOf(maxOf(packet.size * 2, size + n))
        data.readBytes(packet, size, n)
        size += n
    }

    override fun packetFinished() {
        if (isAc3(packet, size)) {
            inner.packetStarted(timeUs, flags)
            inner.consume(ParsableByteArray(packet.copyOf(size)))
            inner.packetFinished()
        }
        size = 0
    }

    override fun endOfInputReached() = inner.endOfInputReached()

    companion object {
        /** The first [size] bytes of [b] are whole AC-3 frames: the sync word, and a length the frames' size divides. */
        fun isAc3(b: ByteArray, size: Int): Boolean {
            if (size < 6 || (b[0].toInt() and 0xFF) != 0x0B || (b[1].toInt() and 0xFF) != 0x77) return false
            val frame = Ac3Util.parseAc3SyncframeSize(b.copyOf(6))
            return frame > 0 && size % frame == 0
        }
    }
}

/**
 * A Blu-ray's LPCM track (stream type 0x80): each packet a header of 4 bytes (the channels, the sample rate, the
 * bits) then big-endian samples, handed on as 16-bit PCM in Android's channel order (see Lpcm).
 */
@OptIn(UnstableApi::class)
internal class LpcmReader(private val language: String?, private val roleFlags: Int) : ElementaryStreamReader {
    private lateinit var output: TrackOutput
    private lateinit var formatId: String
    private var format: Format? = null
    private var timeUs = C.TIME_UNSET
    private var packet = ByteArray(16 * 1024)
    private var size = 0

    override fun seek() {
        size = 0
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        timeUs = pesTimeUs
        size = 0
    }

    override fun consume(data: ParsableByteArray) {
        val n = data.bytesLeft()
        if (size + n > packet.size) packet = packet.copyOf(maxOf(packet.size * 2, size + n))
        data.readBytes(packet, size, n)
        size += n
    }

    override fun packetFinished() {
        val header = Lpcm.header(packet, size)
        if (header != null) {
            if (format?.channelCount != header.layout.channels || format?.sampleRate != header.rate) {
                val f = Format.Builder().setId(formatId).setContainerMimeType(MimeTypes.VIDEO_MP2T).setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setChannelCount(header.layout.channels).setSampleRate(header.rate).setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .setLanguage(language).setRoleFlags(roleFlags).build()
                format = f
                output.format(f)
            }
            val pcm = Lpcm.toPcm16(packet, Lpcm.HEADER, size - Lpcm.HEADER, header)
            if (pcm.isNotEmpty() && timeUs != C.TIME_UNSET) {
                output.sampleData(ParsableByteArray(pcm), pcm.size)
                output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, pcm.size, 0, null)
            }
        }
        size = 0
    }
}

/** Blu-ray LPCM's packets, read (see LpcmReader). Plain functions, for the tests. */
internal object Lpcm {
    const val HEADER = 4

    /**
     * How a packet's channels are laid: [channels] heard, [coded] in each frame (odd counts carry a padding channel),
     * and [order], for each of Android's channels in turn (front left, right, centre, LFE, back, side), the packet's.
     */
    class Layout(val channels: Int, val coded: Int, val order: IntArray)

    class Header(val layout: Layout, val rate: Int, val bytesPerSample: Int)

    /** The layout for a header's channel assignment: the disc's order is L R C, then the surrounds, LFE last. */
    fun layout(assignment: Int): Layout? = when (assignment) {
        1 -> Layout(1, 2, intArrayOf(0)) // mono
        3 -> Layout(2, 2, intArrayOf(0, 1)) // stereo
        4 -> Layout(3, 4, intArrayOf(0, 1, 2)) // L R C
        5 -> Layout(3, 4, intArrayOf(0, 1, 2)) // L R S
        6 -> Layout(4, 4, intArrayOf(0, 1, 2, 3)) // L R C S
        7 -> Layout(4, 4, intArrayOf(0, 1, 2, 3)) // L R LS RS
        8 -> Layout(5, 6, intArrayOf(0, 1, 2, 3, 4)) // L R C LS RS
        9 -> Layout(6, 6, intArrayOf(0, 1, 2, 5, 3, 4)) // L R C LS RS LFE: Android's L R C LFE LS RS
        10 -> Layout(7, 8, intArrayOf(0, 1, 2, 5, 6, 3, 4)) // L R C LS RS LB RB: L R C LB RB LS RS
        11 -> Layout(8, 8, intArrayOf(0, 1, 2, 7, 5, 6, 3, 4)) // L R C LS RS LB RB LFE: L R C LFE LB RB LS RS
        else -> null
    }

    /** The header at the start of a packet of [size] bytes in [b], or null when it isn't one this can read. */
    fun header(b: ByteArray, size: Int): Header? {
        if (size < HEADER) return null
        val layout = layout((b[2].toInt() ushr 4) and 0x0F) ?: return null
        val rate = when (b[2].toInt() and 0x0F) { 1 -> 48_000; 4 -> 96_000; 5 -> 192_000; else -> return null }
        val bytes = when ((b[3].toInt() ushr 6) and 0x03) { 1 -> 2; 2, 3 -> 3; else -> return null } // 16, 20 or 24 bits
        return Header(layout, rate, bytes)
    }

    /**
     * The samples in [b] from [from], [length] bytes, as 16-bit little-endian PCM in Android's order: each sample's two
     * highest bytes (a 20- or 24-bit one loses its lowest), the padding channel left out.
     */
    fun toPcm16(b: ByteArray, from: Int, length: Int, h: Header): ByteArray {
        val frame = h.layout.coded * h.bytesPerSample
        if (length <= 0 || frame <= 0) return ByteArray(0)
        val frames = length / frame
        val out = ByteArray(frames * h.layout.channels * 2)
        var o = 0
        for (f in 0 until frames) {
            val base = from + f * frame
            for (ch in h.layout.order) {
                val at = base + ch * h.bytesPerSample
                out[o++] = b[at + 1]
                out[o++] = b[at]
            }
        }
        return out
    }
}
