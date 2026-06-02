package com.example.qrcodescanner

/**
 * Data model + HTML/CSS renderer for a rich, multi-section diagnostic report (and a small QR
 * scan report). The HTML is fed to [renderHtmlToPdf], which uses Android's print framework to
 * produce a PDF — no third-party PDF library is involved.
 */

enum class TestStatus { PASS, FAIL, WARNING, NONE }

data class Cell(val text: String, val status: TestStatus = TestStatus.NONE)

data class ReportTable(val headers: List<String>, val rows: List<List<Cell>>)

data class SensorCard(
    val label: String,
    val value: String,
    val status: TestStatus = TestStatus.NONE,
    val statusText: String = "-"
)

sealed interface ModuleBody {
    data class Tabular(val table: ReportTable) : ModuleBody
    data class Sensors(val cards: List<SensorCard>, val table: ReportTable?) : ModuleBody
}

data class InfoField(val label: String, val value: String)

data class DiagnosticModule(
    val title: String,
    val badgeText: String,
    val badgeStatus: TestStatus,
    val body: ModuleBody,
    /** Half-width modules render two-per-row (like "Data Logging" + "Transmission"). */
    val half: Boolean = false
)

data class DiagnosticReport(
    val networkName: String,
    val title: String,
    val visitType: String,
    val dateTime: String,
    val technician: String,
    val reportId: String,
    val bannerTitle: String,
    val bannerSummary: String,
    val bannerStatus: TestStatus,
    val deviceInfo: List<InfoField>,
    val modules: List<DiagnosticModule>
)

/** Dummy data mirroring the sample Field Diagnostic Report. */
fun sampleDiagnosticReport(): DiagnosticReport = DiagnosticReport(
    networkName = "WEATHER MONITORING NETWORK",
    title = "Field Diagnostic Report",
    visitType = "New Installation",
    dateTime = "28 May 2026 · 12:45",
    technician = "—",
    reportId = "RPT-52534319",
    bannerTitle = "Needs Attention — Issues detected",
    bannerSummary = "6 Pass · 1 Warning · 2 Fail",
    bannerStatus = TestStatus.WARNING,
    deviceInfo = listOf(
        InfoField("Device ID", "860710086035634"),
        InfoField("Serial No.", "RCWISDMDL000182"),
        InfoField("Firmware", "86580-ARG_017-1029"),
        InfoField("Hardware Rev", "—"),
        InfoField("Site Name", "—"),
        InfoField("GPS Coords", "26.70611, 81.01051"),
        InfoField("Elevation", "—"),
        InfoField("Modem", "—"),
        InfoField("Sensors Config", "ARG"),
    ),
    modules = listOf(
        DiagnosticModule(
            title = "Module 1 — Power Subsystem",
            badgeText = "Fail",
            badgeStatus = TestStatus.FAIL,
            body = ModuleBody.Tabular(
                ReportTable(
                    headers = listOf("Test Name", "Measured", "Expected", "Result", "Time"),
                    rows = listOf(
                        listOf(Cell("Battery voltage"), Cell("4.19 V"), Cell("3.9–4.1 V"), Cell("FAIL", TestStatus.FAIL), Cell("12:45:34")),
                        listOf(Cell("Battery charging"), Cell("Yes"), Cell("Yes"), Cell("Pass", TestStatus.PASS), Cell("12:45:34")),
                        listOf(Cell("Solar panel voltage"), Cell("6.16 V"), Cell("> 0 V"), Cell("PASS", TestStatus.PASS), Cell("12:45:34")),
                    )
                )
            )
        ),
        DiagnosticModule(
            title = "Module 2 — Sensor Readings",
            badgeText = "All Pass",
            badgeStatus = TestStatus.PASS,
            body = ModuleBody.Sensors(
                cards = listOf(
                    SensorCard("Temperature", "—"),
                    SensorCard("Humidity", "—"),
                    SensorCard("Pressure", "—"),
                    SensorCard("Wind Speed", "—"),
                    SensorCard("Wind Direction", "—"),
                    SensorCard("Solar Irradiance", "—"),
                ),
                table = ReportTable(
                    headers = listOf("Test Name", "Result", "Time"),
                    rows = listOf(
                        listOf(Cell("ARG tips count (Manual: 2, Device: 2)"), Cell("PASS", TestStatus.PASS), Cell("12:45:34")),
                    )
                )
            )
        ),
        DiagnosticModule(
            title = "Module 3 — SIM / Cellular",
            badgeText = "Fail",
            badgeStatus = TestStatus.FAIL,
            body = ModuleBody.Tabular(
                ReportTable(
                    headers = listOf("Test Name", "Measured", "Result", "Time"),
                    rows = listOf(
                        listOf(Cell("eSIM"), Cell("ICCID: 89918740407071876782 · Signal: -44 dBm"), Cell("Pass", TestStatus.PASS), Cell("12:45:34")),
                        listOf(Cell("Physical SIM"), Cell("ICCID: 8991102406396462663F · Signal: 0 dBm"), Cell("Fail", TestStatus.FAIL), Cell("12:45:34")),
                    )
                )
            )
        ),
        DiagnosticModule(
            title = "Module 4 — Data Logging",
            badgeText = "All Pass",
            badgeStatus = TestStatus.PASS,
            half = true,
            body = ModuleBody.Tabular(
                ReportTable(
                    headers = listOf("Test", "Result"),
                    rows = listOf(
                        listOf(Cell("RTC drift"), Cell("PASS · 11 sec drift", TestStatus.PASS)),
                        listOf(Cell("IMEI match"), Cell("PASS", TestStatus.PASS)),
                    )
                )
            )
        ),
        DiagnosticModule(
            title = "Module 5 — Transmission",
            badgeText = "Warning",
            badgeStatus = TestStatus.WARNING,
            half = true,
            body = ModuleBody.Tabular(
                ReportTable(
                    headers = listOf("Test", "Result"),
                    rows = listOf(
                        listOf(Cell("Overall SIM status"), Cell("WARNING", TestStatus.WARNING)),
                    )
                )
            )
        ),
    )
)

