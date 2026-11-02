/* The mini-JNI: a real JNIEnv and JavaVM inside the module, for C code written against
   JNI (BSF4ooRexx.cc, layer 1), over the launcher's object table (import module "jni",
   host/.../Jni.java).

   A jobject (jclass, jstring, arrays...) is a handle of the launcher's table, cast to
   a pointer; NULL is null. jmethodID and jfieldID are indexes into the launcher's
   tables of methods and fields. The JDK's jni.h is used as it is, with compat/jni's
   jni_md.h for wasm32.

   Every wasm thread is a Java thread (thread-spawn starts one, or hands the pthread to
   the Java thread that asked for it), so one JNIEnv serves them all: the launcher
   knows the calling thread, and keeps each thread's local references and pending
   exception. GetEnv says JNI_EDETACHED until AttachCurrentThread, whose frame of
   local references DetachCurrentThread frees, as in JNI; a thread that uses the
   JNIEnv without attaching works, its local references live until it deletes them.

   The Call...Method functions hand the launcher their arguments as they come: a
   jvalue array, or the va_list itself (in wasm32, a pointer to the promoted arguments,
   each aligned to its size), which the launcher reads following the method's
   descriptor. Strings and primitive arrays are copied: Get...Chars and
   Get...ArrayElements always return a copy (isCopy is JNI_TRUE), which Release...
   writes back (unless JNI_ABORT) and frees (unless JNI_COMMIT).

   Not provided (their entries are NULL): DefineClass, AllocObject, RegisterNatives,
   UnregisterNatives, FromReflected..., ToReflected..., the direct buffers, GetModule,
   IsVirtualThread. MonitorEnter/MonitorExit are a lock per object, which does not
   exclude Java code synchronized on the same object. */

#include <jni.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define JNI_IMPORT(name) __attribute__((import_module("jni"), import_name(#name)))

JNI_IMPORT(find_class)          int jx_find_class(const char *name);
JNI_IMPORT(get_object_class)    int jx_get_object_class(int obj);
JNI_IMPORT(get_superclass)      int jx_get_superclass(int cls);
JNI_IMPORT(is_assignable_from)  int jx_is_assignable_from(int c1, int c2);
JNI_IMPORT(is_instance_of)      int jx_is_instance_of(int obj, int cls);
JNI_IMPORT(is_same_object)      int jx_is_same_object(int a, int b);
JNI_IMPORT(new_ref)             int jx_new_ref(int obj, int kind);
JNI_IMPORT(delete_ref)          void jx_delete_ref(int ref);
JNI_IMPORT(ref_type)            int jx_ref_type(int ref);
JNI_IMPORT(push_frame)          int jx_push_frame(void);
JNI_IMPORT(pop_frame)           int jx_pop_frame(int result);
JNI_IMPORT(attach)              int jx_attach(void);
JNI_IMPORT(detach)              int jx_detach(void);
JNI_IMPORT(is_attached)         int jx_is_attached(void);
JNI_IMPORT(get_method_id)       int jx_get_method_id(int cls, const char *name, const char *sig, int isStatic);
JNI_IMPORT(get_field_id)        int jx_get_field_id(int cls, const char *name, const char *sig, int isStatic);
JNI_IMPORT(call)                int jx_call(int mode, int obj, int cls, int mid, const void *args, int isVaList, jvalue *result);
JNI_IMPORT(field)               int jx_field(int op, int obj, int fid, jvalue *value);
JNI_IMPORT(throw)               int jx_throw(int t);
JNI_IMPORT(throw_new)           int jx_throw_new(int cls, const char *msg);
JNI_IMPORT(exception_occurred)  int jx_exception_occurred(void);
JNI_IMPORT(exception_check)     int jx_exception_check(void);
JNI_IMPORT(exception_clear)     void jx_exception_clear(void);
JNI_IMPORT(exception_describe)  void jx_exception_describe(void);
JNI_IMPORT(fatal_error)         void jx_fatal_error(const char *msg);
JNI_IMPORT(new_string_utf)      int jx_new_string_utf(const char *s);
JNI_IMPORT(new_string)          int jx_new_string(const jchar *s, int len);
JNI_IMPORT(string_length)       int jx_string_length(int s);
JNI_IMPORT(string_utf_length)   int jx_string_utf_length(int s);
JNI_IMPORT(string_region)       int jx_string_region(int s, int start, int len, void *buf, int utf);
JNI_IMPORT(array_length)        int jx_array_length(int arr);
JNI_IMPORT(array_kind)          int jx_array_kind(int arr);
JNI_IMPORT(new_object_array)    int jx_new_object_array(int len, int cls, int init);
JNI_IMPORT(get_object_element)  int jx_get_object_element(int arr, int index);
JNI_IMPORT(set_object_element)  void jx_set_object_element(int arr, int index, int value);
JNI_IMPORT(new_primitive_array) int jx_new_primitive_array(int kind, int len);
JNI_IMPORT(primitive_region)    int jx_primitive_region(int arr, int start, int len, void *buf, int set);
JNI_IMPORT(monitor)             int jx_monitor(int obj, int enter);

