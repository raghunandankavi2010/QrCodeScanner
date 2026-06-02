package com.example.qrcodescanner

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Produces PDFs by rendering an HTML/CSS template through Android's print framework
 * ([renderHtmlToPdf]). No third-party PDF library is used, so output is free for commercial use.
 *
 * Files are written into [Context.getCacheDir]/shared so the app's FileProvider can share them.
 */

private fun sharedFile(context: Context, prefix: String): File {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return File(dir, "$prefix-$stamp.pdf")
}

/** Builds a PDF for a single scanned QR [content]: a field table plus a regenerated QR image. */
suspend fun generateScanPdf(context: Context, content: String): File {
    val scannedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    val qrDataUri = generateQrBitmap(content).toPngDataUri()
    val html = renderQrScanHtml(content, scannedAt, qrDataUri)
    return renderHtmlToPdf(context, html, sharedFile(context, "qr-scan"))
}

/** Builds the rich, multi-section sample diagnostic report PDF. */
suspend fun generateDiagnosticReportPdf(context: Context): File {
    val html = renderReportHtml(sampleDiagnosticReport())
    return renderHtmlToPdf(context, html, sharedFile(context, "diagnostic-report"))
}
