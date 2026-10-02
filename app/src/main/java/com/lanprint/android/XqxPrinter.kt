package com.lanprint.android

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

object XqxPrinter {

    data class PrintOptions(
        val paperSize: String = DEFAULT_PAPER_SIZE,
        val orientationLandscape: Boolean = false,
        val copies: Int = 1,
        val scalePercent: Int = 100,
    )

    fun pdfPageCount(pdfPath: String): Int {
        val pfd = ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(pfd).use { it.pageCount }
        } finally {
            pfd.close()
        }
    }

    /**
     * Renders [pageNumbers] (1-based) of the PDF at [pdfPath], converts to
     * XQX, and sends it to [printer]. Returns null on success, or an error
     * message on failure.
     */
    fun printPages(
        context: Context,
        usbManager: UsbPrinterManager,
        printer: UsbPrinterManager.ConnectedPrinter,
        pdfPath: String,
        pageNumbers: List<Int>,
        options: PrintOptions,
    ): String? {
        val geometry = PAPER_SIZES[options.paperSize.lowercase()] ?: PAPER_SIZES[DEFAULT_PAPER_SIZE]!!

        val workDir = File(context.cacheDir, "xqx-work").apply { mkdirs() }
        val pbmFile = File(workDir, "job-${System.currentTimeMillis()}.pbm")
        val outFile = File(workDir, "job-${System.currentTimeMillis()}.out")

        try {
            PdfRasterizer.renderPagesToPbm(
                pdfPath,
                pageNumbers,
                geometry,
                options.orientationLandscape,
                options.scalePercent,
                pbmFile.absolutePath
            )

            // Shared across both protocols -- resolution, geometry, media,
            // copies, tray, and density mean the same thing in both.
            val baseArgs = mutableListOf(
                "-r1200x600",
                "-g${geometry.widthPx}x${geometry.heightPx}",
                "-p${geometry.paperCode}",
                "-m1", // standard plain paper
                "-n${options.copies.coerceAtLeast(1)}",
                "-d1", // no hardware duplexer over USB on any model here -- always single-sided per pass; two-sided is handled at the app level (print odd pages, then even pages)
                "-s7", // auto tray
                "-u${geometry.clipUlx}x${geometry.clipUly}",
                "-l${geometry.clipLrx}x${geometry.clipLry}",
                "-T3",
            )

            val model = printer.model
            val rc: Int
            when (model.protocol) {
                PrinterProtocol.XQX -> {
                    baseArgs += "-L3"
                    rc = Foo2xqx.nativeConvert(pbmFile.absolutePath, outFile.absolutePath, baseArgs.toTypedArray())
                }
                PrinterProtocol.ZJS -> {
                    baseArgs += model.zjsExtraArgs // e.g. "-z1 -P -L0" for the 1018/1020/1022 family
                    rc = Foo2xqx.nativeConvertZjs(pbmFile.absolutePath, outFile.absolutePath, baseArgs.toTypedArray())
                }
            }
            if (rc != 0) return "Raster conversion failed (exit code $rc)."

            val outBytes = outFile.readBytes()
            if (outBytes.isEmpty()) return "Raster conversion produced no data."

            val ok = usbManager.sendBulkData(printer, outBytes)
            if (!ok) return "USB transfer to the printer failed."

            return null
        } catch (e: Exception) {
            return e.message ?: "Unknown print error"
        } finally {
            pbmFile.delete()
            outFile.delete()
        }
    }
}