_Static_assert(sizeof(va_list) == sizeof(void *), "wasm32: va_list is a pointer");
_Static_assert(sizeof(jobject) == 4 && sizeof(jvalue) == 8, "wasm32");

#define H(o)    ((int)(uintptr_t)(o))
#define OBJ(h)  ((jobject)(uintptr_t)(h))
#define MID(id) ((jmethodID)(uintptr_t)(id))
#define FID(id) ((jfieldID)(uintptr_t)(id))

enum { VIRTUAL = 0, NONVIRTUAL = 1, STATIC = 2, NEW = 3 };
enum { REF_LOCAL = 1, REF_GLOBAL = 2, REF_WEAK = 3 };

#ifdef JNI_VERSION_21
#define ORX_JNI_VERSION JNI_VERSION_21
#else
#define ORX_JNI_VERSION JNI_VERSION_1_8
#endif

static const void *va(va_list ap)
{
    const void *p;
    memcpy(&p, &ap, sizeof p);
    return p;
}

static jvalue call(int mode, jobject obj, jclass cls, jmethodID mid, const void *args, int isVaList)
{
    jvalue r;
    memset(&r, 0, sizeof r);
    jx_call(mode, H(obj), H(cls), H(mid), args, isVaList, &r);
    return r;
}

/* ---- version, classes, exceptions, references ---------------------------------------- */

static jint JNICALL GetVersion(JNIEnv *env) { (void)env; return ORX_JNI_VERSION; }

static jclass JNICALL FindClass(JNIEnv *env, const char *name)
{ (void)env; return (jclass)OBJ(jx_find_class(name)); }

static jclass JNICALL GetSuperclass(JNIEnv *env, jclass sub)
{ (void)env; return (jclass)OBJ(jx_get_superclass(H(sub))); }

static jboolean JNICALL IsAssignableFrom(JNIEnv *env, jclass sub, jclass sup)
{ (void)env; return (jboolean)jx_is_assignable_from(H(sub), H(sup)); }

static jint JNICALL Throw(JNIEnv *env, jthrowable t) { (void)env; return jx_throw(H(t)); }

static jint JNICALL ThrowNew(JNIEnv *env, jclass cls, const char *msg)
{ (void)env; return jx_throw_new(H(cls), msg); }

static jthrowable JNICALL ExceptionOccurred(JNIEnv *env)
{ (void)env; return (jthrowable)OBJ(jx_exception_occurred()); }

static void JNICALL ExceptionDescribe(JNIEnv *env) { (void)env; jx_exception_describe(); }
static void JNICALL ExceptionClear(JNIEnv *env) { (void)env; jx_exception_clear(); }
static jboolean JNICALL ExceptionCheck(JNIEnv *env) { (void)env; return (jboolean)jx_exception_check(); }

static void JNICALL FatalError(JNIEnv *env, const char *msg)
{
    (void)env;
    jx_fatal_error(msg);
    abort();
}

static jint JNICALL PushLocalFrame(JNIEnv *env, jint capacity)
{ (void)env; (void)capacity; return jx_push_frame(); }

static jobject JNICALL PopLocalFrame(JNIEnv *env, jobject result)
{ (void)env; return OBJ(jx_pop_frame(H(result))); }

