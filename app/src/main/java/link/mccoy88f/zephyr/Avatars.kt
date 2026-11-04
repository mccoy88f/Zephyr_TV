package link.mccoy88f.zephyr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.widget.ImageView
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The users' avatars, drawn by DiceBear (dicebear.com): its free API, no key, as PNG (Android draws no SVG on its own).
 * An avatar is a style and a seed ("lorelei/k3J9xQ2mWb"): the seed is random, so DiceBear never gets a user's name.
 * Kept on disk with the add-ons' logos, read once into memory.
 */
internal object Avatars {
    /** DiceBear's styles offered: free to use, CC0 or free for personal and commercial use, as their licences say. */
    val STYLES = listOf("lorelei", "notionists", "thumbs", "bottts", "pixel-art", "avataaars")

    /** A style's name, as DiceBear writes it. */
    fun styleName(style: String) = when (style) {
        "lorelei" -> "Lorelei"
        "notionists" -> "Notionists"
        "thumbs" -> "Thumbs"
        "bottts" -> "Bottts"
        "pixel-art" -> "Pixel Art"
        "avataaars" -> "Avataaars"
        else -> style
    }

    private val SEED = Regex("[A-Za-z0-9]{1,24}")

    /** [avatar]'s style and seed, or null when it isn't one (none, or a backup's that says anything). */
    fun parse(avatar: String?): Pair<String, String>? {
        val style = avatar?.substringBefore('/', "") ?: return null
        val seed = avatar.substringAfter('/', "")
        return if (style in STYLES && SEED.matches(seed)) style to seed else null
    }

    /** A new random seed: letters and digits, nothing of the user's. */
    fun newSeed(): String {
        val chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..10).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** The avatar's PNG: round, on one of a few soft colours (picked by the seed), large enough for the users' menu. */
    fun url(avatar: String): String? {
        val (style, seed) = parse(avatar) ?: return null
        return "https://api.dicebear.com/9.x/$style/png?seed=$seed&size=192&radius=50&backgroundColor=b6e3f4,c0aede,d1d4f9,ffd5dc,ffdfbf"
    }

    private val loaded = ConcurrentHashMap<String, Bitmap>()

    /** [avatar]'s picture when it's in memory already, else null (see load). */
    fun cached(avatar: String?): Bitmap? = avatar?.let { loaded[it] }

    /** [avatar]'s picture: from memory, the disk, or DiceBear the first time. Null when it can't be had. Off the UI thread. */
    fun load(avatar: String): Bitmap? {
        loaded[avatar]?.let { return it }
        val url = url(avatar) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(Images.file(url, Images.Kind.LOGO).path) }.getOrNull() ?: return null
        loaded[avatar] = bitmap
        return bitmap
    }
}

/**
 * An avatar, round, in its own colours: a tint (the top bar's, its icons' on focus) would make it a blot, so it takes
 * none.
 */
internal class AvatarDrawable(private val bitmap: Bitmap) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val scale = size / minOf(bitmap.width, bitmap.height)
        paint.shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(b.exactCenterX() - bitmap.width * scale / 2, b.exactCenterY() - bitmap.height * scale / 2)
        })
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), size / 2, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {} // its own colours, always
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * [avatar] shown in [view]: at once when it's in memory, else once it's loaded (if [view] still wants it then: [still]),
 * and [shown] then. [none] is called when there's no avatar, while it loads, and when it can't be had.
 */
internal fun MainActivity.avatarInto(view: ImageView, avatar: String?, still: () -> Boolean = { true }, none: () -> Unit = {},
                                     shown: () -> Unit = {}) {
    if (Avatars.parse(avatar) == null) return none()
    Avatars.cached(avatar)?.let {
        view.setImageDrawable(AvatarDrawable(it))
        return shown()
    }
    none() // meanwhile
    posterIo.execute {
        val bitmap = Avatars.load(avatar!!)
        ui.post {
            if (!still()) return@post
            if (bitmap == null) return@post none()
            view.setImageDrawable(AvatarDrawable(bitmap))
            shown()
        }
    }
}
