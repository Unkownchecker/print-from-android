cmake_minimum_required(VERSION 3.22.1)
project(f2xnative C)

# From the foo2zjs project (GPL-2.0-or-later, see foo2zjs/COPYING):
#   foo2xqx.c  -- XQX protocol  (HP LaserJet P1005/P1006/P1007/P1008/P1505)
#   foo2zjs.c  -- ZJ-stream protocol (HP LaserJet 1000/1005/1018/1020/1022)
#   jbig.c/jbig_ar.c -- JBIG compression, used by both of the above
#   arm2hpdl.c -- wraps a raw firmware image with HP's download header
add_library(f2xnative SHARED
    f2x_jni.c
    f2x_wrap.c
    fzj_wrap.c
    a2h_wrap.c
    foo2zjs/jbig.c
    foo2zjs/jbig_ar.c)

target_compile_options(f2xnative PRIVATE -O2 -fvisibility=hidden -Wno-unused-function -Wno-unused-variable)
target_link_options(f2xnative PRIVATE -Wl,-Bsymbolic -Wl,-z,max-page-size=16384)
