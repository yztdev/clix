/*
 * CLIX Windows console launcher.
 * The console handler only increments an atomic cancel count / signals an event.
 * A worker delivers one byte per Ctrl+C over an overlapped named pipe to Java.
 * Console close/logoff/shutdown is handled by the ordinary main thread.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <strsafe.h>

#define CLIX_PIPE_PREFIX L"\\\\.\\pipe\\clix-cancel-"
#define CLIX_MAX_CMD 32767

static HANDLE g_cancelEvent = NULL;
static HANDLE g_stopEvent = NULL;
static HANDLE g_shutdownEvent = NULL;
static HANDLE g_pipe = INVALID_HANDLE_VALUE;
static volatile LONG g_pendingCancels = 0;

static BOOL WINAPI ctrlHandler(DWORD ctrlType) {
    if (ctrlType == CTRL_C_EVENT || ctrlType == CTRL_BREAK_EVENT) {
        InterlockedIncrement(&g_pendingCancels);
        return g_cancelEvent != NULL && SetEvent(g_cancelEvent);
    }
    if (ctrlType == CTRL_CLOSE_EVENT || ctrlType == CTRL_LOGOFF_EVENT || ctrlType == CTRL_SHUTDOWN_EVENT) {
        /* No blocking cleanup, process termination, or allocation in handler. */
        return g_shutdownEvent != NULL && SetEvent(g_shutdownEvent);
    }
    return FALSE;
}

/* Windows CommandLineToArgvW-compatible quoting; returns FALSE on overflow. */
static BOOL appendArg(wchar_t *dst, size_t capacity, const wchar_t *arg) {
    size_t index = wcslen(dst), count = 0;
    if (index + 3 >= capacity) return FALSE;
    dst[index++] = L'"';
    for (const wchar_t *p = arg; ; ++p) {
        if (*p == L'\\') { ++count; continue; }
        if (*p == L'"') {
            if (index + count * 2 + 2 >= capacity) return FALSE;
            while (count--) dst[index++] = L'\\';
            count = 0;
            dst[index++] = L'\\'; dst[index++] = L'"';
        } else if (*p == L'\0') {
            if (index + count * 2 + 2 >= capacity) return FALSE;
            while (count--) dst[index++] = L'\\';
            break;
        } else {
            if (index + count + 2 >= capacity) return FALSE;
            while (count--) dst[index++] = L'\\';
            count = 0;
            dst[index++] = *p;
        }
    }
    dst[index++] = L'"'; dst[index] = L'\0';
    return TRUE;
}

static BOOL appendSpaceArg(wchar_t *dst, size_t capacity, const wchar_t *arg) {
    if (FAILED(StringCchCatW(dst, capacity, L" "))) return FALSE;
    return appendArg(dst, capacity, arg);
}

/* Each write is a proper OVERLAPPED operation, cancellable by the stop event. */
static BOOL sendCancelByte(void) {
    OVERLAPPED ov;
    DWORD written = 0;
    char byte = 'C';
    ZeroMemory(&ov, sizeof(ov));
    ov.hEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (ov.hEvent == NULL) return FALSE;
    BOOL ok = WriteFile(g_pipe, &byte, 1, &written, &ov);
    if (!ok && GetLastError() == ERROR_IO_PENDING) {
        HANDLE events[2] = {ov.hEvent, g_stopEvent};
        DWORD result = WaitForMultipleObjects(2, events, FALSE, INFINITE);
        if (result == WAIT_OBJECT_0) ok = GetOverlappedResult(g_pipe, &ov, &written, FALSE);
        else {
            CancelIoEx(g_pipe, &ov);
            /* OVERLAPPED memory must remain valid until the I/O actually completes. */
            GetOverlappedResult(g_pipe, &ov, &written, TRUE);
            ok = FALSE;
        }
    }
    CloseHandle(ov.hEvent);
    return ok && written == 1;
}

static DWORD WINAPI cancelWorker(LPVOID unused) {
    (void)unused;
    OVERLAPPED ov;
    ZeroMemory(&ov, sizeof(ov));
    ov.hEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (ov.hEvent == NULL) return 1;
    BOOL connected = ConnectNamedPipe(g_pipe, &ov);
    if (!connected) {
        DWORD err = GetLastError();
        if (err == ERROR_PIPE_CONNECTED) connected = TRUE;
        else if (err == ERROR_IO_PENDING) {
            HANDLE events[2] = {ov.hEvent, g_stopEvent};
            DWORD wr = WaitForMultipleObjects(2, events, FALSE, INFINITE);
            if (wr == WAIT_OBJECT_0) {
                DWORD bytes = 0;
                connected = GetOverlappedResult(g_pipe, &ov, &bytes, FALSE);
            } else {
                CancelIoEx(g_pipe, &ov);
                DWORD bytes = 0;
                GetOverlappedResult(g_pipe, &ov, &bytes, TRUE);
            }
        }
    }
    CloseHandle(ov.hEvent);
    if (!connected) return 1;

    HANDLE events[2] = {g_cancelEvent, g_stopEvent};
    for (;;) {
        DWORD wr = WaitForMultipleObjects(2, events, FALSE, INFINITE);
        if (wr != WAIT_OBJECT_0 || WaitForSingleObject(g_stopEvent, 0) == WAIT_OBJECT_0) break;
        /* InterlockedExchange snapshots all queued events; no ResetEvent race. */
        LONG batch = InterlockedExchange(&g_pendingCancels, 0);
        for (LONG i = 0; i < batch; ++i) {
            if (WaitForSingleObject(g_stopEvent, 0) == WAIT_OBJECT_0) break;
            if (!sendCancelByte()) return 1;
        }
    }
    return 0;
}

