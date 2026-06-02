package com.example.qrcodescanner

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

// A4 at ~150 dpi. Generous enough for crisp output; text is stored as vectors regardless.
private const val PAGE_WIDTH_PX = 1240
private const val PAGE_HEIGHT_PX = 1754

/**
 * Renders an HTML string to a PDF [outFile] using only the Android SDK — no third-party PDF
 * library — so it is free for commercial use.
 *
 * A [WebView] is attached to the window invisibly (alpha 0) so it actually lays out and renders
 * its DOM, then it is drawn into an [android.graphics.pdf.PdfDocument] canvas. Drawing through
 * `PdfDocument` records text and shapes as **vector** PDF content (not a rasterized screenshot),
 * keeping text crisp. No system print dialog is shown. The WebView is removed when done.
 *
 * [context] must be an Activity context (it needs a window token to attach the WebView).
 * All WebView access happens on the main thread, as required.
 */
suspend fun renderHtmlToPdf(context: Context, html: String, outFile: File): File =
    withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            outFile.parentFile?.mkdirs()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val webView = WebView(context)
            var finished = false

            fun finish(action: () -> Unit) {
                if (finished) return
                finished = true
                runCatching { windowManager.removeView(webView) }
                action()
            }

            webView.setBackgroundColor(Color.WHITE)
            webView.settings.javaScriptEnabled = false
            // Software layer is required to draw WebView content into PdfDocument's software canvas.
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (finished) return
                    // Let layout + embedded image decoding settle, then draw to PDF.
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            drawWebViewToPdf(view, outFile)
                            finish { if (cont.isActive) cont.resume(outFile) }
                        } catch (e: Throwable) {
                            finish { if (cont.isActive) cont.resumeWithException(e) }
                        }
                    }, 250)
                }
            }

            val params = WindowManager.LayoutParams(
                PAGE_WIDTH_PX,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSPARENT
            ).apply {
                alpha = 0f
                gravity = Gravity.TOP or Gravity.START
            }

            cont.invokeOnCancellation { finish {} }
            try {
                windowManager.addView(webView, params)
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            } catch (e: Throwable) {
                finish { if (cont.isActive) cont.resumeWithException(e) }
            }
        }
    }

/** Measures the rendered [webView] and draws it across one or more A4 PDF pages. */
private fun drawWebViewToPdf(webView: WebView, outFile: File) {
    webView.measure(
        View.MeasureSpec.makeMeasureSpec(PAGE_WIDTH_PX, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    val contentHeight = webView.measuredHeight.coerceAtLeast(PAGE_HEIGHT_PX)
    webView.layout(0, 0, PAGE_WIDTH_PX, contentHeight)

    val pageCount = ceil(contentHeight.toDouble() / PAGE_HEIGHT_PX).toInt().coerceAtLeast(1)
    val document = PdfDocument()
    try {
        for (i in 0 until pageCount) {
            val pageInfo =
                PdfDocument.PageInfo.Builder(PAGE_WIDTH_PX, PAGE_HEIGHT_PX, i + 1).create()
            val page = document.startPage(pageInfo)
            with(page.canvas) {
                drawColor(Color.WHITE)
                save()
                translate(0f, (-i * PAGE_HEIGHT_PX).toFloat())
                webView.draw(this)
                restore()
            }
            document.finishPage(page)
        }
        outFile.outputStream().use { document.writeTo(it) }
    } finally {
        document.close()
    }
}
