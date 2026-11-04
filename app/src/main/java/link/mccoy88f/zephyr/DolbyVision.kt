package link.mccoy88f.zephyr

import android.media.MediaCodecInfo.CodecProfileLevel
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * Dolby Vision on a TV that doesn't decode it (or not the profile a film has).
 *
 * Profile 7, the Blu-ray's (4K remuxes): its base layer is plain HDR10 HEVC, the enhancement in NAL units an HEVC
 * decoder skips. Media3 only falls back to HEVC for profiles 4, 8 and 9, so such a file found no decoder: read
 * through [BaseLayer] it's HEVC, and plays in HDR10.
 *
 * Profile 5 (many WEB-DLs): no base layer an HEVC decoder can show (its colours come out green and purple), so a
 * stream that says Dolby Vision alone ([only]) goes after the others there (see PlayerActivity.streamOrder).
 */
@OptIn(UnstableApi::class)
internal object DolbyVision {
    @Volatile private var known: Set<Int>? = null

    /** The Dolby Vision profiles the device's decoders take (MediaCodec's flags); none without a DV decoder. */
    private fun profiles(): Set<Int> = known ?: runCatching {
        MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)
            .flatMap { info -> info.profileLevels.map { it.profile } }.toSet()
    }.getOrDefault(emptySet()).also { known = it }

    /** The device decodes Dolby Vision (some profile of it). */
    fun decodes(): Boolean = profiles().isNotEmpty()

    /** The device decodes Dolby Vision profile 7 (dual layer). */
    fun decodesProfile7(): Boolean = CodecProfileLevel.DolbyVisionProfileDvheDtb in profiles()

    /** The profile in a Dolby Vision codecs string ("dvhe.07.06": 7), or null. */
    fun profile(codecs: String?): Int? =
        Regex("""^dv(?:he|h1|av|a1|av1)\.(\d{1,2})""").find(codecs.orEmpty().lowercase())?.groupValues?.get(1)?.toIntOrNull()

    /** [f] as plain HEVC when it's Dolby Vision profile 7: its base layer, for an HEVC decoder. */
    fun baseLayer(f: Format): Format =
        if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && profile(f.codecs) == 7)
            f.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build()
        else f

    /**
     * A stream whose name says Dolby Vision ("DV", "DoVi", "Dolby Vision") and no HDR10 or HDR: profile 5, most
     * likely, which a TV without Dolby Vision can't show. "DV HDR10" (profile 7 or 8) has a base layer it can.
     */
    fun only(title: String): Boolean {
        val dv = Regex("""(?i)(?<![\p{L}0-9])(dv|dovi|dolby[\s._-]*vision)(?![\p{L}0-9])""").containsMatchIn(title)
        val hdr = Regex("""(?i)(?<![\p{L}0-9])hdr(10\+?|10plus)?(?![\p{L}])""").containsMatchIn(title)
        return dv && !hdr
    }
}

/**
 * [inner]'s extractors, each one's Dolby Vision profile 7 track handed on as HEVC (see DolbyVision.baseLayer), on a
 * device that doesn't decode that profile; else [inner]'s as they are.
 */
@OptIn(UnstableApi::class)
internal class DvBaseLayer(private val inner: ExtractorsFactory = DefaultExtractorsFactory()) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(inner.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(inner.createExtractors(uri, responseHeaders))

    // The subtitles as the player sets them up for the files' own (see DefaultMediaSourceFactory).
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        inner.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    private fun wrap(all: Array<Extractor>): Array<Extractor> =
        if (DolbyVision.decodesProfile7()) all else Array(all.size) { i -> BaseLayer(all[i]) }
}

/** [inner], with a Dolby Vision profile 7 track's format told as HEVC. */
@OptIn(UnstableApi::class)
internal class BaseLayer(private val inner: Extractor) : Extractor by inner {
    override fun init(output: ExtractorOutput) = inner.init(Output(output))

    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation

    private class Output(private val out: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = Track(out.track(id, type))
        override fun endTracks() = out.endTracks()
        override fun seekMap(seekMap: SeekMap) = out.seekMap(seekMap)
    }

    private class Track(private val track: TrackOutput) : TrackOutput by track {
        override fun format(format: Format) = track.format(DolbyVision.baseLayer(format))

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            track.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = track.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
            track.sampleMetadata(timeUs, flags, size, offset, cryptoData)

        override fun durationUs(durationUs: Long) = track.durationUs(durationUs)
    }
}