// ---------------------------------------------------------------------------------------------
// HTML rendering
// ---------------------------------------------------------------------------------------------

private fun esc(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun statusClass(status: TestStatus): String = when (status) {
    TestStatus.PASS -> "pass"
    TestStatus.FAIL -> "fail"
    TestStatus.WARNING -> "warn"
    TestStatus.NONE -> "none"
}

private val sharedCss = """
    * { margin: 0; padding: 0; box-sizing: border-box; }
    @page { size: A4; margin: 0; }
    body { font-family: 'Helvetica Neue', 'Roboto', Arial, sans-serif; color: #1f2937;
           font-size: 13px; line-height: 1.45; }
    .header { background: #1b2a5e; color: #fff; padding: 22px 32px 18px; }
    .header .net { font-size: 11px; letter-spacing: 1.5px; color: #9aa6cf; font-weight: 600; }
    .header .title { font-size: 26px; font-weight: 700; margin-top: 4px; }
    .meta { background: #21306b; color: #fff; display: flex; padding: 14px 32px; gap: 24px; }
    .meta-item { flex: 1; }
    .meta-label { font-size: 10px; letter-spacing: 0.8px; color: #9aa6cf; font-weight: 600; }
    .meta-value { font-size: 14px; font-weight: 700; margin-top: 3px; }
    .banner { display: flex; justify-content: space-between; align-items: center;
              padding: 12px 32px; background: #fcf3cf; border-left: 6px solid #e0a800; }
    .banner.fail { background: #fde8e8; border-left-color: #d32f2f; }
    .banner.pass { background: #e6f4ea; border-left-color: #1e7e34; }
    .banner-title { font-weight: 700; color: #b8860b; font-size: 14px; }
    .banner.fail .banner-title { color: #d32f2f; }
    .banner.pass .banner-title { color: #1e7e34; }
    .banner-summary { color: #6b7280; font-size: 12px; }
    .content { padding: 26px 32px 32px; }
    .section { font-size: 18px; font-weight: 700; color: #111827; margin: 24px 0 12px; }
    .content > .section:first-child { margin-top: 0; }
    .module-head { display: flex; align-items: center; gap: 12px; margin: 24px 0 12px; }
    .module-head .section { margin: 0; }
    .badge { font-size: 11px; font-weight: 700; padding: 3px 12px; border-radius: 999px; }
    .badge.pass { background: #e6f4ea; color: #1e7e34; }
    .badge.fail { background: #fde8e8; color: #d32f2f; }
    .badge.warn { background: #fcf3cf; color: #b8860b; }
    .info-grid { display: grid; grid-template-columns: repeat(3, 1fr);
                 border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
    .info-cell { padding: 13px 16px; border-right: 1px solid #eef0f2; border-bottom: 1px solid #eef0f2; }
    .info-label { font-size: 10px; letter-spacing: 0.6px; color: #8a909a; font-weight: 600; }
    .info-value { font-size: 14px; font-weight: 700; color: #1f2937; margin-top: 3px; }
    table { width: 100%; border-collapse: collapse; border: 1px solid #e5e7eb;
            border-radius: 8px; overflow: hidden; }
    th { background: #f3f4f6; text-align: left; padding: 10px 14px; font-size: 10px;
         letter-spacing: 0.6px; color: #6b7280; font-weight: 700; }
    td { padding: 11px 14px; border-top: 1px solid #eef0f2; font-size: 13px; vertical-align: top; }
    td.name { font-weight: 700; color: #1f2937; }
    td.muted { color: #9ca3af; }
    td.result { font-weight: 700; }
    td.result.pass { color: #1e7e34; }
    td.result.fail { color: #d32f2f; }
    td.result.warn { color: #c77700; }
    .sensor-grid { display: grid; grid-template-columns: repeat(3, 1fr);
                   border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 14px; }
    .sensor-cell { padding: 14px 16px; border-right: 1px solid #eef0f2; border-bottom: 1px solid #eef0f2; }
    .sensor-label { font-size: 10px; letter-spacing: 0.6px; color: #8a909a; font-weight: 600; }
    .sensor-value { font-size: 16px; font-weight: 700; color: #1f2937; margin: 4px 0 8px; }
    .sensor-pill { display: inline-block; font-size: 11px; padding: 2px 12px; border-radius: 999px;
                   background: #e6f4ea; color: #1e7e34; }
    .module-row { display: flex; gap: 20px; }
    .module-col { flex: 1; }
    .module-col .module-head { margin-top: 0; }
""".trimIndent()

/** Renders a full diagnostic report to a standalone HTML document. */
fun renderReportHtml(report: DiagnosticReport): String {
    val sb = StringBuilder()
    sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
    sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    sb.append("<style>").append(sharedCss).append("</style></head><body>")

    // Header band
    sb.append("<div class=\"header\"><div class=\"net\">").append(esc(report.networkName))
        .append("</div><div class=\"title\">").append(esc(report.title)).append("</div></div>")

    // Meta strip
    sb.append("<div class=\"meta\">")
    metaItem(sb, "VISIT TYPE", report.visitType)
    metaItem(sb, "DATE & TIME", report.dateTime)
    metaItem(sb, "TECHNICIAN", report.technician)
    metaItem(sb, "REPORT ID", report.reportId)
    sb.append("</div>")

    // Status banner
    sb.append("<div class=\"banner ").append(statusClass(report.bannerStatus)).append("\">")
        .append("<div class=\"banner-title\">").append(esc(report.bannerTitle)).append("</div>")
        .append("<div class=\"banner-summary\">").append(esc(report.bannerSummary)).append("</div></div>")

    sb.append("<div class=\"content\">")

    // Device & site info grid
    sb.append("<div class=\"section\">Device &amp; Site Information</div>")
    sb.append("<div class=\"info-grid\">")
    for (field in report.deviceInfo) {
        sb.append("<div class=\"info-cell\"><div class=\"info-label\">")
            .append(esc(field.label.uppercase())).append("</div><div class=\"info-value\">")
            .append(esc(field.value)).append("</div></div>")
    }
    sb.append("</div>")

    // Modules (grouping consecutive half-width modules two-per-row)
    var i = 0
    while (i < report.modules.size) {
        val module = report.modules[i]
        if (module.half) {
            sb.append("<div class=\"module-row\">")
            sb.append("<div class=\"module-col\">")
            renderModule(sb, module)
            sb.append("</div>")
            if (i + 1 < report.modules.size && report.modules[i + 1].half) {
                sb.append("<div class=\"module-col\">")
                renderModule(sb, report.modules[i + 1])
                sb.append("</div>")
                i++
            }
            sb.append("</div>")
        } else {
            renderModule(sb, module)
        }
        i++
    }

    sb.append("</div></body></html>")
    return sb.toString()
}

private fun metaItem(sb: StringBuilder, label: String, value: String) {
    sb.append("<div class=\"meta-item\"><div class=\"meta-label\">").append(esc(label))
        .append("</div><div class=\"meta-value\">").append(esc(value)).append("</div></div>")
}

private fun renderModule(sb: StringBuilder, module: DiagnosticModule) {
    sb.append("<div class=\"module-head\"><div class=\"section\">").append(esc(module.title))
        .append("</div><span class=\"badge ").append(statusClass(module.badgeStatus)).append("\">")
        .append(esc(module.badgeText)).append("</span></div>")
    when (val body = module.body) {
        is ModuleBody.Tabular -> renderTable(sb, body.table)
        is ModuleBody.Sensors -> {
            sb.append("<div class=\"sensor-grid\">")
            for (card in body.cards) {
                sb.append("<div class=\"sensor-cell\"><div class=\"sensor-label\">")
                    .append(esc(card.label.uppercase())).append("</div><div class=\"sensor-value\">")
                    .append(esc(card.value)).append("</div><span class=\"sensor-pill\">")
                    .append(esc(card.statusText)).append("</span></div>")
            }
            sb.append("</div>")
            body.table?.let { renderTable(sb, it) }
        }
    }
}

private fun renderTable(sb: StringBuilder, table: ReportTable) {
    val resultIdx = table.headers.indexOfFirst { it.equals("Result", ignoreCase = true) }
    val mutedIdx = table.headers.mapIndexedNotNull { idx, h ->
        if (h.equals("Expected", true) || h.equals("Time", true)) idx else null
    }.toSet()

    sb.append("<table><thead><tr>")
    for (h in table.headers) sb.append("<th>").append(esc(h.uppercase())).append("</th>")
    sb.append("</tr></thead><tbody>")
    for (row in table.rows) {
        sb.append("<tr>")
        row.forEachIndexed { idx, cell ->
            val cls = when {
                idx == resultIdx -> "result ${statusClass(cell.status)}"
                idx == 0 -> "name"
                idx in mutedIdx -> "muted"
                else -> ""
            }
            if (cls.isEmpty()) sb.append("<td>") else sb.append("<td class=\"").append(cls).append("\">")
            sb.append(esc(cell.text)).append("</td>")
        }
        sb.append("</tr>")
    }
    sb.append("</tbody></table>")
}

/** Renders a small report for a single scanned QR code (table of fields + the QR image). */
fun renderQrScanHtml(content: String, scannedAt: String, qrDataUri: String): String {
    val sb = StringBuilder()
    sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
    sb.append("<style>").append(sharedCss)
    sb.append(".qr-wrap { text-align: center; margin-top: 26px; }")
    sb.append(".qr-wrap img { width: 240px; height: 240px; }")
    sb.append(".qr-caption { color: #6b7280; font-size: 12px; margin-top: 8px; }")
    sb.append("</style></head><body>")
    sb.append("<div class=\"header\"><div class=\"net\">QR CODE SCANNER</div>")
        .append("<div class=\"title\">Scan Result</div></div>")
    sb.append("<div class=\"content\">")
    sb.append("<table><tbody>")
    qrRow(sb, "Content", content)
    qrRow(sb, "Format", "QR_CODE")
    qrRow(sb, "Scanned at", scannedAt)
    sb.append("</tbody></table>")
    sb.append("<div class=\"qr-wrap\"><img src=\"").append(qrDataUri).append("\"/>")
        .append("<div class=\"qr-caption\">Regenerated from the scanned content</div></div>")
    sb.append("</div></body></html>")
    return sb.toString()
}

private fun qrRow(sb: StringBuilder, label: String, value: String) {
    sb.append("<tr><td class=\"name\" style=\"width:28%\">").append(esc(label))
        .append("</td><td>").append(esc(value)).append("</td></tr>")
}
