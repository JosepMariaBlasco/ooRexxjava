/*
 * The host functions of oorexxwasm's SysWasmHost.hpp (weak symbols in the
 * interpreter), defined for the JVM: each one calls a function the Java
 * launcher gives the module (import module "rexxjvm", host/.../RunWasm.java).
 *
 * External commands run as processes of the JVM's host (ProcessBuilder);
 * .RexxInfo~executable and ~libraryPath come from the launcher.
 * Below them, what the WASI patch adds: the real process ids, and the
 * connections to the Rexx API server shared between processes.
 * The contract (arguments, flags, return values) is SysWasmHost.hpp's.
 */

#include <stddef.h>
#include <sys/types.h>

#define RXJ_IMPORT(name) __attribute__((import_module("rexxjvm"), import_name(#name)))

RXJ_IMPORT(command_run)
int rxj_command_run(char **argv, char **envp, const char *cwd,
                    const char *input, int inputLength, int flags);
RXJ_IMPORT(command_result_length)
int rxj_command_result_length(int which);
RXJ_IMPORT(command_result_copy)
void rxj_command_result_copy(int which, char *buf);
RXJ_IMPORT(executable_path)
int rxj_executable_path(char *buf, int size);
RXJ_IMPORT(library_directory)
int rxj_library_directory(char *buf, int size);
RXJ_IMPORT(process_id)
int rxj_process_id(int parent);
RXJ_IMPORT(api_available)
int rxj_api_available(void);
RXJ_IMPORT(api_connect)
int rxj_api_connect(const char *service);
RXJ_IMPORT(api_listen)
int rxj_api_listen(const char *service);
RXJ_IMPORT(api_accept)
int rxj_api_accept(int handle);
RXJ_IMPORT(api_read)
int rxj_api_read(int handle, void *buf, int size);
RXJ_IMPORT(api_write)
int rxj_api_write(int handle, const void *buf, int size);
RXJ_IMPORT(api_close)
void rxj_api_close(int handle);
RXJ_IMPORT(api_start_server)
int rxj_api_start_server(void);
RXJ_IMPORT(signal_wait)
int rxj_signal_wait(void);

int orxWasmCommandRun(char **argv, char **envp, const char *cwd,
                      const char *input, int inputLength, int flags)
{
    return rxj_command_run(argv, envp, cwd, input, inputLength, flags);
}

int orxWasmCommandResultLength(int which)
{
    return rxj_command_result_length(which);
}

void orxWasmCommandResultCopy(int which, char *buf)
{
    rxj_command_result_copy(which, buf);
}

int orxWasmExecutablePath(char *buf, int size)
{
    return rxj_executable_path(buf, size);
}

int orxWasmLibraryDirectory(char *buf, int size)
{
    return rxj_library_directory(buf, size);
}

/* The real process ids (libwasi-emulated-getpid's getpid() is a constant:
   every process would be the same session of the shared API server). */
pid_t getpid(void)
{
    static pid_t pid = 0;
    if (pid == 0)
    {
        pid = (pid_t)rxj_process_id(0);
    }
    return pid;
}

pid_t getppid(void)
{
    return (pid_t)rxj_process_id(1);
}

/* The shared Rexx API server's connections (the WASI patch's
   SysHostConnection.hpp), over the launcher's Unix domain sockets. */
int orxWasiApiAvailable(void) { return rxj_api_available(); }
int orxWasiApiConnect(const char *service) { return rxj_api_connect(service); }
int orxWasiApiListen(const char *service) { return rxj_api_listen(service); }
int orxWasiApiAccept(int handle) { return rxj_api_accept(handle); }
long orxWasiApiRead(int handle, void *buf, size_t size)
{
    return rxj_api_read(handle, buf, size > 0x40000000 ? 0x40000000 : (int)size);
}
long orxWasiApiWrite(int handle, const void *buf, size_t size)
{
    return rxj_api_write(handle, buf, (int)size);
}
void orxWasiApiClose(int handle) { rxj_api_close(handle); }
int orxWasiApiStartServer(void) { return rxj_api_start_server(); }

/* The host's signals (SystemInterpreter.cpp's hook, called when the
   interpreter starts): a thread that waits in the launcher for the next
   SIGINT, SIGTERM or SIGHUP it gets and posts it as natively, with the
   interpreter's orxWasmSignal() (a HALT in every activity, sleeps ended).
   Calling the export from a thread of the JVM's own would have no stack or
   thread-local storage of its own in the module: this is a thread of the
   program. rxj_signal_wait() returns -1 when the launcher gives no signals
   (a tool, or a JVM without sun.misc.Signal): the thread ends. */
#include <pthread.h>
int orxWasmSignal(int sig);

static void *orx_wasi_signal_thread(void *unused)
{
    (void)unused;
    for (;;)
    {
        int sig = rxj_signal_wait();
        if (sig < 0)
        {
            return NULL;
        }
        orxWasmSignal(sig);
    }
}

void orxWasiStartSignals(void)
{
    static int started = 0;
    if (started)
    {
        return;
    }
    started = 1;
    pthread_t t;
    if (pthread_create(&t, NULL, orx_wasi_signal_thread, NULL) == 0)
    {
        pthread_detach(t);
    }
}
