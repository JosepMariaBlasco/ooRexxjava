#include <cstdio>
#include <stdexcept>
#include <pthread.h>
#include <unistd.h>
static pthread_mutex_t m = PTHREAD_MUTEX_INITIALIZER;
static int counter = 0;
void *worker(void *arg) {
    for (int i = 0; i < 100000; i++) { pthread_mutex_lock(&m); counter++; pthread_mutex_unlock(&m); }
    try { throw std::runtime_error("from thread"); }
    catch (const std::exception &e) { printf("caught in thread %ld: %s\n", (long)arg, e.what()); }
    return nullptr;
}
int main() {
    try { throw std::runtime_error("main"); } catch (const std::exception &e) { printf("caught: %s\n", e.what()); }
    pthread_t t[3];
    for (long i = 0; i < 3; i++) pthread_create(&t[i], nullptr, worker, (void*)i);
    for (int i = 0; i < 3; i++) pthread_join(t[i], nullptr);
    printf("counter = %d (expect 300000)\n", counter);
    FILE *f = fopen("out.txt", "w"); fprintf(f, "file ok\n"); fclose(f);
    return 0;
}
