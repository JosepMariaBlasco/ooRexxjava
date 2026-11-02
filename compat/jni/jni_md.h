/* jni_md.h for wasm32 (the mini-JNI, compat/orx_jni.c): the machine-dependent part
   of the JDK's jni.h, which the build takes from the JDK it runs with.

   In the module a jobject, jclass or jmethodID is a pointer, 32 bits: it holds a
   handle of the launcher's object table (host/.../Jni.java), never an address. */

#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT
#define JNICALL

typedef int jint;
typedef long long jlong;   /* long is 32 bits in wasm32 */
typedef signed char jbyte;

#endif