static jobject JNICALL NewGlobalRef(JNIEnv *env, jobject o) { (void)env; return OBJ(jx_new_ref(H(o), REF_GLOBAL)); }
static void JNICALL DeleteGlobalRef(JNIEnv *env, jobject o) { (void)env; jx_delete_ref(H(o)); }
static void JNICALL DeleteLocalRef(JNIEnv *env, jobject o) { (void)env; jx_delete_ref(H(o)); }
static jobject JNICALL NewLocalRef(JNIEnv *env, jobject o) { (void)env; return OBJ(jx_new_ref(H(o), REF_LOCAL)); }
static jweak JNICALL NewWeakGlobalRef(JNIEnv *env, jobject o) { (void)env; return (jweak)OBJ(jx_new_ref(H(o), REF_WEAK)); }
static void JNICALL DeleteWeakGlobalRef(JNIEnv *env, jweak o) { (void)env; jx_delete_ref(H(o)); }
static jint JNICALL EnsureLocalCapacity(JNIEnv *env, jint capacity) { (void)env; (void)capacity; return JNI_OK; }

static jobjectRefType JNICALL GetObjectRefType(JNIEnv *env, jobject o)
{ (void)env; return (jobjectRefType)jx_ref_type(H(o)); }

static jboolean JNICALL IsSameObject(JNIEnv *env, jobject a, jobject b)
{ (void)env; return (jboolean)jx_is_same_object(H(a), H(b)); }

static jclass JNICALL GetObjectClass(JNIEnv *env, jobject o)
{ (void)env; return (jclass)OBJ(jx_get_object_class(H(o))); }

static jboolean JNICALL IsInstanceOf(JNIEnv *env, jobject o, jclass cls)
{ (void)env; return (jboolean)jx_is_instance_of(H(o), H(cls)); }

/* ---- methods and fields ------------------------------------------------------------- */

static jmethodID JNICALL GetMethodID(JNIEnv *env, jclass cls, const char *name, const char *sig)
{ (void)env; return MID(jx_get_method_id(H(cls), name, sig, 0)); }

static jmethodID JNICALL GetStaticMethodID(JNIEnv *env, jclass cls, const char *name, const char *sig)
{ (void)env; return MID(jx_get_method_id(H(cls), name, sig, 1)); }

static jfieldID JNICALL GetFieldID(JNIEnv *env, jclass cls, const char *name, const char *sig)
{ (void)env; return FID(jx_get_field_id(H(cls), name, sig, 0)); }

static jfieldID JNICALL GetStaticFieldID(JNIEnv *env, jclass cls, const char *name, const char *sig)
{ (void)env; return FID(jx_get_field_id(H(cls), name, sig, 1)); }

static jobject JNICALL NewObject(JNIEnv *env, jclass cls, jmethodID mid, ...)
{
    (void)env;
    va_list ap;
    va_start(ap, mid);
    jvalue r = call(NEW, NULL, cls, mid, va(ap), 1);
    va_end(ap);
    return r.l;
}

static jobject JNICALL NewObjectV(JNIEnv *env, jclass cls, jmethodID mid, va_list ap)
{ (void)env; return call(NEW, NULL, cls, mid, va(ap), 1).l; }

static jobject JNICALL NewObjectA(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args)
{ (void)env; return call(NEW, NULL, cls, mid, args, 0).l; }

/* Call<T>Method, Call<T>MethodV, Call<T>MethodA, and the Nonvirtual and Static ones */
#define CALLS(T, jt, u)                                                                          \
static jt JNICALL Call##T##Method(JNIEnv *env, jobject o, jmethodID mid, ...)                    \
{ (void)env; va_list ap; va_start(ap, mid); jvalue r = call(VIRTUAL, o, NULL, mid, va(ap), 1);  \
  va_end(ap); return r.u; }                                                                      \
static jt JNICALL Call##T##MethodV(JNIEnv *env, jobject o, jmethodID mid, va_list ap)            \
{ (void)env; return call(VIRTUAL, o, NULL, mid, va(ap), 1).u; }                                 \
static jt JNICALL Call##T##MethodA(JNIEnv *env, jobject o, jmethodID mid, const jvalue *args)    \
{ (void)env; return call(VIRTUAL, o, NULL, mid, args, 0).u; }                                   \
static jt JNICALL CallNonvirtual##T##Method(JNIEnv *env, jobject o, jclass c, jmethodID mid, ...) \
{ (void)env; va_list ap; va_start(ap, mid); jvalue r = call(NONVIRTUAL, o, c, mid, va(ap), 1);  \
  va_end(ap); return r.u; }                                                                      \
