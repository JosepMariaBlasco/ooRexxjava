/*
 * The host functions of oorexxwasm's SysWasmHost.hpp (weak symbols in the
 * interpreter), defined for the JVM: each one calls a function the Java
 * launcher gives the module (import module "rexxjvm", host/.../RunWasm.java).
 *
 * External commands run as processes of the JVM's host (ProcessBuilder);
 * .RexxInfo~executable and ~libraryPath come from the launcher.
 * The contract (arguments, flags, return values) is SysWasmHost.hpp's.
 */

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
