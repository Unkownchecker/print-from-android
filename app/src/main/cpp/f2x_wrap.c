/*
 * f2x_wrap.c -- runs the UNMODIFIED foo2xqx.c (from the foo2zjs project) as a
 * library function instead of a command-line filter.
 *
 * foo2xqx is a stdin->stdout filter that calls exit() and keeps its options in
 * global variables. To call it repeatedly from inside an app process we:
 *   - #include the original source into this translation unit (so we can see
 *     and reset its globals),
 *   - point its stdin/stdout at files we open ourselves,
 *   - turn exit() into a longjmp back to f2x_run(),
 *   - swap getopt() for a small self-contained one (no hidden global state),
 *   - rename a few generic symbols so they can't collide with libc.
 *
 * The original source is not edited, so its output is byte-for-byte what the
 * real foo2xqx produces (verified in tests against the reference binary).
 *
 * License: GPL-2.0-or-later, same as foo2zjs (see COPYING).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <unistd.h>

static FILE *f2x_in;
static FILE *f2x_out;
static jmp_buf f2x_jmp;
static volatile int f2x_exit_code;

static char *f2x_optarg;
static int f2x_optind;
static int f2x_optpos;

static void f2x_exit(int code) __attribute__((noreturn));
static void f2x_exit(int code)
{
    f2x_exit_code = code;
    longjmp(f2x_jmp, 1);
}

/* Minimal POSIX-style getopt (supports clustered flags and "x:" arguments). */
static int f2x_getopt(int argc, char *const argv[], const char *optstring)
{
    const char *a;
    const char *p;
    int c;

    f2x_optarg = NULL;
    if (f2x_optpos == 0) {
        if (f2x_optind >= argc) return -1;
        a = argv[f2x_optind];
        if (a[0] != '-' || a[1] == 0) return -1;
        if (a[1] == '-' && a[2] == 0) { f2x_optind++; return -1; }
        f2x_optpos = 1;
    }
    a = argv[f2x_optind];
    c = (unsigned char) a[f2x_optpos++];
    p = strchr(optstring, c);
    if (!p || c == ':') {
        if (!a[f2x_optpos]) { f2x_optind++; f2x_optpos = 0; }
        return '?';
    }
    if (p[1] == ':') {
        if (a[f2x_optpos]) {
            f2x_optarg = (char *) a + f2x_optpos;
        } else if (f2x_optind + 1 < argc) {
            f2x_optarg = argv[f2x_optind + 1];
            f2x_optind++;
        } else {
            f2x_optind++; f2x_optpos = 0;
            return '?';
        }
        f2x_optind++;
        f2x_optpos = 0;
        return c;
    }
    if (!a[f2x_optpos]) { f2x_optind++; f2x_optpos = 0; }
    return c;
}

#undef stdin
#undef stdout
#define stdin  f2x_in
#define stdout f2x_out
#define exit(c) f2x_exit(c)
#define getopt f2x_getopt
#define optarg f2x_optarg
#define optind f2x_optind
#define main   foo2xqx_main
#define error  f2x_error
#define debug  f2x_debug
#define usage  f2x_usage

/* The upstream XQX and ZJ programs define many of the same global symbols.
 * Prefix this copy so both converters can live in the same shared library. */
#define Debug              f2x_Debug
#define Version            f2x_Version
#define AllIsBlack          f2x_AllIsBlack
#define AnyColor            f2x_AnyColor
#define BlackClears         f2x_BlackClears
#define Bpp                 f2x_Bpp
#define Copies              f2x_Copies
#define Duplex              f2x_Duplex
#define EconoMode           f2x_EconoMode
#define EvenPages           f2x_EvenPages
#define ExtraPad            f2x_ExtraPad
#define Filename            f2x_Filename
#define IsCUPS              f2x_IsCUPS
#define LogicalClip         f2x_LogicalClip
#define LogicalOffsetX      f2x_LogicalOffsetX
#define LogicalOffsetY      f2x_LogicalOffsetY
#define LowerRightX         f2x_LowerRightX
#define LowerRightY         f2x_LowerRightY
#define MediaCode           f2x_MediaCode
#define Mode                f2x_Mode
#define Color2Mono          f2x_Color2Mono
#define JbgOptions          f2x_JbgOptions
#define Mirror1             f2x_Mirror1
#define Mirror2             f2x_Mirror2
#define Mirror4             f2x_Mirror4
#define OutputStartPlane    f2x_OutputStartPlane
#define PageHeight          f2x_PageHeight
#define PageNum             f2x_PageNum
#define PageWidth           f2x_PageWidth
#define PaperCode           f2x_PaperCode
#define PrintDensity        f2x_PrintDensity
#define RealWidth           f2x_RealWidth
#define ResX                f2x_ResX
#define ResY                f2x_ResY
#define SaveToner           f2x_SaveToner
#define SeekIndex           f2x_SeekIndex
#define SeekRec             f2x_SeekRec
#define SourceCode          f2x_SourceCode
#define UpperLeftX          f2x_UpperLeftX
#define UpperLeftY          f2x_UpperLeftY
#define Username            f2x_Username
#define BIE_CHAIN           f2x_BIE_CHAIN
#define SEEKREC             f2x_SEEKREC

