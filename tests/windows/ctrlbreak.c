/*
 * ctrlbreak MS COMMAND-LINE: runs the command in a process group of its own, sends it
 * Ctrl+Break after MS milliseconds, and exits with its exit code (tests/windows/run.ps1:
 * rexxjs.exe sends Ctrl+Break on as SIGINT, a HALT). Ctrl+C cannot be sent to one
 * process group alone; Ctrl+Break can.
 *   x86_64-w64-mingw32-gcc -O2 -municode -o ctrlbreak.exe ctrlbreak.c -static
 */
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

int wmain(int argc, wchar_t **argv) {
    if (argc < 3) { fputs("usage: ctrlbreak MS COMMAND-LINE\n", stderr); return 2; }
    DWORD ms = (DWORD)_wtoi(argv[1]);
    /* the command line after MS, as it was given */
    const wchar_t *c = GetCommandLineW();
    for (int skip = 0; skip < 2; skip++) {
        int q = 0;
        while (*c && (q || (*c != L' ' && *c != L'\t'))) { if (*c == L'"') q = !q; c++; }
        while (*c == L' ' || *c == L'\t') c++;
    }
    wchar_t *cl = _wcsdup(c);
    STARTUPINFOW si;
    memset(&si, 0, sizeof si);
    si.cb = sizeof si;
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(NULL, cl, NULL, NULL, TRUE, CREATE_NEW_PROCESS_GROUP, NULL, NULL, &si, &pi)) {
        fprintf(stderr, "ctrlbreak: cannot start it (%lu)\n", (unsigned long)GetLastError());
        return 127;
    }
    SetConsoleCtrlHandler(NULL, TRUE);       /* not for us */
    if (WaitForSingleObject(pi.hProcess, ms) == WAIT_TIMEOUT) {
        GenerateConsoleCtrlEvent(CTRL_BREAK_EVENT, pi.dwProcessId);
        if (WaitForSingleObject(pi.hProcess, 30000) == WAIT_TIMEOUT) {
            fputs("ctrlbreak: still running 30 s after Ctrl+Break\n", stderr);
            TerminateProcess(pi.hProcess, 124);
        }
    }
    DWORD rc = 127;
    GetExitCodeProcess(pi.hProcess, &rc);
    return (int)rc;
}
