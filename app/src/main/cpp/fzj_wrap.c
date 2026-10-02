/*
 * fzj_wrap.c -- runs the UNMODIFIED foo2zjs.c (the foo2zjs project's main/
 * original driver, producing Zenographics ZJ-stream format) as a library
 * function, for the HP LaserJet 1000/1005/1018/1020/1022 family. This is a
 * DIFFERENT wire protocol from foo2xqx/XQX (see f2x_wrap.c) -- structurally
 * very similar source, same wrapping technique, genuinely different bytes
 * on the wire.
 *
 * License: GPL-2.0-or-later, same as foo2zjs (see foo2zjs/COPYING).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>

static FILE *fzj_in;
static FILE *fzj_out;
static jmp_buf fzj_jmp;
static volatile int fzj_exit_code;

static char *fzj_optarg;
static int fzj_optind;
static int fzj_optpos;

static void fzj_exit(int code) __attribute__((noreturn));
static void fzj_exit(int code)
{
    fzj_exit_code = code;
    longjmp(fzj_jmp, 1);
}

static int fzj_getopt(int argc, char *const argv[], const char *optstring)
{
    const char *a;
    const char *p;
    int c;

    fzj_optarg = NULL;
    if (fzj_optpos == 0) {
        if (fzj_optind >= argc) return -1;
        a = argv[fzj_optind];
        if (a[0] != '-' || a[1] == 0) return -1;
        if (a[1] == '-' && a[2] == 0) { fzj_optind++; return -1; }
        fzj_optpos = 1;
    }
    a = argv[fzj_optind];
    c = (unsigned char) a[fzj_optpos++];
    p = strchr(optstring, c);
    if (!p || c == ':') {
        if (!a[fzj_optpos]) { fzj_optind++; fzj_optpos = 0; }
        return '?';
    }
    if (p[1] == ':') {
        if (a[fzj_optpos]) {
            fzj_optarg = (char *) a + fzj_optpos;
        } else if (fzj_optind + 1 < argc) {
            fzj_optarg = argv[fzj_optind + 1];
            fzj_optind++;
        } else {
            fzj_optind++; fzj_optpos = 0;
            return '?';
        }
        fzj_optind++;
        fzj_optpos = 0;
        return c;
    }
    if (!a[fzj_optpos]) { fzj_optind++; fzj_optpos = 0; }
    return c;
}

#undef stdin
#undef stdout
#define stdin  fzj_in
#define stdout fzj_out
#define exit(c) fzj_exit(c)
#define getopt fzj_getopt
#define optarg fzj_optarg
#define optind fzj_optind
#define main   foo2zjs_main
#define error  fzj_error
#define debug  fzj_debug
#define usage  fzj_usage

/* The upstream XQX and ZJ programs define many of the same global symbols.
 * Prefix this copy so both converters can live in the same shared library. */
#define Debug              fzj_Debug
#define Version            fzj_Version
#define AllIsBlack          fzj_AllIsBlack
#define AnyColor            fzj_AnyColor
#define BlackClears         fzj_BlackClears
#define Bpp                 fzj_Bpp
#define Copies              fzj_Copies
#define Duplex              fzj_Duplex
#define EconoMode           fzj_EconoMode
#define EvenPages           fzj_EvenPages
#define ExtraPad            fzj_ExtraPad
#define Filename            fzj_Filename
#define IsCUPS              fzj_IsCUPS
#define LogicalClip         fzj_LogicalClip
#define LogicalOffsetX      fzj_LogicalOffsetX
#define LogicalOffsetY      fzj_LogicalOffsetY
#define LowerRightX         fzj_LowerRightX
#define LowerRightY         fzj_LowerRightY
#define MediaCode           fzj_MediaCode
#define Mode                fzj_Mode
#define Color2Mono          fzj_Color2Mono
#define JbgOptions          fzj_JbgOptions
#define Mirror1             fzj_Mirror1
#define Mirror2             fzj_Mirror2
#define Mirror4             fzj_Mirror4
#define OutputStartPlane    fzj_OutputStartPlane
#define PageHeight          fzj_PageHeight
#define PageNum             fzj_PageNum
#define PageWidth           fzj_PageWidth
#define PaperCode           fzj_PaperCode
#define PrintDensity        fzj_PrintDensity
#define RealWidth           fzj_RealWidth
#define ResX                fzj_ResX
#define ResY                fzj_ResY
#define SaveToner           fzj_SaveToner
#define SeekIndex           fzj_SeekIndex
#define SeekRec             fzj_SeekRec
#define SourceCode          fzj_SourceCode
#define UpperLeftX          fzj_UpperLeftX
#define UpperLeftY          fzj_UpperLeftY
#define Username            fzj_Username
#define BIE_CHAIN           fzj_BIE_CHAIN
#define SEEKREC             fzj_SEEKREC

#define blank_page          fzj_blank_page
#define chunk_write         fzj_chunk_write
#define cmyk_page           fzj_cmyk_page
#define cmyk_pages          fzj_cmyk_pages
#define cmyk_planes         fzj_cmyk_planes
#define do_one              fzj_do_one
#define end_doc             fzj_end_doc
#define end_page            fzj_end_page
#define free_chain          fzj_free_chain
#define getint              fzj_getint
#define item_uint32_write   fzj_item_uint32_write
#define output_jbig         fzj_output_jbig
#define parse_xy            fzj_parse_xy
#define pbm_header          fzj_pbm_header
#define pbm_page            fzj_pbm_page
#define pbm_pages           fzj_pbm_pages
#define pksm_page           fzj_pksm_page
#define pksm_pages          fzj_pksm_pages
#define read_and_clip_image fzj_read_and_clip_image
#define rotate_bytes_180    fzj_rotate_bytes_180
#define skip_to_nl          fzj_skip_to_nl
#define start_doc           fzj_start_doc
#define start_page          fzj_start_page
#define write_page          fzj_write_page
#define write_plane         fzj_write_plane

#include "foo2zjs/foo2zjs.c"

static void fzj_reset(void)
{
    Debug = 0;
    ResX = 1200;
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
    MediaCode = DMMEDIA_STANDARD;
    Username = NULL;
    Filename = NULL;
    Mode = 0;
    Model = 0;
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
    Dots[0] = Dots[1] = Dots[2] = Dots[3] = 0;
    TotalDots = 0;
    IsCUPS = 0;
    EvenPages = NULL;
    memset(SeekRec, 0, sizeof(SeekRec));
    SeekIndex = 0;
    SeekMedia = 0;
}

/*
 * Converts the pbmraw file at in_path into a ZJ-stream file at out_path,
 * using foo2zjs command-line style arguments (argv[0] is the program
 * name). Returns 0 on success, otherwise the exit code foo2zjs would have
 * used (or a negative number if the files couldn't be opened).
 */
int fzj_run(int argc, char **argv, const char *in_path, const char *out_path)
{
    int rc;

    fzj_in = fopen(in_path, "rb");
    if (!fzj_in) return -1;
    fzj_out = fopen(out_path, "wb");
    if (!fzj_out) { fclose(fzj_in); fzj_in = NULL; return -2; }

    fzj_reset();
    fzj_optind = 1;
    fzj_optpos = 0;
    fzj_optarg = NULL;
    fzj_exit_code = 99;

    if (setjmp(fzj_jmp) == 0) {
        foo2zjs_main(argc, argv);
        fzj_exit_code = 98;
    }
    rc = fzj_exit_code;

    fclose(fzj_in);
    fzj_in = NULL;
    fclose(fzj_out);
    fzj_out = NULL;
    return rc;
}
