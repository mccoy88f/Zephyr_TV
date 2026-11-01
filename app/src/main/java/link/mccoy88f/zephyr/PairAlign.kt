package link.mccoy88f.zephyr

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * A video file and its paired audio file (YouTube adaptive): each file's times start at its first sample, so the
 * player does not wait for the one whose container clock starts later.
 */
@OptIn(UnstableApi::class)
internal class PairAlign(private val inner: ExtractorsFactory = DefaultExtractorsFactory()) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(inner.createExtractors())
    override fun createExtractors(uri: Uri, headers: Map<String, List<String>>): Array<Extractor> =
        wrap(inner.createExtractors(uri, headers))

    private fun wrap(all: Array<Extractor>): Array<Extractor> = Array(all.size) { i -> StartAtZero(all[i]) }
}

/** [inner]'s samples, timed from the first: 0, then the gaps they already had. */
@OptIn(UnstableApi::class)
internal class StartAtZero(private val inner: Extractor) : Extractor by inner {
    private var origin = C.TIME_UNSET

    override fun init(output: ExtractorOutput) = inner.init(Output(output))

    override fun seek(position: Long, timeUs: Long) {
        inner.seek(position, if (origin == C.TIME_UNSET) timeUs else timeUs + origin)
    }

    override fun getUnderlyingImplementation(): Extractor = this

    private inner class Output(private val out: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = Track(out.track(id, type))
        override fun endTracks() = out.endTracks()
        override fun seekMap(seekMap: SeekMap) = out.seekMap(seekMap)
    }

    private inner class Track(private val track: TrackOutput) : TrackOutput by track {
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (origin == C.TIME_UNSET) origin = timeUs
            track.sampleMetadata(timeUs - origin, flags, size, offset, cryptoData)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            track.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = track.sampleData(data, length, sampleDataPart)

        override fun durationUs(durationUs: Long) = track.durationUs(durationUs)
    }
}
