package link.mccoy88f.zephyr

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M2tsTest {
    // The player's bytes are the file's with each 192-byte packet's first 4 (its time) left out.
    @Test fun mapsThePlayersBytesToTheFiles() {
        assertEquals(4L, M2ts.file(0))
        assertEquals(191L, M2ts.file(187))
        assertEquals(196L, M2ts.file(188))
        assertEquals(1000L * 192 + 4 + 10, M2ts.file(1000L * 188 + 10))
    }

    @Test fun countsThePlayersBytesInTheFiles() {
        assertEquals(0L, M2ts.ts(0))
        assertEquals(0L, M2ts.ts(4))
        assertEquals(188L, M2ts.ts(192))
        assertEquals(188L, M2ts.ts(196))
        assertEquals(189L, M2ts.ts(197))
        assertEquals(3L * 188, M2ts.ts(3L * 192)) // a whole file of 3 packets
        for (b in listOf(0L, 1L, 187L, 188L, 5000L)) assertEquals(b + 1, M2ts.ts(M2ts.file(b) + 1)) // up to and including a byte: one more than it
    }

    // A Blu-ray's DTS and DTS-HD (as La vita è bella's remux has its Italian sound): read as sound, not as ad markers.
    @Test fun readsABlurayDtsSound() {
        fun info(type: Int) = TsPayloadReader.EsInfo(type, "ita", TsPayloadReader.EsInfo.AUDIO_TYPE_UNDEFINED, null, ByteArray(0))
        for (type in listOf(0x82, Hdmv.DTS_HD_HRA, Hdmv.DTS_HD_MA)) assertTrue(Hdmv().createPayloadReader(type, info(type)) is PesReader)
        assertTrue(Hdmv().createPayloadReader(0x81, info(0x81)) is PesReader) // AC-3, as before
        // What Media3 does by itself: 0x82 left out, 0x86 taken for SCTE-35 (a section, not sound).
        assertNull(DefaultTsPayloadReaderFactory().createPayloadReader(0x82, info(0x82)))
        assertTrue(DefaultTsPayloadReaderFactory().createPayloadReader(0x86, info(0x86)) !is PesReader)
    }
}
