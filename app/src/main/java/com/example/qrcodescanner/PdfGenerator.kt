package com.example.qrcodescanner

import android.content.Context
import android.graphics.Bitmap
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a PDF for a single scanned QR [content] and returns the file.
 *
 * The PDF contains a title, a two-column table (Field / Value) with the scanned text,
 * format and timestamp, and a freshly generated QR image of the content.
 *
 * The file is written into [Context.getCacheDir]/shared so it can be served to other apps
 * via the app's FileProvider. Runs on [Dispatchers.IO].
 */
suspend fun generateScanPdf(context: Context, content: String): File = withContext(Dispatchers.IO) {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val now = Date()
    val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
    val displayStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(now)
    val file = File(sharedDir, "qr-scan-$fileStamp.pdf")

    PdfWriter(file).use { writer ->
        val pdf = PdfDocument(writer)
        Document(pdf).use { document ->
            document.add(
                Paragraph("QR Scan Result")
                    .setFontSize(20f)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER)
            )

            val table = Table(UnitValue.createPercentArray(floatArrayOf(25f, 75f)))
                .useAllAvailableWidth()
            table.addHeaderCell(headerCell("Field"))
            table.addHeaderCell(headerCell("Value"))
            table.addCell(labelCell("Content"))
            table.addCell(Cell().add(Paragraph(content)))
            table.addCell(labelCell("Format"))
            table.addCell(Cell().add(Paragraph("QR_CODE")))
            table.addCell(labelCell("Scanned at"))
            table.addCell(Cell().add(Paragraph(displayStamp)))
            document.add(table)

            document.add(
                Paragraph("QR Code")
                    .setFontSize(14f)
                    .setBold()
                    .setMarginTop(20f)
            )

            val qrImage = Image(ImageDataFactory.create(bitmapToPng(generateQrBitmap(content))))
                .setWidth(200f)
                .setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER)
            document.add(qrImage)
        }
    }

    file
}

private fun headerCell(text: String): Cell =
    Cell().add(Paragraph(text).setBold())
        .setBackgroundColor(ColorConstants.LIGHT_GRAY)

private fun labelCell(text: String): Cell =
    Cell().add(Paragraph(text).setBold())

private fun bitmapToPng(bitmap: Bitmap): ByteArray =
    ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }
