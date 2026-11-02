/* BSF4ooRexx's native library (BSF4ooRexx.cc, layer 1 of Java from Rexx) linked into
   the module, unchanged.

   It is compiled with dlopen, dlsym and dlerror renamed to the functions below
   (-Ddlopen=orx_bsf_dlopen ...): it uses them only to load the JVM's library
   (BsfLoadJava: /opt/BSF4ooRexx850/libjvm.so, $JAVA_HOME/lib/server/libjvm.so,
   libjvm.so) and find JNI_CreateJavaVM and JNI_GetDefaultJavaVMInitArgs in it. Here
   the JVM is the one that runs the module, and those functions are the mini-JNI's
   (compat/orx_jni.c).

   And the table of the libraries linked into the module (oorexxwasm's SysLibrary,
   orx_wasm_static_libraries): BSF4ooRexx, under both of the names BSF.CLS loads it
   by, then whatever a build adds (orx_wasi_extra_libraries: build-test.sh's test
   libraries). */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

/* ---- the JVM's library ---------------------------------------------------------------- */

static const char JVM_HANDLE = 0;

void *orx_bsf_dlopen(const char *file, int flags)
{
    (void)flags;
    return file != NULL && strstr(file, "jvm") != NULL ? (void *)&JVM_HANDLE : NULL;
}

void *orx_bsf_dlsym(void *handle, const char *name)
{
    if (handle != &JVM_HANDLE) return NULL;
    if (strcmp(name, "JNI_CreateJavaVM") == 0) return (void *)JNI_CreateJavaVM;
    if (strcmp(name, "JNI_GetDefaultJavaVMInitArgs") == 0) return (void *)JNI_GetDefaultJavaVMInitArgs;
    if (strcmp(name, "JNI_GetCreatedJavaVMs") == 0) return (void *)JNI_GetCreatedJavaVMs;
    return NULL;
}

char *orx_bsf_dlerror(void)
{
    return (char *)"not in the module";
}

/* ---- the libraries linked into the module ------------------------------------------------ */

typedef struct { const char *name; void *address; } OrxWasmStaticSymbol;
typedef struct { const char *name; const OrxWasmStaticSymbol *symbols; } OrxWasmStaticLibrary;

void *RexxGetPackage(void);   /* BSF4ooRexx.cc's OOREXX_GET_PACKAGE(bsf) */
const OrxWasmStaticLibrary *orx_wasi_extra_libraries(void) __attribute__((weak));

static const OrxWasmStaticSymbol bsf_symbols[] = {
    { "RexxGetPackage", (void *)RexxGetPackage },
    { 0, 0 }
};

static OrxWasmStaticLibrary *libraries;
static pthread_once_t once = PTHREAD_ONCE_INIT;

static void merge(void)
{
    static const OrxWasmStaticLibrary builtin[] = {
        { "BSF4ooRexx850", bsf_symbols },
        { "BSF4ooRexx", bsf_symbols },
    };
    size_t nb = sizeof builtin / sizeof builtin[0], ne = 0;
    const OrxWasmStaticLibrary *extra = orx_wasi_extra_libraries ? orx_wasi_extra_libraries() : NULL;
    if (extra != NULL) while (extra[ne].name != NULL) ne++;
    libraries = calloc(nb + ne + 1, sizeof *libraries);
    memcpy(libraries, builtin, sizeof builtin);
    if (ne > 0) memcpy(libraries + nb, extra, ne * sizeof *libraries);
}

const OrxWasmStaticLibrary *orx_wasm_static_libraries(void)
{
    pthread_once(&once, merge);
    return libraries;
}