static jt JNICALL CallNonvirtual##T##MethodV(JNIEnv *env, jobject o, jclass c, jmethodID mid,    \
                                             va_list ap)                                         \
{ (void)env; return call(NONVIRTUAL, o, c, mid, va(ap), 1).u; }                                 \
static jt JNICALL CallNonvirtual##T##MethodA(JNIEnv *env, jobject o, jclass c, jmethodID mid,    \
                                             const jvalue *args)                                 \
{ (void)env; return call(NONVIRTUAL, o, c, mid, args, 0).u; }                                   \
static jt JNICALL CallStatic##T##Method(JNIEnv *env, jclass c, jmethodID mid, ...)               \
{ (void)env; va_list ap; va_start(ap, mid); jvalue r = call(STATIC, NULL, c, mid, va(ap), 1);   \
  va_end(ap); return r.u; }                                                                      \
static jt JNICALL CallStatic##T##MethodV(JNIEnv *env, jclass c, jmethodID mid, va_list ap)       \
{ (void)env; return call(STATIC, NULL, c, mid, va(ap), 1).u; }                                  \
static jt JNICALL CallStatic##T##MethodA(JNIEnv *env, jclass c, jmethodID mid, const jvalue *args) \
{ (void)env; return call(STATIC, NULL, c, mid, args, 0).u; }

CALLS(Object, jobject, l)
CALLS(Boolean, jboolean, z)
CALLS(Byte, jbyte, b)
CALLS(Char, jchar, c)
CALLS(Short, jshort, s)
CALLS(Int, jint, i)
CALLS(Long, jlong, j)
CALLS(Float, jfloat, f)
CALLS(Double, jdouble, d)

static void JNICALL CallVoidMethod(JNIEnv *env, jobject o, jmethodID mid, ...)
{ (void)env; va_list ap; va_start(ap, mid); call(VIRTUAL, o, NULL, mid, va(ap), 1); va_end(ap); }
static void JNICALL CallVoidMethodV(JNIEnv *env, jobject o, jmethodID mid, va_list ap)
{ (void)env; call(VIRTUAL, o, NULL, mid, va(ap), 1); }
static void JNICALL CallVoidMethodA(JNIEnv *env, jobject o, jmethodID mid, const jvalue *args)
{ (void)env; call(VIRTUAL, o, NULL, mid, args, 0); }
static void JNICALL CallNonvirtualVoidMethod(JNIEnv *env, jobject o, jclass c, jmethodID mid, ...)
{ (void)env; va_list ap; va_start(ap, mid); call(NONVIRTUAL, o, c, mid, va(ap), 1); va_end(ap); }
static void JNICALL CallNonvirtualVoidMethodV(JNIEnv *env, jobject o, jclass c, jmethodID mid, va_list ap)
{ (void)env; call(NONVIRTUAL, o, c, mid, va(ap), 1); }
static void JNICALL CallNonvirtualVoidMethodA(JNIEnv *env, jobject o, jclass c, jmethodID mid, const jvalue *args)
{ (void)env; call(NONVIRTUAL, o, c, mid, args, 0); }
static void JNICALL CallStaticVoidMethod(JNIEnv *env, jclass c, jmethodID mid, ...)
{ (void)env; va_list ap; va_start(ap, mid); call(STATIC, NULL, c, mid, va(ap), 1); va_end(ap); }
static void JNICALL CallStaticVoidMethodV(JNIEnv *env, jclass c, jmethodID mid, va_list ap)
{ (void)env; call(STATIC, NULL, c, mid, va(ap), 1); }
static void JNICALL CallStaticVoidMethodA(JNIEnv *env, jclass c, jmethodID mid, const jvalue *args)
{ (void)env; call(STATIC, NULL, c, mid, args, 0); }

