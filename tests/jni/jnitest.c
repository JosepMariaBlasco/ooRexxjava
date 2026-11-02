/* The mini-JNI (compat/orx_jni.c) from C, as BSF4ooRexx.cc will use it: every family
   of JNIEnv functions against tests/jni/jnitest/JniTarget.java. Linked into
   dist-test's rexx.wasm as a tool: rexxj --orx-wasm-jnitest (see tests/jni/README.md).
   Prints one line per failed check and a summary; the exit code is the number of
   failures. */

#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

JNIEnv *orx_jni_env(void);
JavaVM *orx_jni_vm(void);

static int checks, failures;

#define CHECK(cond, ...) do {                                           \
        checks++;                                                       \
        if (!(cond)) {                                                  \
            failures++;                                                 \
            printf("FAIL %s:%d: ", __FILE__, __LINE__);                 \
            printf(__VA_ARGS__);                                        \
            printf("\n");                                               \
        }                                                               \
    } while (0)

#define NO_EXCEPTION(env, what) do {                                    \
        if ((*env)->ExceptionCheck(env)) {                              \
            printf("exception after %s: ", what);                       \
            fflush(stdout);                                             \
            (*env)->ExceptionDescribe(env);                             \
        }                                                               \
        CHECK(1, "%s", what);                                           \
    } while (0)

