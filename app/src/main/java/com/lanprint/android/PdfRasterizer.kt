package com.lanprint.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

object PdfRasterizer {

    // Printer target pages are large (e.g. 10200x6600 px for Letter at this
    // printer's native 1200x600 dpi). Rendering + dithering a bitmap that
    // size directly (ARGB_8888 = 4 bytes/px) would need ~270MB just for one
    // page and will OutOfMemory on real phones. Instead we render at a
    // capped, safe intermediate resolution, then dither at that resolution
    // and expand the 1-bit rows to the printer's full target grid -- the target
    // pixel grid is still produced at full size, we just never hold the
    // whole thing in memory at once. Quality loss from this is negligible
    // for a monochrome laser printer's actual resolvable detail.
    private const val MAX_INTERMEDIATE_DIM = 2400

    /**
     * Renders [pageNumbers] (1-based) of the PDF at [pdfPath] into a single
     * file at [outPbmPath] containing one concatenated raw-PBM (P4) block
     * per page, fitted to [geometry] (rotated if [orientationLandscape]).
     */
    fun renderPagesToPbm(
        pdfPath: String,
        pageNumbers: List<Int>,
        geometry: PaperGeometry,
        orientationLandscape: Boolean,
        scalePercent: Int,
        outPbmPath: String,
    ) {
        val pfd = ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val out = RandomAccessFile(outPbmPath, "rw")
        out.setLength(0)

        val targetW = if (orientationLandscape) geometry.heightPx else geometry.widthPx
        val targetH = if (orientationLandscape) geometry.widthPx else geometry.heightPx
        val horizontalDpi = if (orientationLandscape) 600f else 1200f
        val verticalDpi = if (orientationLandscape) 1200f else 600f

        val scale = min(1.0, MAX_INTERMEDIATE_DIM.toDouble() / max(targetW, targetH))
        val workingW = max(1, (targetW * scale).toInt())
        val workingH = max(1, (targetH * scale).toInt())
        val intermediateScaleX = workingW.toFloat() / targetW
        val intermediateScaleY = workingH.toFloat() / targetH

        try {
            for (pageNum in pageNumbers) {
                val index = pageNum - 1
                if (index < 0 || index >= renderer.pageCount) continue
                val page = renderer.openPage(index)
                try {
                    val bitmap = Bitmap.createBitmap(workingW, workingH, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)

                        val rotate = orientationLandscape != (page.width > page.height)
                        val sourceW = if (rotate) page.height else page.width
                        val sourceH = if (rotate) page.width else page.height
                        val fitScale = min(
                            targetW * 72f / (horizontalDpi * sourceW),
                            targetH * 72f / (verticalDpi * sourceH),
                        )
                        val scaleFactor = fitScale * scalePercent.coerceIn(10, 200) / 100f
                        val scaleX = scaleFactor * horizontalDpi / 72f * intermediateScaleX
                        val scaleY = scaleFactor * verticalDpi / 72f * intermediateScaleY
                        val drawW = (sourceW * scaleX).toInt().coerceAtLeast(1)
                        val drawH = (sourceH * scaleY).toInt().coerceAtLeast(1)
                        if (rotate) {
                            val left = (workingW - drawW) / 2f
                            val top = (workingH - drawH) / 2f
                            val transform = Matrix().apply {
                                setValues(
                                    floatArrayOf(
                                        0f, -scaleX, page.height * scaleX + left,
                                        scaleY, 0f, top,
                                        0f, 0f, 1f,
                                    )
                                )
                            }
                            page.render(
                                bitmap,
                                Rect(0, 0, workingW, workingH),
                                transform,
                                PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                            )
                        } else {
                            val left = (workingW - drawW) / 2
                            val top = (workingH - drawH) / 2
                            val destRect = Rect(left, top, left + drawW, top + drawH)
                            page.render(bitmap, destRect, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        }
                        writeDitheredPbmPage(out, bitmap, workingW, workingH, targetW, targetH)
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    page.close()
                }
            }
        } finally {
            renderer.close()
            pfd.close()
            out.close()
        }
    }

    /**
     * Dithers the capped intermediate bitmap, expands each 1-bit row once,
     * and reuses it for the corresponding target rows. This avoids doing
     * Floyd-Steinberg error diffusion for every printer pixel while keeping
     * memory use O(width), not O(width*height).
     *
     * PBM (P4) convention: 1 bit per pixel, MSB first, each row padded to a
     * whole byte, BLACK = bit 1, WHITE = bit 0 (verified against libnetpbm's
     * own convention and against real foo2xqx output -- do not invert this).
     */
    private fun writeDitheredPbmPage(
        out: RandomAccessFile,
        src: Bitmap,
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int,
    ) {
        out.write("P4\n$targetW $targetH\n".toByteArray(Charsets.US_ASCII))

        val rowBytes = (targetW + 7) / 8
        val rowBuf = ByteArray(rowBytes)
        val sourceRowBuf = ByteArray((srcW + 7) / 8)

        var curErr = FloatArray(srcW)
        var nextErr = FloatArray(srcW)

        val srcRowPixels = IntArray(srcW)
        val targetXToSourceX = IntArray(targetW) { x ->
            (x.toLong() * srcW / targetW).toInt().coerceIn(0, srcW - 1)
        }
        var currentSourceY = -1

        for (y in 0 until targetH) {
            val sy = (y.toLong() * srcH / targetH).toInt().coerceIn(0, srcH - 1)
            while (currentSourceY < sy) {
                val sourceY = currentSourceY + 1
                src.getPixels(srcRowPixels, 0, srcW, 0, sourceY, srcW, 1)
                sourceRowBuf.fill(0)
                for (x in 0 until srcW) {
                    val pixel = srcRowPixels[x]
                    val luminance =
                        0.299f * ((pixel shr 16) and 0xFF) +
                            0.587f * ((pixel shr 8) and 0xFF) +
                            0.114f * (pixel and 0xFF)
                    val old = luminance + curErr[x]
                    val isBlack = old < 128f
                    val error = old - if (isBlack) 0f else 255f

                    if (isBlack) {
                        sourceRowBuf[x / 8] =
                            (sourceRowBuf[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                    }
                    if (x + 1 < srcW) curErr[x + 1] += error * 7f / 16f
                    if (x > 0) nextErr[x - 1] += error * 3f / 16f
                    nextErr[x] += error * 5f / 16f
                    if (x + 1 < srcW) nextErr[x + 1] += error * 1f / 16f
                }

                val tmp = curErr
                curErr = nextErr
                nextErr = tmp
                nextErr.fill(0f)

                rowBuf.fill(0)
                for (x in 0 until targetW) {
                    val sourceX = targetXToSourceX[x]
                    if ((sourceRowBuf[sourceX / 8].toInt() and (0x80 shr (sourceX % 8))) != 0) {
                        rowBuf[x / 8] =
                            (rowBuf[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                    }
                }
                currentSourceY = sourceY
            }

            out.write(rowBuf)
        }
    }
}