/* Get<T>Field, Set<T>Field, GetStatic<T>Field, SetStatic<T>Field */
#define FIELDS(T, jt, u)                                                                   \
static jt JNICALL Get##T##Field(JNIEnv *env, jobject o, jfieldID f)                        \
{ (void)env; jvalue v; memset(&v, 0, sizeof v); jx_field(0, H(o), H(f), &v); return v.u; } \
static void JNICALL Set##T##Field(JNIEnv *env, jobject o, jfieldID f, jt x)               \
{ (void)env; jvalue v; memset(&v, 0, sizeof v); v.u = x; jx_field(1, H(o), H(f), &v); }   \
static jt JNICALL GetStatic##T##Field(JNIEnv *env, jclass c, jfieldID f)                   \
{ (void)env; (void)c; jvalue v; memset(&v, 0, sizeof v); jx_field(0, 0, H(f), &v); return v.u; } \
static void JNICALL SetStatic##T##Field(JNIEnv *env, jclass c, jfieldID f, jt x)          \
{ (void)env; (void)c; jvalue v; memset(&v, 0, sizeof v); v.u = x; jx_field(1, 0, H(f), &v); }

FIELDS(Object, jobject, l)
FIELDS(Boolean, jboolean, z)
FIELDS(Byte, jbyte, b)
FIELDS(Char, jchar, c)
FIELDS(Short, jshort, s)
FIELDS(Int, jint, i)
FIELDS(Long, jlong, j)
FIELDS(Float, jfloat, f)
FIELDS(Double, jdouble, d)

/* ---- strings -------------------------------------------------------------------------- */

static jstring JNICALL NewString(JNIEnv *env, const jchar *s, jsize len)
{ (void)env; return (jstring)OBJ(jx_new_string(s, len)); }

static jsize JNICALL GetStringLength(JNIEnv *env, jstring s)
{ (void)env; return jx_string_length(H(s)); }

static const jchar *JNICALL GetStringChars(JNIEnv *env, jstring s, jboolean *isCopy)
{
    (void)env;
    int n = jx_string_length(H(s));
    if (jx_exception_check()) return NULL;
    jchar *buf = (jchar *)malloc(2 * (size_t)n + 2);
    if (buf == NULL) return NULL;
    jx_string_region(H(s), 0, n, buf, 0);
    buf[n] = 0;
    if (isCopy) *isCopy = JNI_TRUE;
    return buf;
}

static void JNICALL ReleaseStringChars(JNIEnv *env, jstring s, const jchar *chars)
{ (void)env; (void)s; free((void *)chars); }

static jstring JNICALL NewStringUTF(JNIEnv *env, const char *utf)
{ (void)env; return utf == NULL ? NULL : (jstring)OBJ(jx_new_string_utf(utf)); }

static jsize JNICALL GetStringUTFLength(JNIEnv *env, jstring s)
{ (void)env; return jx_string_utf_length(H(s)); }

static const char *JNICALL GetStringUTFChars(JNIEnv *env, jstring s, jboolean *isCopy)
{
    (void)env;
    int n = jx_string_length(H(s));
    if (jx_exception_check()) return NULL;
    int bytes = jx_string_utf_length(H(s));
    char *buf = (char *)malloc((size_t)bytes + 1);
    if (buf == NULL) return NULL;
    jx_string_region(H(s), 0, n, buf, 1);
    buf[bytes] = 0;
    if (isCopy) *isCopy = JNI_TRUE;
    return buf;
}

static void JNICALL ReleaseStringUTFChars(JNIEnv *env, jstring s, const char *chars)
{ (void)env; (void)s; free((void *)chars); }

static void JNICALL GetStringRegion(JNIEnv *env, jstring s, jsize start, jsize len, jchar *buf)
{ (void)env; jx_string_region(H(s), start, len, buf, 0); }

static void JNICALL GetStringUTFRegion(JNIEnv *env, jstring s, jsize start, jsize len, char *buf)
{
    (void)env;
    int w = jx_string_region(H(s), start, len, buf, 1);
    if (!jx_exception_check()) buf[w] = 0;
}

static const jchar *JNICALL GetStringCritical(JNIEnv *env, jstring s, jboolean *isCopy)
{ return GetStringChars(env, s, isCopy); }

static void JNICALL ReleaseStringCritical(JNIEnv *env, jstring s, const jchar *chars)
{ ReleaseStringChars(env, s, chars); }

