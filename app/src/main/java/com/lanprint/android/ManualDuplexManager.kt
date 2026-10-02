package com.lanprint.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manual duplex: print all odd pages, wait for whoever's at the printer to
 * flip the stack and reload it, then print the even pages (reversed order
 * by default). Mirrors the desktop app's server/manual-duplex.js so the
 * phone app's "Sides" option and flip-prompt UI work identically against
 * either backend.
 */
class ManualDuplexManager(private val context: Context) {

    data class Job(
        val pdfPath: String,
        val printerModel: PrinterModel,
        val options: XqxPrinter.PrintOptions,
        val totalPages: Int,
        val selectedPages: List<Int>,
        val expiryRunnable: Runnable,
    )

    companion object {
        private val JOB_EXPIRY_MS = 20 * 60 * 1000L // 20 minutes, matching the desktop app
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val handler = Handler(Looper.getMainLooper())

    data class StartResult(
        val jobId: String,
        val totalPages: Int,
        val oddCount: Int,
        val evenCount: Int,
        val oddPages: List<Int>,
    )

    fun evenPages(pages: List<Int>, reverse: Boolean): List<Int> {
        val list = pages.filter { it % 2 == 0 }
        return if (reverse) list.reversed() else list
    }

    /** Copies [sourcePdfPath] into app storage (so it survives past this call) and returns job info. */
    fun start(
        sourcePdfPath: String,
        printerModel: PrinterModel,
        options: XqxPrinter.PrintOptions,
        pageNumbers: List<Int>? = null,
    ): StartResult? {
        val documentPageCount = XqxPrinter.pdfPageCount(sourcePdfPath)
        val selectedPages = pageNumbers ?: (1..documentPageCount).toList()
        if (selectedPages.size < 2 || selectedPages.any { it !in 1..documentPageCount }) return null

        val jobId = UUID.randomUUID().toString()
        val workDir = File(context.cacheDir, "manual-duplex").apply { mkdirs() }
        val storedPdf = File(workDir, "$jobId.pdf")
        File(sourcePdfPath).copyTo(storedPdf, overwrite = true)

        val expiryRunnable = Runnable { cancel(jobId) }
        handler.postDelayed(expiryRunnable, JOB_EXPIRY_MS)

        jobs[jobId] = Job(storedPdf.absolutePath, printerModel, options, selectedPages.size, selectedPages, expiryRunnable)

        val odd = selectedPages.filter { it % 2 == 1 }
        val even = selectedPages.filter { it % 2 == 0 }
        return StartResult(jobId, selectedPages.size, odd.size, even.size, odd)
    }

    fun get(jobId: String): Job? = jobs[jobId]

    fun cancel(jobId: String) {
        val job = jobs.remove(jobId) ?: return
        handler.removeCallbacks(job.expiryRunnable)
        File(job.pdfPath).delete()
    }

    fun finish(jobId: String) {
        // Same as cancel, but named separately for call-site clarity after a successful phase 2.
        cancel(jobId)
    }
}