static BOOL buildCommandLine(wchar_t *out, size_t capacity, const wchar_t *jar, int argc, wchar_t **argv) {
    out[0] = L'\0';
    if (!appendArg(out, capacity, L"java") || !appendSpaceArg(out, capacity, L"-jar") ||
        !appendSpaceArg(out, capacity, jar)) return FALSE;
    if (argc <= 1) return appendSpaceArg(out, capacity, L"remote");
    for (int i = 1; i < argc; ++i) {
        if (!appendSpaceArg(out, capacity, argv[i])) return FALSE;
    }
    return TRUE;
}

int wmain(int argc, wchar_t **argv) {
    int result = 1;
    wchar_t exeDir[MAX_PATH], jarPath[MAX_PATH], pipeName[128];
    wchar_t *cmdLine = NULL;
    HANDLE worker = NULL;
    PROCESS_INFORMATION pi;
    ZeroMemory(&pi, sizeof(pi));
    if (!GetModuleFileNameW(NULL, exeDir, MAX_PATH)) goto cleanup;
    wchar_t *slash = wcsrchr(exeDir, L'\\');
    if (slash == NULL) goto cleanup;
    *slash = L'\0';
    if (FAILED(StringCchPrintfW(jarPath, MAX_PATH, L"%s\\clix.jar", exeDir))) goto cleanup;
    if (GetFileAttributesW(jarPath) == INVALID_FILE_ATTRIBUTES) {
        fwprintf(stderr, L"clix.jar not found beside clix.exe\n");
        goto cleanup;
    }
    unsigned int nonce = 0;
    if (rand_s(&nonce) != 0) {
        fwprintf(stderr, L"secure pipe name generation failed\n");
        goto cleanup;
    }
    if (FAILED(StringCchPrintfW(pipeName, 128, L"%s%08lx-%08x", CLIX_PIPE_PREFIX,
                                 GetCurrentProcessId(), nonce))) goto cleanup;
    g_pipe = CreateNamedPipeW(pipeName, PIPE_ACCESS_OUTBOUND | FILE_FLAG_OVERLAPPED,
        PIPE_TYPE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS, 1, 0, 0, 0, NULL);
    if (g_pipe == INVALID_HANDLE_VALUE) goto cleanup;
    if (!SetEnvironmentVariableW(L"CLIX_CANCEL_PIPE", pipeName)) goto cleanup;
    g_cancelEvent = CreateEventW(NULL, FALSE, FALSE, NULL);
    g_stopEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    g_shutdownEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!g_cancelEvent || !g_stopEvent || !g_shutdownEvent) goto cleanup;
    cmdLine = (wchar_t *)calloc(CLIX_MAX_CMD, sizeof(wchar_t));
    if (!cmdLine || !buildCommandLine(cmdLine, CLIX_MAX_CMD, jarPath, argc, argv)) {
        fwprintf(stderr, L"command line too long\n");
        goto cleanup;
    }
    STARTUPINFOW si;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    si.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    si.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    /* Install before starting JVM so the cancellation handler is already active. */
    if (!SetConsoleCtrlHandler(ctrlHandler, TRUE)) goto cleanup;
    if (!CreateProcessW(NULL, cmdLine, NULL, NULL, TRUE,
            CREATE_NEW_PROCESS_GROUP | CREATE_UNICODE_ENVIRONMENT,
            NULL, NULL, &si, &pi)) {
        fwprintf(stderr, L"failed to start Java (ensure java is on PATH)\n");
        goto cleanup;
    }
    worker = CreateThread(NULL, 0, cancelWorker, NULL, 0, NULL);
    if (!worker) goto cleanup;
    HANDLE wait[2] = {pi.hProcess, g_shutdownEvent};
    DWORD wr = WaitForMultipleObjects(2, wait, FALSE, INFINITE);
    if (wr == WAIT_OBJECT_0 + 1) {
        /* Normal worker thread handles shutdown; never block in handler. */
        TerminateProcess(pi.hProcess, 1);
        WaitForSingleObject(pi.hProcess, 2000);
    }
    if (wr == WAIT_OBJECT_0 || wr == WAIT_OBJECT_0 + 1) {
        DWORD code = 1;
        if (GetExitCodeProcess(pi.hProcess, &code)) result = (int)code;
    }
cleanup:
    if (g_stopEvent) SetEvent(g_stopEvent);
    if (g_pipe != INVALID_HANDLE_VALUE) CancelIoEx(g_pipe, NULL);
    if (worker) { WaitForSingleObject(worker, 2000); CloseHandle(worker); }
    if (pi.hProcess) {
        if (WaitForSingleObject(pi.hProcess, 0) != WAIT_OBJECT_0) TerminateProcess(pi.hProcess, 1);
        CloseHandle(pi.hProcess);
    }
    if (pi.hThread) CloseHandle(pi.hThread);
    if (g_pipe != INVALID_HANDLE_VALUE) CloseHandle(g_pipe);
    if (g_cancelEvent) CloseHandle(g_cancelEvent);
    if (g_stopEvent) CloseHandle(g_stopEvent);
    if (g_shutdownEvent) CloseHandle(g_shutdownEvent);
    free(cmdLine);
    return result;
}
