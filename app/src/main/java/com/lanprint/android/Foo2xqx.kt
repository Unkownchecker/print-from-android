package com.lanprint.android

/**
 * Thin JNI bridge to the native foo2xqx converter (see
 * app/src/main/cpp/foo2zjs, from the foo2zjs project, GPL-2.0-or-later).
 * Converts a raw PBM (P4) raster file into the printer's XQX wire format.
 */
object Foo2xqx {
    init {
        System.loadLibrary("f2xnative")
    }

    /**
     * @param pbmPath  path to a raw PBM (P4) file, one or more concatenated
     *                 pages, black=1/white=0, MSB-first, rows byte-padded.
     * @param outPath  where to write the resulting XQX byte stream.
     * @param args     foo2xqx command-line arguments, WITHOUT the program
     *                 name (e.g. "-r1200x600", "-g10200x6600", "-p1", ...).
     * @return 0 on success; any other value is foo2xqx's own exit code
     *         (or a negative internal error code if a file couldn't be opened).
     */
    external fun nativeConvert(pbmPath: String, outPath: String, args: Array<String>): Int

    /**
     * Same idea as [nativeConvert], but for the ZJ-stream protocol (HP
     * LaserJet 1000/1005/1018/1020/1022 family) via a port of foo2zjs's
     * own foo2zjs.c, instead of the XQX protocol via foo2xqx.c.
     */
    external fun nativeConvertZjs(pbmPath: String, outPath: String, args: Array<String>): Int

    /**
     * Wraps a raw printer firmware image (as distributed by foo2zjs) with
     * HP's download header/trailer, producing the exact bytes to send to
     * the printer. Ports foo2zjs's arm2hpdl.c unmodified -- see
     * app/src/main/cpp/a2h_wrap.c.
     */
    external fun nativeConvertFirmware(imgPath: String, dlPath: String): Int
}