/* a string's value as UTF-8 (modified), into buf */
static const char *text(JNIEnv *env, jobject s, char *buf, size_t size)
{
    if (s == NULL) { snprintf(buf, size, "<null>"); return buf; }
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    snprintf(buf, size, "%s", c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return buf;
}

/* the class name and message of the pending exception, which is cleared */
static const char *pending(JNIEnv *env, char *buf, size_t size)
{
    jthrowable t = (*env)->ExceptionOccurred(env);
    if (t == NULL) { snprintf(buf, size, "<none>"); return buf; }
    (*env)->ExceptionClear(env);
    jclass tc = (*env)->GetObjectClass(env, t);
    jclass cc = (*env)->FindClass(env, "java/lang/Class");
    jmethodID getName = (*env)->GetMethodID(env, cc, "getName", "()Ljava/lang/String;");
    jmethodID getMessage = (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Throwable"),
                                               "getMessage", "()Ljava/lang/String;");
    char n[200], m[200];
    text(env, (*env)->CallObjectMethod(env, tc, getName), n, sizeof n);
    text(env, (*env)->CallObjectMethod(env, t, getMessage), m, sizeof m);
    snprintf(buf, size, "%s: %s", n, m);
    return buf;
}

static jlong callV(JNIEnv *env, jobject o, jmethodID mid, ...)
{
    va_list ap;
    va_start(ap, mid);
    jlong r = (*env)->CallLongMethodV(env, o, mid, ap);
    va_end(ap);
    return r;
}

static double now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static jclass target;   /* a global reference to jnitest.JniTarget */

static void *worker(void *arg)
{
    int n = (int)(long)arg;
    JNIEnv *env;
    JavaVM *vm = orx_jni_vm();
    if ((*vm)->AttachCurrentThread(vm, (void **)&env, NULL) != JNI_OK) return (void *)1L;
    jmethodID add = (*env)->GetMethodID(env, target, "add", "(II)I");
    jmethodID ctor = (*env)->GetMethodID(env, target, "<init>", "(ILjava/lang/String;)V");
    long bad = 0;
    for (int k = 0; k < 2000; k++)
    {
        char s[32];
        snprintf(s, sizeof s, "t%d-%d", n, k);
        jstring js = (*env)->NewStringUTF(env, s);
        jobject o = (*env)->NewObject(env, target, ctor, k, js);
        if ((*env)->CallIntMethod(env, o, add, k, n) != k + n) bad++;
        char back[32];
        text(env, (*env)->GetObjectField(env, o, (*env)->GetFieldID(env, target, "str", "Ljava/lang/String;")), back, sizeof back);
        if (strcmp(back, s) != 0) bad++;
        (*env)->DeleteLocalRef(env, js);
        (*env)->DeleteLocalRef(env, o);
    }
    (*vm)->DetachCurrentThread(vm);
    return (void *)bad;
}

int main(int argc, char **argv)
{
    (void)argc; (void)argv;
    JavaVM *vm = orx_jni_vm();
    JNIEnv *env = NULL;
    char b1[400], b2[400];

    /* the VM and attaching */
    CHECK((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) == JNI_EDETACHED && env == NULL, "GetEnv before attaching");
    CHECK((*vm)->GetEnv(vm, (void **)&env, 0x7fff0000) == JNI_EVERSION, "GetEnv, version too high");
    CHECK((*vm)->AttachCurrentThread(vm, (void **)&env, NULL) == JNI_OK && env != NULL, "AttachCurrentThread");
    JNIEnv *env2;
    CHECK((*vm)->GetEnv(vm, (void **)&env2, JNI_VERSION_1_8) == JNI_OK && env2 == env, "GetEnv attached");
    JavaVM *vm2;
    CHECK((*env)->GetJavaVM(env, &vm2) == JNI_OK && vm2 == vm, "GetJavaVM");
    CHECK((*env)->GetVersion(env) >= JNI_VERSION_1_8, "GetVersion: %x", (*env)->GetVersion(env));
    JavaVM *vms[2]; jsize nvm = 0;
    CHECK(JNI_GetCreatedJavaVMs(vms, 2, &nvm) == JNI_OK && nvm == 1 && vms[0] == vm, "JNI_GetCreatedJavaVMs");

    /* classes */
    jclass lt = (*env)->FindClass(env, "jnitest/JniTarget");
    NO_EXCEPTION(env, "FindClass jnitest/JniTarget");
    CHECK(lt != NULL, "FindClass jnitest/JniTarget");
    target = (jclass)(*env)->NewGlobalRef(env, lt);
    CHECK((*env)->GetObjectRefType(env, lt) == JNILocalRefType, "local ref type");
    CHECK((*env)->GetObjectRefType(env, target) == JNIGlobalRefType, "global ref type");
    CHECK((*env)->IsSameObject(env, lt, target), "IsSameObject local/global");
    (*env)->DeleteLocalRef(env, lt);
    CHECK((*env)->GetObjectRefType(env, lt) == JNIInvalidRefType, "deleted local ref");
    jclass base = (*env)->GetSuperclass(env, target);
    jclass object = (*env)->FindClass(env, "java/lang/Object");
    jclass string = (*env)->FindClass(env, "java/lang/String");
    jclass cls = (*env)->FindClass(env, "java/lang/Class");
    jmethodID getName = (*env)->GetMethodID(env, cls, "getName", "()Ljava/lang/String;");
    CHECK(strcmp(text(env, (*env)->CallObjectMethod(env, base, getName), b1, sizeof b1), "jnitest.JniBase") == 0, "GetSuperclass: %s", b1);
    CHECK((*env)->IsAssignableFrom(env, target, base) && !(*env)->IsAssignableFrom(env, base, target), "IsAssignableFrom");
    CHECK((*env)->GetSuperclass(env, object) == NULL, "GetSuperclass(Object)");
    jclass sarr = (*env)->FindClass(env, "[Ljava/lang/String;");
    CHECK(sarr != NULL && strcmp(text(env, (*env)->CallObjectMethod(env, sarr, getName), b1, sizeof b1), "[Ljava.lang.String;") == 0, "FindClass array: %s", b1);
    CHECK((*env)->FindClass(env, "no/such/Klass") == NULL, "FindClass missing");
    CHECK(strncmp(pending(env, b1, sizeof b1), "java.lang.NoClassDefFoundError: no/such/Klass", 45) == 0, "FindClass missing: %s", b1);

    /* objects, constructors, methods */
    jmethodID ctor0 = (*env)->GetMethodID(env, target, "<init>", "()V");
    jmethodID ctor2 = (*env)->GetMethodID(env, target, "<init>", "(ILjava/lang/String;)V");
    jobject o = (*env)->NewObject(env, target, ctor0);
    NO_EXCEPTION(env, "NewObject");
    CHECK((*env)->IsInstanceOf(env, o, target) && (*env)->IsInstanceOf(env, o, object) && !(*env)->IsInstanceOf(env, o, string), "IsInstanceOf");
    CHECK((*env)->IsInstanceOf(env, NULL, string), "IsInstanceOf(NULL)");
    CHECK((*env)->IsSameObject(env, (*env)->GetObjectClass(env, o), target), "GetObjectClass");
    jvalue cv[2]; cv[0].i = 5; cv[1].l = (*env)->NewStringUTF(env, "five");
    jobject o2 = (*env)->NewObjectA(env, target, ctor2, cv);
    jfieldID fi = (*env)->GetFieldID(env, target, "i", "I");
    jfieldID fstr = (*env)->GetFieldID(env, target, "str", "Ljava/lang/String;");
    CHECK((*env)->GetIntField(env, o2, fi) == 5, "NewObjectA: int field");
    CHECK(strcmp(text(env, (*env)->GetObjectField(env, o2, fstr), b1, sizeof b1), "five") == 0, "NewObjectA: string field");

    jmethodID add = (*env)->GetMethodID(env, target, "add", "(II)I");
    CHECK((*env)->CallIntMethod(env, o, add, 40, 2) == 42, "CallIntMethod");
    jvalue av[2]; av[0].i = -7; av[1].i = 10;
    CHECK((*env)->CallIntMethodA(env, o, add, av) == 3, "CallIntMethodA");

    jmethodID mix = (*env)->GetMethodID(env, target, "mix", "(ZBCSIJFDLjava/lang/String;)J");
    jstring abc = (*env)->NewStringUTF(env, "abc");
    jlong expect = 1 + (-3) + 0xe9 + (-300) + 100000 + (1LL << 40) + 25 + 314 + 3;
    jlong got = (*env)->CallLongMethod(env, o, mix, JNI_TRUE, (jbyte)-3, (jchar)0xe9, (jshort)-300, 100000, 1LL << 40, 2.5f, 3.14, abc);
    CHECK(got == expect, "CallLongMethod, every kind (va_list): %lld, expected %lld", got, expect);
    jvalue mv[9];
    mv[0].z = JNI_TRUE; mv[1].b = -3; mv[2].c = 0xe9; mv[3].s = -300; mv[4].i = 100000;
    mv[5].j = 1LL << 40; mv[6].f = 2.5f; mv[7].d = 3.14; mv[8].l = abc;
    got = (*env)->CallLongMethodA(env, o, mix, mv);
    CHECK(got == expect, "CallLongMethodA, every kind (jvalue): %lld", got);
    got = callV(env, o, mix, JNI_TRUE, (jbyte)-3, (jchar)0xe9, (jshort)-300, 100000, 1LL << 40, 2.5f, 3.14, abc);
    CHECK(got == expect, "CallLongMethodV, every kind: %lld", got);
    jmethodID half = (*env)->GetMethodID(env, target, "half", "(FD)D");
    CHECK((*env)->CallDoubleMethod(env, o, half, 3.0f, 0.25) == 1.75, "CallDoubleMethod");

    jmethodID hello = (*env)->GetStaticMethodID(env, target, "hello", "(Ljava/lang/String;)Ljava/lang/String;");
    CHECK(strcmp(text(env, (*env)->CallStaticObjectMethod(env, target, hello, abc), b1, sizeof b1), "hello, abc") == 0, "CallStaticObjectMethod: %s", b1);
    CHECK((*env)->GetMethodID(env, target, "hello", "(Ljava/lang/String;)Ljava/lang/String;") == NULL, "GetMethodID of a static method");
    CHECK(strncmp(pending(env, b1, sizeof b1), "java.lang.NoSuchMethodError", 27) == 0, "NoSuchMethodError: %s", b1);
    CHECK((*env)->GetMethodID(env, target, "add", "(JJ)J") == NULL, "GetMethodID, wrong descriptor");
    (*env)->ExceptionClear(env);

    jmethodID nameM = (*env)->GetMethodID(env, base, "name", "()Ljava/lang/String;");
    CHECK(strcmp(text(env, (*env)->CallObjectMethod(env, o, nameM), b1, sizeof b1), "target") == 0, "virtual call: %s", b1);
    CHECK(strcmp(text(env, (*env)->CallNonvirtualObjectMethod(env, o, base, nameM), b1, sizeof b1), "base") == 0, "CallNonvirtualObjectMethod: %s", b1);
    jmethodID hidden = (*env)->GetMethodID(env, target, "hidden", "()Ljava/lang/String;");
    CHECK(strcmp(text(env, (*env)->CallObjectMethod(env, o, hidden), b1, sizeof b1), "hidden") == 0, "private method: %s", b1);
    jmethodID toString = (*env)->GetMethodID(env, object, "toString", "()Ljava/lang/String;");
    CHECK(strncmp(text(env, (*env)->CallObjectMethod(env, o, toString), b1, sizeof b1), "jnitest.JniTarget@", 18) == 0, "inherited method: %s", b1);

    /* a public method of a class Java does not let us reach (java.util.ImmutableCollections$ListItr) */
    jmethodID iterM = (*env)->GetStaticMethodID(env, target, "iter", "()Ljava/util/Iterator;");
    jobject it = (*env)->CallStaticObjectMethod(env, target, iterM);
    jclass itc = (*env)->GetObjectClass(env, it);
    jmethodID hasNext = (*env)->GetMethodID(env, itc, "hasNext", "()Z");
    jmethodID next = (*env)->GetMethodID(env, itc, "next", "()Ljava/lang/Object;");
    NO_EXCEPTION(env, "GetMethodID on an inaccessible class");
    int count = 0;
    while ((*env)->CallBooleanMethod(env, it, hasNext) && count < 10)
    {
        (*env)->CallObjectMethod(env, it, next);
        count++;
    }
    NO_EXCEPTION(env, "calls on an inaccessible class");
    CHECK(count == 2, "iterator through its own class: %d", count);

    /* exceptions */
    jmethodID boom = (*env)->GetStaticMethodID(env, target, "boom", "(Ljava/lang/String;)V");
    (*env)->CallStaticVoidMethod(env, target, boom, abc);
    CHECK((*env)->ExceptionCheck(env), "exception pending after a throw");
    CHECK(strcmp(pending(env, b1, sizeof b1), "java.lang.IllegalStateException: abc") == 0, "ExceptionOccurred: %s", b1);
    CHECK(!(*env)->ExceptionCheck(env), "ExceptionClear");
    jclass rte = (*env)->FindClass(env, "java/lang/RuntimeException");
    CHECK((*env)->ThrowNew(env, rte, "thrown from C") == 0, "ThrowNew");
    CHECK(strcmp(pending(env, b1, sizeof b1), "java.lang.RuntimeException: thrown from C") == 0, "ThrowNew: %s", b1);
    jthrowable t = (jthrowable)(*env)->NewObject(env, rte, (*env)->GetMethodID(env, rte, "<init>", "(Ljava/lang/String;)V"), abc);
    CHECK((*env)->Throw(env, t) == 0, "Throw");
    CHECK(strcmp(pending(env, b1, sizeof b1), "java.lang.RuntimeException: abc") == 0, "Throw: %s", b1);
    (*env)->CallIntMethod(env, NULL, add, 1, 2);
    CHECK(strncmp(pending(env, b1, sizeof b1), "java.lang.NullPointerException", 30) == 0, "call on NULL: %s", b1);

    /* fields */
    jfieldID fz = (*env)->GetFieldID(env, target, "z", "Z"), fb = (*env)->GetFieldID(env, target, "b", "B");
    jfieldID fc = (*env)->GetFieldID(env, target, "c", "C"), fs = (*env)->GetFieldID(env, target, "s", "S");
    jfieldID fj = (*env)->GetFieldID(env, target, "j", "J"), ff = (*env)->GetFieldID(env, target, "f", "F");
    jfieldID fd = (*env)->GetFieldID(env, target, "d", "D");
    CHECK((*env)->GetBooleanField(env, o, fz) == JNI_TRUE && (*env)->GetByteField(env, o, fb) == -3 &&
          (*env)->GetCharField(env, o, fc) == 0xe9 && (*env)->GetShortField(env, o, fs) == -300 &&
          (*env)->GetIntField(env, o, fi) == 42 && (*env)->GetLongField(env, o, fj) == 1LL << 40 &&
          (*env)->GetFloatField(env, o, ff) == 1.5f && (*env)->GetDoubleField(env, o, fd) == 2.5, "Get<T>Field");
    (*env)->SetBooleanField(env, o, fz, JNI_FALSE); (*env)->SetByteField(env, o, fb, 100);
    (*env)->SetCharField(env, o, fc, 0x20ac); (*env)->SetShortField(env, o, fs, 30000);
    (*env)->SetIntField(env, o, fi, -1); (*env)->SetLongField(env, o, fj, -5);
    (*env)->SetFloatField(env, o, ff, -0.5f); (*env)->SetDoubleField(env, o, fd, 1e300);
    (*env)->SetObjectField(env, o, fstr, abc);
    CHECK((*env)->GetBooleanField(env, o, fz) == JNI_FALSE && (*env)->GetByteField(env, o, fb) == 100 &&
          (*env)->GetCharField(env, o, fc) == 0x20ac && (*env)->GetShortField(env, o, fs) == 30000 &&
          (*env)->GetIntField(env, o, fi) == -1 && (*env)->GetLongField(env, o, fj) == -5 &&
          (*env)->GetFloatField(env, o, ff) == -0.5f && (*env)->GetDoubleField(env, o, fd) == 1e300 &&
          (*env)->IsSameObject(env, (*env)->GetObjectField(env, o, fstr), abc), "Set<T>Field");
    jfieldID fsecret = (*env)->GetFieldID(env, target, "secret", "I");
    CHECK((*env)->GetIntField(env, o, fsecret) == 99, "private field");
    jfieldID fsc = (*env)->GetStaticFieldID(env, target, "sCount", "I");
    jfieldID fsn = (*env)->GetStaticFieldID(env, target, "sName", "Ljava/lang/String;");
    CHECK((*env)->GetStaticIntField(env, target, fsc) == 7, "GetStaticIntField");
    (*env)->SetStaticIntField(env, target, fsc, 8);
    CHECK((*env)->GetStaticIntField(env, target, fsc) == 8, "SetStaticIntField");
    CHECK(strcmp(text(env, (*env)->GetStaticObjectField(env, target, fsn), b1, sizeof b1), "static") == 0, "GetStaticObjectField");
    CHECK((*env)->GetFieldID(env, target, "nope", "I") == NULL, "GetFieldID missing");
    CHECK(strncmp(pending(env, b1, sizeof b1), "java.lang.NoSuchFieldError", 26) == 0, "NoSuchFieldError: %s", b1);
    jclass sys = (*env)->FindClass(env, "java/lang/System");
    jfieldID out = (*env)->GetStaticFieldID(env, sys, "out", "Ljava/io/PrintStream;");
    CHECK((*env)->GetStaticObjectField(env, sys, out) != NULL, "System.out");

    /* strings */
    const char *utf8 = "\xc3\xa9\xe2\x82\xac\xf0\x9f\x98\x80";   /* é € 😀, standard UTF-8 */
    jstring u = (*env)->NewStringUTF(env, utf8);
    CHECK((*env)->GetStringLength(env, u) == 4, "GetStringLength: %d", (*env)->GetStringLength(env, u));
    jmethodID codes = (*env)->GetStaticMethodID(env, target, "codes", "(Ljava/lang/String;)Ljava/lang/String;");
    CHECK(strcmp(text(env, (*env)->CallStaticObjectMethod(env, target, codes, u), b1, sizeof b1), "e9 20ac d83d de00") == 0, "NewStringUTF, four-byte form: %s", b1);
    CHECK((*env)->GetStringUTFLength(env, u) == 11, "GetStringUTFLength (modified UTF-8): %d", (*env)->GetStringUTFLength(env, u));
    const char *mu = (*env)->GetStringUTFChars(env, u, NULL);
    CHECK(strcmp(mu, "\xc3\xa9\xe2\x82\xac\xed\xa0\xbd\xed\xb8\x80") == 0, "GetStringUTFChars: modified UTF-8");
    jstring u2 = (*env)->NewStringUTF(env, mu);
    CHECK(strcmp(text(env, (*env)->CallStaticObjectMethod(env, target, codes, u2), b1, sizeof b1), "e9 20ac d83d de00") == 0, "NewStringUTF, modified form: %s", b1);
    (*env)->ReleaseStringUTFChars(env, u, mu);
    jchar nul[3] = { 'a', 0, 'b' };
    jstring sn = (*env)->NewString(env, nul, 3);
    const char *snu = (*env)->GetStringUTFChars(env, sn, NULL);
    CHECK(memcmp(snu, "a\xc0\x80" "b", 5) == 0, "NUL as C0 80");
    (*env)->ReleaseStringUTFChars(env, sn, snu);
    const jchar *jc = (*env)->GetStringChars(env, u, NULL);
    CHECK(jc[0] == 0xe9 && jc[1] == 0x20ac && jc[2] == 0xd83d && jc[3] == 0xde00, "GetStringChars");
    (*env)->ReleaseStringChars(env, u, jc);
    jchar reg[2];
    (*env)->GetStringRegion(env, u, 1, 2, reg);
    CHECK(reg[0] == 0x20ac && reg[1] == 0xd83d, "GetStringRegion");
    char ureg[16];
    (*env)->GetStringUTFRegion(env, u, 0, 2, ureg);
    CHECK(strcmp(ureg, "\xc3\xa9\xe2\x82\xac") == 0, "GetStringUTFRegion");
    (*env)->GetStringRegion(env, u, 3, 5, reg);
    CHECK(strncmp(pending(env, b1, sizeof b1), "java.lang.StringIndexOutOfBoundsException", 41) == 0, "GetStringRegion out of bounds: %s", b1);
    CHECK((*env)->NewStringUTF(env, NULL) == NULL, "NewStringUTF(NULL)");

    /* arrays */
    jmethodID squares = (*env)->GetStaticMethodID(env, target, "squares", "(I)[I");
    jintArray sq = (jintArray)(*env)->CallStaticObjectMethod(env, target, squares, 5);
    CHECK((*env)->GetArrayLength(env, sq) == 5, "GetArrayLength");
    jint *e = (*env)->GetIntArrayElements(env, sq, NULL);
    CHECK(e[0] == 0 && e[4] == 16, "GetIntArrayElements");
    e[0] = 1000;
    (*env)->ReleaseIntArrayElements(env, sq, e, JNI_ABORT);
    jmethodID sum = (*env)->GetStaticMethodID(env, target, "sum", "([I)J");
    CHECK((*env)->CallStaticLongMethod(env, target, sum, sq) == 30, "JNI_ABORT does not copy back");
    e = (*env)->GetIntArrayElements(env, sq, NULL);
    e[0] = 1000;
    (*env)->ReleaseIntArrayElements(env, sq, e, 0);
    CHECK((*env)->CallStaticLongMethod(env, target, sum, sq) == 1030, "Release copies back");
    jint r3[3] = { 1, 2, 3 };
    (*env)->SetIntArrayRegion(env, sq, 2, 3, r3);
    CHECK((*env)->CallStaticLongMethod(env, target, sum, sq) == 1007, "SetIntArrayRegion");
    (*env)->SetIntArrayRegion(env, sq, 4, 3, r3);
    CHECK(strncmp(pending(env, b1, sizeof b1), "java.lang.ArrayIndexOutOfBoundsException", 40) == 0, "region out of bounds: %s", b1);
    void *crit = (*env)->GetPrimitiveArrayCritical(env, sq, NULL);
    CHECK(((jint *)crit)[0] == 1000, "GetPrimitiveArrayCritical");
    (*env)->ReleasePrimitiveArrayCritical(env, sq, crit, 0);

    jbooleanArray za = (*env)->NewBooleanArray(env, 2); jboolean zv[2] = { JNI_TRUE, JNI_FALSE };
    (*env)->SetBooleanArrayRegion(env, za, 0, 2, zv);
    jbyteArray ba = (*env)->NewByteArray(env, 2); jbyte bv[2] = { -1, 3 };
    (*env)->SetByteArrayRegion(env, ba, 0, 2, bv);
    jcharArray ca = (*env)->NewCharArray(env, 2); jchar chv[2] = { 'x', 0x20ac };
    (*env)->SetCharArrayRegion(env, ca, 0, 2, chv);
    jshortArray sa = (*env)->NewShortArray(env, 2); jshort sv[2] = { -2, 7 };
    (*env)->SetShortArrayRegion(env, sa, 0, 2, sv);
    jlongArray la = (*env)->NewLongArray(env, 2); jlong lv[2] = { 1LL << 40, 1 };
    (*env)->SetLongArrayRegion(env, la, 0, 2, lv);
    jfloatArray fa = (*env)->NewFloatArray(env, 2); jfloat fv[2] = { 0.5f, 0.25f };
    (*env)->SetFloatArrayRegion(env, fa, 0, 2, fv);
    jdoubleArray da = (*env)->NewDoubleArray(env, 2); jdouble dv[2] = { 1.5, 2.0 };
    (*env)->SetDoubleArrayRegion(env, da, 0, 2, dv);
    jmethodID kinds = (*env)->GetStaticMethodID(env, target, "kinds", "([Z[B[C[S[J[F[D)Ljava/lang/String;");
    text(env, (*env)->CallStaticObjectMethod(env, target, kinds, za, ba, ca, sa, la, fa, da), b1, sizeof b1);
    CHECK(strcmp(b1, "truefalse 2 x\xe2\x82\xac 5 1099511627777 0.75 3.5") == 0, "every primitive array: %s", b1);
    jlong lback[2]; jdouble dback[2]; jboolean zback[2];
    (*env)->GetLongArrayRegion(env, la, 0, 2, lback);
    (*env)->GetDoubleArrayRegion(env, da, 0, 2, dback);
    (*env)->GetBooleanArrayRegion(env, za, 0, 2, zback);
    CHECK(lback[0] == 1LL << 40 && dback[1] == 2.0 && zback[0] == 1 && zback[1] == 0, "Get<T>ArrayRegion");
    jboolean *ze = (*env)->GetBooleanArrayElements(env, za, NULL);
    CHECK(ze[0] == 1 && ze[1] == 0, "GetBooleanArrayElements");
    (*env)->ReleaseBooleanArrayElements(env, za, ze, 0);
    jbyteArray empty = (*env)->NewByteArray(env, 0);
    jbyte *ee = (*env)->GetByteArrayElements(env, empty, NULL);
    CHECK(ee != NULL && (*env)->GetArrayLength(env, empty) == 0, "empty array elements");
    (*env)->ReleaseByteArrayElements(env, empty, ee, 0);

    jobjectArray oa = (*env)->NewObjectArray(env, 3, string, abc);
    (*env)->SetObjectArrayElement(env, oa, 1, u);
    jmethodID join = (*env)->GetStaticMethodID(env, target, "join", "([Ljava/lang/String;)Ljava/lang/String;");
    CHECK(strcmp(text(env, (*env)->CallStaticObjectMethod(env, target, join, oa), b1, sizeof b1), "abc,\xc3\xa9\xe2\x82\xac\xed\xa0\xbd\xed\xb8\x80,abc") == 0, "NewObjectArray, SetObjectArrayElement: %s", b1);
    CHECK((*env)->IsSameObject(env, (*env)->GetObjectArrayElement(env, oa, 2), abc), "GetObjectArrayElement");
    (*env)->SetObjectArrayElement(env, oa, 0, o);
    CHECK(strncmp(pending(env, b1, sizeof b1), "java.lang.ArrayStoreException", 29) == 0, "ArrayStoreException: %s", b1);
    (*env)->GetObjectArrayElement(env, oa, 3);
    CHECK(strncmp(pending(env, b1, sizeof b1), "java.lang.ArrayIndexOutOfBoundsException", 40) == 0, "object array index: %s", b1);

    /* references and frames */
    jobject g = (*env)->NewGlobalRef(env, o);
    jweak w = (*env)->NewWeakGlobalRef(env, o);
    CHECK((*env)->GetObjectRefType(env, w) == JNIWeakGlobalRefType, "weak ref type");
    CHECK((*env)->IsSameObject(env, w, o) && !(*env)->IsSameObject(env, w, NULL), "weak ref while referenced");
    jobject l2 = (*env)->NewLocalRef(env, g);
    CHECK((*env)->IsSameObject(env, l2, o) && (*env)->GetObjectRefType(env, l2) == JNILocalRefType, "NewLocalRef");
    (*env)->DeleteGlobalRef(env, g);
    (*env)->DeleteWeakGlobalRef(env, w);
    CHECK((*env)->EnsureLocalCapacity(env, 100) == JNI_OK, "EnsureLocalCapacity");
    CHECK((*env)->PushLocalFrame(env, 16) == JNI_OK, "PushLocalFrame");
    jobject inner = NULL;
    for (int k = 0; k < 100; k++) inner = (*env)->NewStringUTF(env, "in a frame");
    jobject kept = (*env)->PopLocalFrame(env, inner);
    CHECK(strcmp(text(env, kept, b1, sizeof b1), "in a frame") == 0, "PopLocalFrame keeps the result");
    (*env)->PushLocalFrame(env, 4);
    inner = (*env)->NewStringUTF(env, "dropped");
    CHECK((*env)->PopLocalFrame(env, NULL) == NULL, "PopLocalFrame(NULL)");
    CHECK((*env)->GetObjectRefType(env, inner) == JNIInvalidRefType, "PopLocalFrame frees the frame");

    /* monitors */
    CHECK((*env)->MonitorEnter(env, o) == JNI_OK && (*env)->MonitorEnter(env, o) == JNI_OK, "MonitorEnter, reentrant");
    CHECK((*env)->MonitorExit(env, o) == JNI_OK && (*env)->MonitorExit(env, o) == JNI_OK, "MonitorExit");
    CHECK((*env)->MonitorExit(env, o) == JNI_ERR, "MonitorExit not held");
    (*env)->ExceptionClear(env);

    /* threads: each attaches, works, detaches */
    enum { NT = 8 };
    pthread_t th[NT];
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 1 << 20);
    double t0 = now();
    for (int k = 0; k < NT; k++) pthread_create(&th[k], &attr, worker, (void *)(long)k);
    long bad = 0;
    for (int k = 0; k < NT; k++) { void *r; pthread_join(th[k], &r); bad += (long)r; }
    double t1 = now();
    CHECK(bad == 0, "threads: %ld wrong results", bad);
    printf("threads: %d x 2000 iterations (5 JNI calls each) in %.2f s\n", NT, t1 - t0);

    /* cost of a call */
    jmethodID addS = (*env)->GetMethodID(env, target, "add", "(II)I");
    int N = 200000;
    jint acc = 0;
    t0 = now();
    for (int k = 0; k < N; k++) acc += (*env)->CallIntMethod(env, o, addS, k, 1);
    t1 = now();
    CHECK(acc != 0, "loop");
    printf("CallIntMethod: %.2f us per call\n", (t1 - t0) * 1e6 / N);
    t0 = now();
    for (int k = 0; k < N; k++)
    {
        jstring x = (*env)->NewStringUTF(env, "abc");
        (*env)->DeleteLocalRef(env, x);
    }
    t1 = now();
    printf("NewStringUTF + DeleteLocalRef: %.2f us per pair\n", (t1 - t0) * 1e6 / N);

    CHECK((*vm)->DetachCurrentThread(vm) == JNI_OK, "DetachCurrentThread");
    CHECK((*vm)->GetEnv(vm, (void **)&env2, JNI_VERSION_1_8) == JNI_EDETACHED, "GetEnv after detaching");
    (void)b2;

    printf("%d checks, %d failed\n", checks, failures);
    return failures;
}
