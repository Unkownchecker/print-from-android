/* JNI bridge: Kotlin -> f2x_run() (the wrapped foo2xqx). GPL-2.0-or-later. */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

int f2x_run(int argc, char **argv, const char *in_path, const char *out_path);
int fzj_run(int argc, char **argv, const char *in_path, const char *out_path);
int a2h_run(const char *in_path, const char *out_path);

typedef int (*run_fn)(int argc, char **argv, const char *in_path, const char *out_path);

/* Shared arg-marshalling for both foo2xqx and foo2zjs, which take the same
 * shape of arguments (program name + string args + in/out file paths) --
 * factored out once rather than duplicated per entry point. */
static jint marshal_and_run(JNIEnv *env, jstring jin, jstring jout, jobjectArray jargs,
                             const char *progname, run_fn fn)
{
    const char *in = (*env)->GetStringUTFChars(env, jin, NULL);
    const char *out = (*env)->GetStringUTFChars(env, jout, NULL);
    jsize n = jargs ? (*env)->GetArrayLength(env, jargs) : 0;

    char **argv = (char **) calloc((size_t) n + 2, sizeof(char *));
    jstring *refs = (jstring *) calloc((size_t) n + 1, sizeof(jstring));
    if (!argv || !refs || !in || !out) {
        free(argv); free(refs);
        if (in) (*env)->ReleaseStringUTFChars(env, jin, in);
        if (out) (*env)->ReleaseStringUTFChars(env, jout, out);
        return -100;
    }

    argv[0] = (char *) progname;
    for (jsize i = 0; i < n; i++) {
        refs[i] = (jstring) (*env)->GetObjectArrayElement(env, jargs, i);
        const char *s = (*env)->GetStringUTFChars(env, refs[i], NULL);
        argv[i + 1] = strdup(s ? s : "");
        if (s) (*env)->ReleaseStringUTFChars(env, refs[i], s);
        (*env)->DeleteLocalRef(env, refs[i]);
    }
    argv[n + 1] = NULL;

    int rc = fn((int) n + 1, argv, in, out);

    for (jsize i = 0; i < n; i++) free(argv[i + 1]);
    free(argv);
    free(refs);
    (*env)->ReleaseStringUTFChars(env, jin, in);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    return rc;
}

/*
 * Foo2xqx.nativeConvert(pbmPath, outPath, args) -> exit code (0 = success)
 * args are foo2xqx command-line options WITHOUT the program name.
 */
JNIEXPORT jint JNICALL
Java_com_lanprint_android_Foo2xqx_nativeConvert(JNIEnv *env, jclass clazz,
                                                jstring jin, jstring jout,
                                                jobjectArray jargs)
{
    (void) clazz;
    return marshal_and_run(env, jin, jout, jargs, "foo2xqx", f2x_run);
}

/*
 * Foo2xqx.nativeConvertZjs(pbmPath, outPath, args) -> exit code (0 = success)
 * Same idea as nativeConvert, but for the ZJ-stream protocol (LaserJet
 * 1000/1005/1018/1020/1022 family) instead of XQX.
 */
JNIEXPORT jint JNICALL
Java_com_lanprint_android_Foo2xqx_nativeConvertZjs(JNIEnv *env, jclass clazz,
                                                   jstring jin, jstring jout,
                                                   jobjectArray jargs)
{
    (void) clazz;
    return marshal_and_run(env, jin, jout, jargs, "foo2zjs", fzj_run);
}

/*
 * Foo2xqx.nativeConvertFirmware(imgPath, dlPath) -> exit code (0 = success)
 * Wraps a raw firmware image (as distributed) with HP's download
 * header/trailer, producing the exact bytes to send to the printer.
 */
JNIEXPORT jint JNICALL
Java_com_lanprint_android_Foo2xqx_nativeConvertFirmware(JNIEnv *env, jclass clazz,
                                                         jstring jin, jstring jout)
{
    (void) clazz;
    const char *in = (*env)->GetStringUTFChars(env, jin, NULL);
    const char *out = (*env)->GetStringUTFChars(env, jout, NULL);
    if (!in || !out) {
        if (in) (*env)->ReleaseStringUTFChars(env, jin, in);
        if (out) (*env)->ReleaseStringUTFChars(env, jout, out);
        return -100;
    }
    int rc = a2h_run(in, out);
    (*env)->ReleaseStringUTFChars(env, jin, in);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    return rc;
}
