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
 * A Blu-ray's sound types as Media3 knows them: its DTS (0x82) is left out unless asked for, and its DTS-HD (0x85
 * High Resolution, 0x86 Master Audio, as most remuxes' Italian track is) is taken for a broadcast's ad markers
 * (SCTE-35's 0x86): no sound at all. Read as DTS-HD, they play: decoded (FFmpeg), or passed through to a receiver.
 * Dolby TrueHD (0x83) and LPCM (0x80) Media3 can't read in MPEG-TS: another track (AC-3) plays, if there is one.
 */
@OptIn(UnstableApi::class)
internal class Hdmv(
    private val inner: TsPayloadReader.Factory = DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS),
) : TsPayloadReader.Factory by inner {
    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        inner.createPayloadReader(if (streamType == DTS_HD_HRA || streamType == DTS_HD_MA) TsExtractor.TS_STREAM_TYPE_DTS_HD else streamType, esInfo)

    companion object {
        const val DTS_HD_HRA = 0x85
        const val DTS_HD_MA = 0x86
    }
}
