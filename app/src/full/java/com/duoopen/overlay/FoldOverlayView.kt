package com.duoopen.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldLine
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen overlay that draws a frozen screenshot through the fold shader.
 *
 * The shader runs per output pixel with up to 32 taps, which is too much for a
 * 5.5 MP panel at 120 Hz. So the shader view is laid out at 1/[renderScale]
 * size, rendered into its own hardware layer, and scaled back up; the frost
 * hides the upscale, and at tilt 0 the plain snapshot is drawn 1:1 instead.
 *
 * Each snapshot also gets, built off the main thread, a copy at the shader
 * view's size (a full-size capture has 4× the texels the shader needs, and
 * its scattered taps thrash the GPU cache) and two smaller pre-blurred copies
 * that the shader samples for wide frost; and a new snapshot
 * crossfades in over the previous one instead of cutting.
 */
class FoldOverlayView(
    context: Context,
    snapshot: Bitmap,
    /** Hinge geometry for this panel; null = centered from the config. */
    foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)? = null,
    private val renderScale: Float = 2f,
) : ViewGroup(context) {

    private val fold = FoldView(context, snapshot, renderScale, foldLine)
    private val flat = FlatView(context, snapshot)

    var tilt: Float
        get() = fold.tilt
        set(value) {
            fold.tilt = value
            val useFold = value >= DuoShader.FLAT_EPSILON
            fold.visibility = if (useFold) VISIBLE else INVISIBLE
            flat.visibility = if (useFold) INVISIBLE else VISIBLE
        }

    var config: DuoConfig
        get() = fold.config
        set(value) {
            fold.config = value
        }

    /**
     * Swaps the picture under the effect in place (e.g. a stale bridge → the
     * fresh capture). [crossfade] blends it in; live frames, close to the one
     * before, skip that (it doubles the shader's texture reads).
     */
    fun setSnapshot(bitmap: Bitmap, crossfade: Boolean = true) {
        fold.setSnapshot(bitmap, crossfade)
        flat.setSnapshot(bitmap)
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(flat)
        addView(fold)
        fold.pivotX = 0f
        fold.pivotY = 0f
        fold.scaleX = renderScale
        fold.scaleY = renderScale
        fold.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        flat.measure(exactly(w), exactly(h))
        fold.measure(exactly(ceil(w / renderScale).toInt()), exactly(ceil(h / renderScale).toInt()))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        flat.layout(0, 0, r - l, b - t)
        fold.layout(0, 0, fold.measuredWidth, fold.measuredHeight)
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    /** Snapshot drawn 1:1 — pixel-identical to the live screen underneath. */
    private class FlatView(context: Context, private var snapshot: Bitmap) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var shader = BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val matrix = Matrix()

        fun setSnapshot(bitmap: Bitmap) {
            snapshot = bitmap
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            matrix.setScale(w / snapshot.width.toFloat(), h / snapshot.height.toFloat())
            shader.setLocalMatrix(matrix)
            paint.shader = shader
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    /** Snapshot through the fold shader, at reduced resolution. */
    private class FoldView(
        context: Context,
        snapshot: Bitmap,
        private val renderScale: Float,
        private val foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)?,
    ) : View(context) {
        private val shader: RuntimeShader? = DuoShader.create(context)
        private val pxPerMm = DuoShader.pxPerMm(context) / renderScale
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        /** What the shader draws; while [fade] < 1 it fades in over [previous]. */
        private var current = Frame(snapshot, prefiltered[snapshot])
        private var previous: Frame? = null
        private var fade = 1f
        /** Latest snapshot handed in; an older one's pre-blur landing late is dropped. */
        private var wanted: Bitmap = snapshot
        private val mixes = Array(3) { RuntimeShader(MIX_SRC) }
        private val fader = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CROSSFADE_MS
            addUpdateListener {
                fade = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    previous = null
                    fade = 1f
                }
            })
        }

        init {
            // The first picture shows at once, sharp-only; its blurred copies
            // follow (at the low tilt an effect starts from they barely show).
            if (current.blur == null) prefilter(snapshot) { b -> if (current.bitmap === snapshot) current = Frame(snapshot, b) }
        }

        fun setSnapshot(bitmap: Bitmap, crossfade: Boolean) {
            wanted = bitmap
            val known = prefiltered[bitmap]
            when {
                known != null -> show(Frame(bitmap, known), crossfade)
                bitmap.config == Bitmap.Config.HARDWARE -> show(Frame(bitmap, null), crossfade)
                else -> prefilter(bitmap) { b -> if (wanted === bitmap) show(Frame(bitmap, b), crossfade) }
            }
        }

        private fun show(next: Frame, crossfade: Boolean) {
            fader.cancel()
            // Only frames prepared alike can blend (same shader inputs).
            previous = current.takeIf { crossfade && (it.blur == null) == (next.blur == null) }
            current = next
            fade = 0f
            if (previous != null) fader.start() else fade = 1f
            invalidate()
        }

        /** Builds [bitmap]'s blurred copies on a worker; [onReady] runs on the main thread. */
        private fun prefilter(bitmap: Bitmap, onReady: (Prefiltered) -> Unit) {
            if (bitmap.config == Bitmap.Config.HARDWARE) return
            val refWidth = if (width > 0) width else max(1, (bitmap.width / renderScale).roundToInt())
            prefilterExecutor.execute {
                val b = runCatching {
                    if (bitmap.isRecycled) null
                    else {
                        val base = if (bitmap.width > refWidth * 5 / 4) downscale(bitmap, refWidth) else null
                        val a = downscale(base ?: bitmap, max(8, refWidth / 4))
                        Prefiltered(base, a, downscale(a, max(4, refWidth / 16)))
                    }
                }.getOrNull() ?: return@execute
                mainHandler.post {
                    prefiltered[bitmap] = b
                    onReady(b)
                    invalidate()
                }
            }
        }

        var config: DuoConfig = DuoSettings.config.value

        var tilt = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return
            canvas.drawColor(Color.BLACK)
            val fold = shader
            val cur = current.inputs(w, h)
            if (fold == null) {
                paint.shader = cur.content
            } else {
                val line = foldLine?.invoke(w, h, config) ?: DuoShader.centeredFold(w, h, config.foldSplitsLong)
                DuoShader.setUniforms(fold, w, h, tilt, config, pxPerMm, line)
                val prev = previous?.takeIf { fade < 1f }?.inputs(w, h)
                fold.setInputShader("content", mix(0, prev?.content, cur.content))
                val curA = cur.blurA
                val curB = cur.blurB
                if (curA != null && curB != null) {
                    DuoShader.setPrefiltered(
                        fold,
                        mix(1, prev?.blurA, curA), cur.texelA,
                        mix(2, prev?.blurB, curB), cur.texelB,
                    )
                }
                paint.shader = fold
            }
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        /** [to] alone, or [from] blending into it at [fade]. */
        private fun mix(slot: Int, from: Shader?, to: Shader): Shader {
            if (from == null) return to
            val m = mixes[slot]
            m.setInputShader("a", from)
            m.setInputShader("b", to)
            m.setFloatUniform("t", fade)
            return m
        }
    }

    /**
     * Downscaled copies of a snapshot: [base] at the view's width (null when the
     * snapshot is already about that size), [a] ~1/4 of it, [b] ~1/16.
     */
    private class Prefiltered(val base: Bitmap?, val a: Bitmap, val b: Bitmap)

    /** A snapshot plus its blurred copies, and the shaders mapping them onto the view. */
    private class Frame(val bitmap: Bitmap, val blur: Prefiltered?) {
        class Inputs(val content: Shader, val blurA: Shader?, val texelA: Float, val blurB: Shader?, val texelB: Float)

        private var cached: Inputs? = null
        private var cachedW = 0f
        private var cachedH = 0f

        fun inputs(w: Float, h: Float): Inputs {
            cached?.let { if (cachedW == w && cachedH == h) return it }
            val a = blur?.a
            val b = blur?.b
            return Inputs(
                content = fitted(blur?.base ?: bitmap, w, h),
                blurA = a?.let { fitted(it, w, h) },
                texelA = if (a != null) w / a.width else 0f,
                blurB = b?.let { fitted(it, w, h) },
                texelB = if (b != null) w / b.width else 0f,
            ).also {
                cached = it
                cachedW = w
                cachedH = h
            }
        }

        private fun fitted(bitmap: Bitmap, w: Float, h: Float): Shader =
            BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
                // Bilinear matters most on the small copies: it's what makes them smooth.
                filterMode = BitmapShader.FILTER_MODE_LINEAR
                setLocalMatrix(Matrix().apply { setScale(w / bitmap.width, h / bitmap.height) })
            }
    }

    private companion object {
        /** New snapshots blend in over this long rather than cutting. */
        const val CROSSFADE_MS = 90L

        const val MIX_SRC = """
            uniform shader a;
            uniform shader b;
            uniform float t;
            half4 main(float2 p) { return mix(a.eval(p), b.eval(p), half(t)); }
        """

        val mainHandler = Handler(Looper.getMainLooper())
        val prefilterExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "duo-prefilter").apply { isDaemon = true } }

        /** Blurred copies by snapshot, so a cached picture reused as a bridge has them at once. Main thread only. */
        val prefiltered = WeakHashMap<Bitmap, Prefiltered>()

        /** Halves (2×2 box) down to within 2× of [targetW], then one bilinear step to it. */
        fun downscale(src: Bitmap, targetW: Int): Bitmap {
            var b = src
            while (b.width > targetW * 2) {
                val n = Bitmap.createScaledBitmap(b, b.width / 2, max(1, b.height / 2), true)
                if (b !== src) b.recycle()
                b = n
            }
            val targetH = max(1, (targetW.toFloat() * src.height / src.width).roundToInt())
            val out = Bitmap.createScaledBitmap(b, targetW, targetH, true)
            if (b !== src && b !== out) b.recycle()
            return out
        }
    }
}