/* ---- arrays --------------------------------------------------------------------------- */

static jsize JNICALL GetArrayLength(JNIEnv *env, jarray a)
{ (void)env; return jx_array_length(H(a)); }

static jobjectArray JNICALL NewObjectArray(JNIEnv *env, jsize len, jclass cls, jobject init)
{ (void)env; return (jobjectArray)OBJ(jx_new_object_array(len, H(cls), H(init))); }

static jobject JNICALL GetObjectArrayElement(JNIEnv *env, jobjectArray a, jsize i)
{ (void)env; return OBJ(jx_get_object_element(H(a), i)); }

static void JNICALL SetObjectArrayElement(JNIEnv *env, jobjectArray a, jsize i, jobject v)
{ (void)env; jx_set_object_element(H(a), i, H(v)); }

/* a copy of the elements, after a header with their number (8 bytes, to keep jlong and
   jdouble aligned) */
#define HEADER 8

static size_t kind_size(int k)
{
    switch (k)
    {
        case 'Z': case 'B': return 1;
        case 'C': case 'S': return 2;
        case 'I': case 'F': return 4;
        default:            return 8;
    }
}

static void *elements(jarray a, int kind, jboolean *isCopy)
{
    int n = jx_array_length(H(a));
    if (jx_exception_check()) return NULL;
    char *block = (char *)malloc(HEADER + (size_t)n * kind_size(kind));
    if (block == NULL) return NULL;
    *(int *)block = n;
    if (!jx_primitive_region(H(a), 0, n, block + HEADER, 0))
    {
        free(block);
        return NULL;
    }
    if (isCopy) *isCopy = JNI_TRUE;
    return block + HEADER;
}

static void release(jarray a, void *elems, jint mode)
{
    if (elems == NULL) return;
    char *block = (char *)elems - HEADER;
    if (mode != JNI_ABORT) jx_primitive_region(H(a), 0, *(int *)block, elems, 1);
    if (mode != JNI_COMMIT) free(block);
}

#define PRIMITIVE_ARRAYS(T, jt, k)                                                            \
static jt##Array JNICALL New##T##Array(JNIEnv *env, jsize len)                                \
{ (void)env; return (jt##Array)OBJ(jx_new_primitive_array(k, len)); }                         \
static jt *JNICALL Get##T##ArrayElements(JNIEnv *env, jt##Array a, jboolean *isCopy)          \
{ (void)env; return (jt *)elements(a, k, isCopy); }                                            \
static void JNICALL Release##T##ArrayElements(JNIEnv *env, jt##Array a, jt *e, jint mode)     \
{ (void)env; release(a, e, mode); }                                                            \
static void JNICALL Get##T##ArrayRegion(JNIEnv *env, jt##Array a, jsize start, jsize len,     \
                                        jt *buf)                                               \
{ (void)env; jx_primitive_region(H(a), start, len, buf, 0); }                                  \
static void JNICALL Set##T##ArrayRegion(JNIEnv *env, jt##Array a, jsize start, jsize len,     \
                                        const jt *buf)                                         \
{ (void)env; jx_primitive_region(H(a), start, len, (void *)buf, 1); }

PRIMITIVE_ARRAYS(Boolean, jboolean, 'Z')
PRIMITIVE_ARRAYS(Byte, jbyte, 'B')
PRIMITIVE_ARRAYS(Char, jchar, 'C')
PRIMITIVE_ARRAYS(Short, jshort, 'S')
PRIMITIVE_ARRAYS(Int, jint, 'I')
PRIMITIVE_ARRAYS(Long, jlong, 'J')
PRIMITIVE_ARRAYS(Float, jfloat, 'F')
PRIMITIVE_ARRAYS(Double, jdouble, 'D')

static void *JNICALL GetPrimitiveArrayCritical(JNIEnv *env, jarray a, jboolean *isCopy)
{
    (void)env;
    int k = jx_array_kind(H(a));
    if (jx_exception_check()) return NULL;
    return elements(a, k, isCopy);
}

static void JNICALL ReleasePrimitiveArrayCritical(JNIEnv *env, jarray a, void *e, jint mode)
{ (void)env; release(a, e, mode); }

/* ---- monitors, the VM ---------------------------------------------------------------- */

