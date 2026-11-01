package link.mccoy88f.zephyr

import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Test

class PairAlignTest {
    @Test fun firstSampleBecomesZeroAndTheRestKeepTheirGaps() {
        val times = ArrayList<Long>()
        val fake = object : Extractor {
            lateinit var out: ExtractorOutput
            override fun sniff(input: ExtractorInput) = true
            override fun init(output: ExtractorOutput) { out = output }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
            fun emit() {
                val t = out.track(1, C.TRACK_TYPE_AUDIO)
                t.sampleMetadata(2_000_000, 0, 0, 0, null)
                t.sampleMetadata(2_100_000, 0, 0, 0, null)
            }
        }
        StartAtZero(fake).init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = object : TrackOutput {
                override fun durationUs(durationUs: Long) {}
                override fun format(format: androidx.media3.common.Format) {}
                override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = 0
                override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) {}
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    times.add(timeUs)
                }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        fake.emit()
        assertEquals(listOf(0L, 100_000L), times)
    }
}