#define blank_page          f2x_blank_page
#define chunk_write         f2x_chunk_write
#define cmyk_page           f2x_cmyk_page
#define cmyk_pages          f2x_cmyk_pages
#define cmyk_planes         f2x_cmyk_planes
#define do_one              f2x_do_one
#define end_doc             f2x_end_doc
#define end_page            f2x_end_page
#define free_chain          f2x_free_chain
#define getint              f2x_getint
#define item_uint32_write   f2x_item_uint32_write
#define output_jbig         f2x_output_jbig
#define parse_xy            f2x_parse_xy
#define pbm_header          f2x_pbm_header
#define pbm_page            f2x_pbm_page
#define pbm_pages           f2x_pbm_pages
#define pksm_page           f2x_pksm_page
#define pksm_pages          f2x_pksm_pages
#define read_and_clip_image f2x_read_and_clip_image
#define rotate_bytes_180    f2x_rotate_bytes_180
#define skip_to_nl          f2x_skip_to_nl
#define start_doc           f2x_start_doc
#define start_page          f2x_start_page
#define write_page          f2x_write_page
#define write_plane         f2x_write_plane

#include "foo2zjs/foo2xqx.c"

/* Put every option/global back to its compile-time default. */
static void f2x_reset(void)
{
    Debug = 0;
    ResX = 600;
    ResY = 600;
    Bpp = 1;
    PaperCode = DMPAPER_LETTER;
    PageWidth = 1200 * 8.5;
    PageHeight = 600 * 11;
    UpperLeftX = 0;
    UpperLeftY = 0;
    LowerRightX = 0;
    LowerRightY = 0;
    Copies = 1;
    Duplex = DMDUPLEX_OFF;
    SourceCode = DMBIN_AUTO;
    MediaCode = DMMEDIA_PLAIN;
    Username = NULL;
    Filename = NULL;
    Mode = 0;
    Color2Mono = 0;
    BlackClears = 0;
    AllIsBlack = 0;
    OutputStartPlane = 1;
    ExtraPad = 16;
    LogicalOffsetX = 0;
    LogicalOffsetY = 0;
    LogicalClip = LOGICAL_CLIP_X | LOGICAL_CLIP_Y;
    SaveToner = 0;
    PageNum = 0;
    RealWidth = 0;
    EconoMode = 0;
    PrintDensity = 3;
    IsCUPS = 0;
    EvenPages = NULL;
    memset(SeekRec, 0, sizeof(SeekRec));
    SeekIndex = 0;
    DuplexPause = 0;
    AnyColor = 0;
}

/*
 * Convert the pbmraw file at in_path into an XQX stream at out_path, using
 * foo2xqx command-line style arguments (argv[0] is the program name).
 * Returns 0 on success, otherwise the exit code foo2xqx would have used
 * (or a negative number if the files couldn't be opened).
 */
int f2x_run(int argc, char **argv, const char *in_path, const char *out_path)
{
    int rc;

    f2x_in = fopen(in_path, "rb");
    if (!f2x_in) return -1;
    f2x_out = fopen(out_path, "wb");
    if (!f2x_out) { fclose(f2x_in); f2x_in = NULL; return -2; }

    f2x_reset();
    f2x_optind = 1;
    f2x_optpos = 0;
    f2x_optarg = NULL;
    f2x_exit_code = 99;

    if (setjmp(f2x_jmp) == 0) {
        foo2xqx_main(argc, argv);
        f2x_exit_code = 98; /* main() should always end via exit() */
    }
    rc = f2x_exit_code;

    fclose(f2x_in);
    f2x_in = NULL;
    fclose(f2x_out);
    f2x_out = NULL;
    return rc;
}
