package com.lanprint.android

/** HP's USB vendor ID (0x03F0), shared by every model in this family. */
const val HP_VENDOR_ID = 0x03F0

/** Which native converter a model needs -- these are genuinely different
 *  wire protocols, not a flag on one shared format. */
enum class PrinterProtocol { XQX, ZJS }

data class PrinterModel(
    val displayName: String,
    /** Substring matched (case-insensitively) against the MDL: field of the
     *  printer's IEEE-1284 device ID string, e.g. "HP LaserJet P1007". */
    val deviceIdSubstring: String,
    /** Exact USB Product ID, sourced directly from foo2zjs's own hplj1000
     *  hotplug script (not guessed) -- the authoritative way to identify
     *  which exact model is attached. */
    val productId: Int,
    /** Which model's firmware file this one actually needs. P1007 and
     *  P1008 are the same hardware as P1005/P1006 under a different USB
     *  PID/branding and use THEIR firmware files, not their own name --
     *  getting this backwards means the printer never becomes usable.
     *  Sourced from the same hotplug script's FWMODEL aliasing. */
    val firmwareModelName: String,
    val protocol: PrinterProtocol,
    /** Extra foo2zjs-specific flags this exact model needs (e.g. "-z1 -P
     *  -L0" for the 1018/1020/1022 family) -- only meaningful for ZJS. */
    val zjsExtraArgs: List<String> = emptyList(),
)

// XQX-family models: confirmed from foo2zjs's own PPD files (each lists
// "DRV:Dfoo2xqx,..."). Product IDs and firmware aliasing are copied
// directly from foo2zjs's hplj1000 hotplug script -- do not renumber these
// from guesswork.
//
// ZJS-family models (LaserJet 1000/1005/1018/1020/1022): a genuinely
// different wire protocol (Zenographics ZJ-stream, via foo2zjs.c), not
// just a different flag on the same one. Only the plain "1020" is wired up
// below (that's what was asked for) -- 1000/1005/1018/1022 use the same
// driver and are very likely addable the same way if needed later.
val SUPPORTED_MODELS = listOf(
    PrinterModel("HP LaserJet P1005", "P1005", 0x3d17, "P1005", PrinterProtocol.XQX),
    PrinterModel("HP LaserJet P1006", "P1006", 0x3e17, "P1006", PrinterProtocol.XQX),
    PrinterModel("HP LaserJet P1007", "P1007", 0x4817, "P1005", PrinterProtocol.XQX), // alias!
    PrinterModel("HP LaserJet P1008", "P1008", 0x4917, "P1006", PrinterProtocol.XQX), // alias!
    PrinterModel("HP LaserJet P1505", "P1505", 0x3f17, "P1505", PrinterProtocol.XQX),
    PrinterModel("HP LaserJet P1505n", "P1505", 0x4017, "P1505n", PrinterProtocol.XQX),
    // PID 0x2b17 is documented for plain "1020"; HP's "1020 Plus" revision
    // may or may not share this exact PID -- not independently confirmed.
    // The IEEE-1284 device-ID-string fallback match (matchModelByDeviceId)
    // will still identify it correctly even if this PID doesn't match.
    PrinterModel(
        "HP LaserJet 1020", "1020", 0x2b17, "1020", PrinterProtocol.ZJS,
        zjsExtraArgs = listOf("-z1", "-P", "-L0"),
    ),
)

/** Primary match: exact (vendor, product) ID pair -- the reliable signal. */
fun matchModelByIds(vendorId: Int, productId: Int): PrinterModel? {
    if (vendorId != HP_VENDOR_ID) return null
    return SUPPORTED_MODELS.firstOrNull { it.productId == productId }
}

/** Secondary/fallback: match against the IEEE-1284 device ID string, for
 *  display confirmation or in case a variant PID isn't in our table yet. */
fun matchModelByDeviceId(deviceIdString: String): PrinterModel? {
    val mdl = Regex("MDL:([^;]+)", RegexOption.IGNORE_CASE)
        .find(deviceIdString)?.groupValues?.get(1) ?: deviceIdString
    return SUPPORTED_MODELS.firstOrNull { mdl.contains(it.deviceIdSubstring, ignoreCase = true) }
}

/**
 * Exact per-paper-size pixel geometry at 1200x600 dpi (1200 horizontal, 600
 * vertical -- this printer family's own default resolution, per its PPDs'
 * *DefaultResolution field). These numbers are copied directly from
 * foo2zjs's foo2xqx-wrapper.in (the XDIM/YDIM/set_clipping table for
 * RES=1200x600) -- do not "simplify" or recompute them; the wrapper's
 * values encode the printer's actual unprintable-margin geometry, which
 * isn't a plain formula.
 */
data class PaperGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val clipUlx: Int, val clipUly: Int,
    val clipLrx: Int, val clipLry: Int,
    val paperCode: Int, // DMPAPER_* from xqx.h, passed to foo2xqx's -p
)

val PAPER_SIZES: Map<String, PaperGeometry> = mapOf(
    "a4" to PaperGeometry(9920, 7016, 176, 84, 176, 84, 9),
    "letter" to PaperGeometry(10200, 6600, 177, 84, 177, 84, 1),
    "legal" to PaperGeometry(10200, 8400, 177, 96, 177, 96, 5),
    "a5" to PaperGeometry(6992, 4960, 192, 96, 192, 96, 11),
)

const val DEFAULT_PAPER_SIZE = "a4"