static jint JNICALL MonitorEnter(JNIEnv *env, jobject o)
{ (void)env; jx_monitor(H(o), 1); return jx_exception_check() ? JNI_ERR : JNI_OK; }

static jint JNICALL MonitorExit(JNIEnv *env, jobject o)
{ (void)env; jx_monitor(H(o), 0); return jx_exception_check() ? JNI_ERR : JNI_OK; }

static jint JNICALL GetJavaVM(JNIEnv *env, JavaVM **vm);

/* ---- the tables ------------------------------------------------------------------------ */

#define CALL_ENTRIES(T)                                                                  \
    .Call##T##Method = Call##T##Method, .Call##T##MethodV = Call##T##MethodV,            \
    .Call##T##MethodA = Call##T##MethodA,                                                \
    .CallNonvirtual##T##Method = CallNonvirtual##T##Method,                              \
    .CallNonvirtual##T##MethodV = CallNonvirtual##T##MethodV,                            \
    .CallNonvirtual##T##MethodA = CallNonvirtual##T##MethodA,                            \
    .CallStatic##T##Method = CallStatic##T##Method,                                      \
    .CallStatic##T##MethodV = CallStatic##T##MethodV,                                    \
    .CallStatic##T##MethodA = CallStatic##T##MethodA

#define FIELD_ENTRIES(T)                                                                 \
    .Get##T##Field = Get##T##Field, .Set##T##Field = Set##T##Field,                      \
    .GetStatic##T##Field = GetStatic##T##Field, .SetStatic##T##Field = SetStatic##T##Field

#define ARRAY_ENTRIES(T)                                                                 \
    .New##T##Array = New##T##Array, .Get##T##ArrayElements = Get##T##ArrayElements,      \
    .Release##T##ArrayElements = Release##T##ArrayElements,                              \
    .Get##T##ArrayRegion = Get##T##ArrayRegion, .Set##T##ArrayRegion = Set##T##ArrayRegion

static const struct JNINativeInterface_ jni_functions = {
    .GetVersion = GetVersion,
    .FindClass = FindClass,
    .GetSuperclass = GetSuperclass,
    .IsAssignableFrom = IsAssignableFrom,
    .Throw = Throw,
    .ThrowNew = ThrowNew,
    .ExceptionOccurred = ExceptionOccurred,
    .ExceptionDescribe = ExceptionDescribe,
    .ExceptionClear = ExceptionClear,
    .FatalError = FatalError,
    .PushLocalFrame = PushLocalFrame,
    .PopLocalFrame = PopLocalFrame,
    .NewGlobalRef = NewGlobalRef,
    .DeleteGlobalRef = DeleteGlobalRef,
    .DeleteLocalRef = DeleteLocalRef,
    .IsSameObject = IsSameObject,
    .NewLocalRef = NewLocalRef,
    .EnsureLocalCapacity = EnsureLocalCapacity,
    .NewObject = NewObject,
    .NewObjectV = NewObjectV,
    .NewObjectA = NewObjectA,
    .GetObjectClass = GetObjectClass,
    .IsInstanceOf = IsInstanceOf,
    .GetMethodID = GetMethodID,
    CALL_ENTRIES(Object), CALL_ENTRIES(Boolean), CALL_ENTRIES(Byte), CALL_ENTRIES(Char),
    CALL_ENTRIES(Short), CALL_ENTRIES(Int), CALL_ENTRIES(Long), CALL_ENTRIES(Float),
    CALL_ENTRIES(Double), CALL_ENTRIES(Void),
    .GetFieldID = GetFieldID,
    .GetStaticMethodID = GetStaticMethodID,
    .GetStaticFieldID = GetStaticFieldID,
    FIELD_ENTRIES(Object), FIELD_ENTRIES(Boolean), FIELD_ENTRIES(Byte), FIELD_ENTRIES(Char),
    FIELD_ENTRIES(Short), FIELD_ENTRIES(Int), FIELD_ENTRIES(Long), FIELD_ENTRIES(Float),
    FIELD_ENTRIES(Double),
    .NewString = NewString,
    .GetStringLength = GetStringLength,
    .GetStringChars = GetStringChars,
    .ReleaseStringChars = ReleaseStringChars,
    .NewStringUTF = NewStringUTF,
    .GetStringUTFLength = GetStringUTFLength,
    .GetStringUTFChars = GetStringUTFChars,
    .ReleaseStringUTFChars = ReleaseStringUTFChars,
    .GetArrayLength = GetArrayLength,
    .NewObjectArray = NewObjectArray,
    .GetObjectArrayElement = GetObjectArrayElement,
    .SetObjectArrayElement = SetObjectArrayElement,
    ARRAY_ENTRIES(Boolean), ARRAY_ENTRIES(Byte), ARRAY_ENTRIES(Char), ARRAY_ENTRIES(Short),
    ARRAY_ENTRIES(Int), ARRAY_ENTRIES(Long), ARRAY_ENTRIES(Float), ARRAY_ENTRIES(Double),
    .MonitorEnter = MonitorEnter,
    .MonitorExit = MonitorExit,
    .GetJavaVM = GetJavaVM,
    .GetStringRegion = GetStringRegion,
    .GetStringUTFRegion = GetStringUTFRegion,
    .GetPrimitiveArrayCritical = GetPrimitiveArrayCritical,
    .ReleasePrimitiveArrayCritical = ReleasePrimitiveArrayCritical,
    .GetStringCritical = GetStringCritical,
    .ReleaseStringCritical = ReleaseStringCritical,
    .NewWeakGlobalRef = NewWeakGlobalRef,
    .DeleteWeakGlobalRef = DeleteWeakGlobalRef,
    .ExceptionCheck = ExceptionCheck,
    .GetObjectRefType = GetObjectRefType,
};

