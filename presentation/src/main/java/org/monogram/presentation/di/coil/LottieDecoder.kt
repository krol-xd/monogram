package org.monogram.presentation.di.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieFeatureFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream

class LottieDecoder(
    private val source: ImageSource
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bufferedSource = source.source()
        val isGzipped = bufferedSource.peek().use { peeked ->
            peeked.request(2)
            if (peeked.buffer.size >= 2) {
                val header = peeked.readShort().toInt() and 0xffff
                header == 0x1f8b
            } else {
                false
            }
        }

        val inputStream = if (isGzipped) {
            withContext(Dispatchers.IO) {
                GZIPInputStream(bufferedSource.inputStream())
            }
        } else {
            bufferedSource.inputStream()
        }

        val result = LottieCompositionFactory.fromJsonInputStreamSync(inputStream, null)
        val composition = result.value ?: throw result.exception ?: RuntimeException("Failed to load Lottie")

        val drawable = LottieDrawable().apply {
            setComposition(composition)
            enableFeatureFlag(LottieFeatureFlag.MergePathsApi19, true)
            clipToCompositionBounds = false
            repeatCount = LottieDrawable.INFINITE
        }

        return DecodeResult(
            image = drawable.asImage(),
            isSampled = false
        )
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val isLottie = result.mimeType == "application/tgs" ||
                    result.mimeType == "application/json" ||
                    result.source.file().name.endsWith(".tgs", ignoreCase = true) ||
                    result.source.file().name.endsWith(".json", ignoreCase = true)

            if (isLottie) {
                return LottieDecoder(result.source)
            }

            return null
        }
    }
}
