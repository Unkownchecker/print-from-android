/*
 * a2h_wrap.c -- runs the UNMODIFIED arm2hpdl.c (from foo2zjs) as a library
 * function. arm2hpdl adds HP's download header/trailer to a raw firmware
 * image, producing the exact byte stream that needs to be sent to the
 * printer. See f2x_wrap.c for the general approach (same technique, much
 * simpler program here: only one global, and it opens its input file
 * itself rather than reading stdin).
 *
 * License: GPL-2.0-or-later, same as foo2zjs (see foo2zjs/COPYING).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>

static FILE *a2h_out;
static jmp_buf a2h_jmp;
static volatile int a2h_exit_code;

static int a2h_optind;
static int a2h_optpos;
static char *a2h_optarg;

static void a2h_exit(int code) __attribute__((noreturn));
static void a2h_exit(int code)
{
    a2h_exit_code = code;
    longjmp(a2h_jmp, 1);
}

static int a2h_getopt(int argc, char *const argv[], const char *optstring)
{
    const char *a;
    const char *p;
    int c;

    a2h_optarg = NULL;
    if (a2h_optpos == 0) {
        if (a2h_optind >= argc) return -1;
        a = argv[a2h_optind];
        if (a[0] != '-' || a[1] == 0) return -1;
        if (a[1] == '-' && a[2] == 0) { a2h_optind++; return -1; }
        a2h_optpos = 1;
    }
    a = argv[a2h_optind];
    c = (unsigned char) a[a2h_optpos++];
    p = strchr(optstring, c);
    if (!p || c == ':') {
        if (!a[a2h_optpos]) { a2h_optind++; a2h_optpos = 0; }
        return '?';
    }
    if (p[1] == ':') {
        if (a[a2h_optpos]) {
            a2h_optarg = (char *) a + a2h_optpos;
        } else if (a2h_optind + 1 < argc) {
            a2h_optarg = argv[a2h_optind + 1];
            a2h_optind++;
        } else {
            a2h_optind++; a2h_optpos = 0;
            return '?';
        }
        a2h_optind++;
        a2h_optpos = 0;
        return c;
    }
    if (!a[a2h_optpos]) { a2h_optind++; a2h_optpos = 0; }
    return c;
}

#undef stdout
#define stdout a2h_out
#define exit(c) a2h_exit(c)
#define getopt a2h_getopt
#define optarg a2h_optarg
#define optind a2h_optind
#define main arm2hpdl_main
#define Debug a2h_Debug
#define debug a2h_debug
#define error a2h_error
#define usage a2h_usage
/* arm2hpdl.c calls bare printf() for its PJL header/footer -- printf()
 * always targets the process's real stdout regardless of our `stdout`
 * macro (that macro only affects explicit fprintf(stdout,...)/fwrite(...,
 * stdout) calls), so it must be redirected separately. */
#define printf(...) fprintf(a2h_out, __VA_ARGS__)

#include "foo2zjs/arm2hpdl.c"

/*
 * Converts the firmware image at in_path into the HP download format at
 * out_path. Returns 0 on success, otherwise arm2hpdl's own exit code (or a
 * negative number if the files couldn't be opened).
 */
int a2h_run(const char *in_path, const char *out_path)
{
    a2h_out = fopen(out_path, "wb");
    if (!a2h_out) return -2;

    Debug = 0;
    a2h_optind = 1;
    a2h_optpos = 0;
    a2h_optarg = NULL;
    a2h_exit_code = 99;

    /* argv[0] is the (unused) program name, argv[1] is the actual file --
     * matching real CLI shape, since the wrapped main() does
     * "argc -= optind; argv += optind;" internally before using argv[0]
     * as the filename. Passing just the filename with argc=1 would leave
     * it looking for a *second* argument and fail with a usage error. */
    char *argv[3];
    argv[0] = "arm2hpdl";
    argv[1] = (char *) in_path;
    argv[2] = NULL;

    if (setjmp(a2h_jmp) == 0) {
        arm2hpdl_main(2, argv);
        a2h_exit_code = 98; /* should always exit() */
    }
    int rc = a2h_exit_code;

    fclose(a2h_out);
    a2h_out = NULL;
    return rc;
}