static const struct JNINativeInterface_ *const jni_env_storage = &jni_functions;

/** The JNIEnv: the same for every thread. */
JNIEnv *orx_jni_env(void)
{
    return (JNIEnv *)&jni_env_storage;
}

static jint JNICALL DestroyJavaVM(JavaVM *vm) { (void)vm; return JNI_ERR; }   /* the JVM hosts the module */

static jint JNICALL AttachCurrentThread(JavaVM *vm, void **penv, void *args)
{
    (void)vm; (void)args;
    jx_attach();
    *penv = orx_jni_env();
    return JNI_OK;
}

static jint JNICALL DetachCurrentThread(JavaVM *vm) { (void)vm; jx_detach(); return JNI_OK; }

static jint JNICALL GetEnv(JavaVM *vm, void **penv, jint version)
{
    (void)vm;
    if (version > ORX_JNI_VERSION || version < JNI_VERSION_1_1)
    {
        *penv = NULL;
        return JNI_EVERSION;
    }
    if (!jx_is_attached())
    {
        *penv = NULL;
        return JNI_EDETACHED;
    }
    *penv = orx_jni_env();
    return JNI_OK;
}

static const struct JNIInvokeInterface_ jvm_functions = {
    .DestroyJavaVM = DestroyJavaVM,
    .AttachCurrentThread = AttachCurrentThread,
    .DetachCurrentThread = DetachCurrentThread,
    .GetEnv = GetEnv,
    .AttachCurrentThreadAsDaemon = AttachCurrentThread,
};

static const struct JNIInvokeInterface_ *const jvm_storage = &jvm_functions;

/** The JavaVM: the JVM that runs the module. */
JavaVM *orx_jni_vm(void)
{
    return (JavaVM *)&jvm_storage;
}

static jint JNICALL GetJavaVM(JNIEnv *env, JavaVM **vm)
{
    (void)env;
    *vm = orx_jni_vm();
    return JNI_OK;
}

/* The invocation API: there is exactly one JVM, the one that runs the module, and it
   cannot be created or destroyed from here. */
jint JNICALL JNI_GetDefaultJavaVMInitArgs(void *args)
{
    (void)args;
    return JNI_OK;
}

jint JNICALL JNI_GetCreatedJavaVMs(JavaVM **vms, jsize size, jsize *count)
{
    if (size > 0 && vms != NULL) vms[0] = orx_jni_vm();
    if (count != NULL) *count = 1;
    return JNI_OK;
}

jint JNICALL JNI_CreateJavaVM(JavaVM **pvm, void **penv, void *args)
{
    (void)args;
    *pvm = orx_jni_vm();
    *penv = NULL;
    return JNI_EEXIST;
}
