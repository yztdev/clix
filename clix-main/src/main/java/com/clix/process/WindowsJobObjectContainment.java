package com.clix.process;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Strong containment for native Windows commands (design doc "Windows 原生命令").
 *
 * <p>A Job Object with KILL_ON_JOB_CLOSE and breakaway *disabled* is created before the
 * process. The process is created with CREATE_SUSPENDED, assigned to the Job, and only then
 * resumed, so there is no "started but not yet in the Job" race. stdout/stderr go to a shared
 * temp file through STARTUPINFO handles.</p>
 *
 * <p>External agents (services, WMI, task scheduler, container runtimes) are outside this
 * domain by definition; they are recorded as possible external side effects, never claimed as
 * cleaned up. WSL's Linux processes are always governed by the cgroup path, not by this Job.</p>
 */
public final class WindowsJobObjectContainment implements ProcessContainment {

    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_BASIC_PROCESS_ID_LIST = 3;
    private static final int LIMIT_FLAGS_OFFSET = 16; // same on x86 and x64
    private static final int EXTENDED_LIMIT_SIZE = 128;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_SHARE_READ_WRITE = 0x00000003;
    private static final int CREATE_ALWAYS = 2;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;
    private static final int CTRL_BREAK_EVENT = 1;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int HANDLE_FLAG_INHERIT = 0x00000001;

    public record NativeHandles(WinNT.HANDLE job, WinNT.HANDLE process, WinNT.HANDLE thread,
                                WinNT.HANDLE output, String nonce, int pid) { }

    interface Kernel32Ext extends Library {
        WinNT.HANDLE CreateJobObjectW(Pointer attributes, String name);
        boolean SetInformationJobObject(WinNT.HANDLE job, int infoClass, Pointer info, int length);
        boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);
        boolean TerminateJobObject(WinNT.HANDLE job, int exitCode);
        boolean QueryInformationJobObject(WinNT.HANDLE job, int infoClass, Pointer info,
                                          int length, IntByReference returned);
        boolean TerminateProcess(WinNT.HANDLE process, int exitCode);
        int ResumeThread(WinNT.HANDLE thread);
        boolean CreateProcessW(String application, char[] commandLine, Pointer processAttributes,
                               Pointer threadAttributes, boolean inheritHandles,
                               WinDef.DWORD creationFlags, Pointer environment, String currentDirectory,
                               WinBase.STARTUPINFO startupInfo, WinBase.PROCESS_INFORMATION processInfo);
        WinNT.HANDLE CreateFileW(String name, int desiredAccess, int shareMode, Pointer security,
                                 int creationDisposition, int flags, WinNT.HANDLE template);
        boolean GenerateConsoleCtrlEvent(int ctrlEvent, int processGroupId);
        boolean GetExitCodeProcess(WinNT.HANDLE process, IntByReference exitCode);
        int WaitForSingleObject(WinNT.HANDLE handle, int millis);
        boolean CloseHandle(WinNT.HANDLE handle);
        boolean SetHandleInformation(WinNT.HANDLE handle, int mask, int flags);
        int GetLastError();
    }

    private static final Kernel32Ext API = load();

    private static Kernel32Ext load() {
        try {
            return Native.load("kernel32", Kernel32Ext.class, W32APIOptions.UNICODE_OPTIONS);
        } catch (Throwable t) {
            return null;
        }
    }

    private final ProcessIdentityVerifier verifier;
    private final Map<String, Path> outputs = new java.util.concurrent.ConcurrentHashMap<>();

    public WindowsJobObjectContainment() {
        this.verifier = new ProcessIdentityVerifier(ProcReaders.windows(), false);
    }

    @Override public ContainmentLevel level() { return ContainmentLevel.JOB_OBJECT; }

    @Override public boolean available() {
        return API != null && System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    @Override public String limitation() { return null; }

    @Override public List<String> wrapCommand(List<String> baseArguments, StartRequest request) {
        if (baseArguments == null || baseArguments.isEmpty() || baseArguments.size() > 1) {
            return baseArguments; // a sandbox invocation is already a complete argv vector
        }
        String shell = request.shellExecutable();
        if (shell == null || shell.isBlank()) shell = "powershell.exe";
        String executable = Path.of(shell).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String command = baseArguments.get(0);
        if (executable.equals("pwsh.exe") || executable.equals("powershell.exe")) {
            return List.of(shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command);
        }
        if (executable.equals("cmd.exe")) {
            return List.of(shell, "/d", "/s", "/c", command);
        }
        return List.of(shell, "-c", command);
    }

    @Override public ContainedProcess start(StartRequest request) throws IOException {
        if (!available()) throw new IOException("Windows Job Object 能力不可用");
        if (request.baseArguments() == null || request.baseArguments().isEmpty()) {
            throw new IOException("命令行为空");
        }
        Path output = Files.createTempFile("clix-job-", ".out");
        WinNT.HANDLE hOutput = API.CreateFileW(output.toString(), GENERIC_WRITE,
                FILE_SHARE_READ_WRITE, null, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, null);
        if (isInvalid(hOutput)) {
            delete(output);
            throw new IOException("无法创建 Job Object 输出句柄: " + API.GetLastError());
        }
        if (!API.SetHandleInformation(hOutput, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT)) {
            int error = API.GetLastError();
            API.CloseHandle(hOutput);
            delete(output);
            throw new IOException("无法将输出句柄设置为可继承: " + error);
        }
        WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
        startup.cb = new WinDef.DWORD(startup.size());
        startup.dwFlags = WinBase.STARTF_USESTDHANDLES;
        startup.hStdOutput = hOutput;
        startup.hStdError = hOutput;
        WinBase.PROCESS_INFORMATION processInfo = new WinBase.PROCESS_INFORMATION();

        int flags = WinBase.CREATE_SUSPENDED | WinBase.CREATE_NEW_PROCESS_GROUP
                | WinBase.CREATE_UNICODE_ENVIRONMENT | 0x08000000 /* CREATE_NO_WINDOW */;
        Memory environment = unicodeEnvironment(request);
        String workingDirectory = request.workingDirectory() == null
                ? null : request.workingDirectory().toString();
        List<String> launchArguments = wrapCommand(request.baseArguments(), request);
        if (launchArguments == null || launchArguments.isEmpty()) {
            API.CloseHandle(hOutput);
            delete(output);
            throw new IOException("命令行为空");
        }
        char[] commandLine = joinCommandLine(launchArguments).toCharArray();
        boolean created = API.CreateProcessW(null, commandLine, null, null, true,
                new WinDef.DWORD(flags), environment, workingDirectory, startup, processInfo);
        if (!created) {
            int error = API.GetLastError();
            API.CloseHandle(hOutput);
            delete(output);
            throw new IOException("CreateProcessW 失败: " + error);
        }

        WinNT.HANDLE job = API.CreateJobObjectW(null, "clix-op-" + ProcessHandle.current().pid()
                + "-" + fullId(request.operationId()));
        if (isInvalid(job)) {
            cleanupFailedStart(processInfo, hOutput, output);
            throw new IOException("CreateJobObjectW 失败: " + API.GetLastError());
        }
        Memory limits = new Memory(EXTENDED_LIMIT_SIZE);
        limits.clear();
        // Breakaway flags are deliberately NOT set: the process tree cannot leave the Job.
        limits.setInt(LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
        if (!API.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                limits, EXTENDED_LIMIT_SIZE)
                || !API.AssignProcessToJobObject(job, processInfo.hProcess)) {
            int error = API.GetLastError();
            API.CloseHandle(job);
            cleanupFailedStart(processInfo, hOutput, output);
            throw new IOException("加入 Job Object 失败: " + error);
        }
        // Only now may the process run: no "started but not yet contained" window exists.
        API.ResumeThread(processInfo.hThread);

        int pid = processInfo.dwProcessId.intValue();
        outputs.put(request.operationId(), output);
        NativeHandles handles = new NativeHandles(job, processInfo.hProcess,
                processInfo.hThread, hOutput, request.nonce(), pid);
        return new ContainedProcess(request.operationId(), request.nonce(), pid, -1, -1, -1,
                null, null, handles, null, output, level(), "windows-job-object", System.nanoTime());
    }

    private void cleanupFailedStart(WinBase.PROCESS_INFORMATION pi, WinNT.HANDLE hOutput, Path output) {
        // The process is still suspended; never resume a process that is not contained.
        API.TerminateProcess(pi.hProcess, 1);
        API.CloseHandle(pi.hThread);
        API.CloseHandle(pi.hProcess);
        API.CloseHandle(hOutput);
        delete(output);
    }

    @Override public boolean identityVerified(ContainedProcess process) {
        if (!verifier.verify(process)) return false;
        if (!(process.nativeHandle() instanceof NativeHandles handles)) return false;
        return handles.nonce().equals(process.nonce())
                && handles.pid() == process.pid() && !isInvalid(handles.job());
    }

    @Override public boolean signal(ContainedProcess process, Signal signal) {
        if (!(process.nativeHandle() instanceof NativeHandles handles)) return false;
        if (signal == Signal.KILL) return API.TerminateJobObject(handles.job(), 1);
        // Windows has no SIGINT/SIGTERM: CTRL_BREAK gives the process group a chance to exit
        // cooperatively before the bounded KILL phase.
        return API.GenerateConsoleCtrlEvent(CTRL_BREAK_EVENT, handles.pid());
    }

    @Override public EmptyDomain checkEmpty(ContainedProcess process) {
        if (!(process.nativeHandle() instanceof NativeHandles handles)) return EmptyDomain.UNKNOWN;
        Memory list = new Memory(4096);
        list.clear();
        IntByReference returned = new IntByReference();
        boolean ok = API.QueryInformationJobObject(handles.job(), JOB_OBJECT_BASIC_PROCESS_ID_LIST,
                list, (int) list.size(), returned);
        if (!ok) return EmptyDomain.UNKNOWN;
        int assigned = list.getInt(0);
        return assigned == 0 ? EmptyDomain.EMPTY : EmptyDomain.NOT_EMPTY;
    }

    @Override public boolean escapedDomain(ContainedProcess process) {
        // A Job Object covers the normally inherited local process tree. Tasks created through
        // external agents are outside the domain and are recorded as external side effects.
        return false;
    }

    @Override public String readOutput(ContainedProcess process) throws IOException {
        Path output = outputs.get(process.operationId());
        if (output == null || !Files.exists(output)) return "";
        return Files.readString(output, StandardCharsets.UTF_8);
    }

    @Override public Integer waitFor(ContainedProcess process, long millis) {
        if (!(process.nativeHandle() instanceof NativeHandles handles)) return null;
        int wait = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis));
        if (API.WaitForSingleObject(handles.process(), wait) != WAIT_OBJECT_0) return null;
        IntByReference code = new IntByReference();
        return API.GetExitCodeProcess(handles.process(), code) ? code.getValue() : null;
    }

    @Override public void release(ContainedProcess process) {
        if (process.nativeHandle() instanceof NativeHandles handles) {
            API.CloseHandle(handles.job());
            API.CloseHandle(handles.thread());
            API.CloseHandle(handles.process());
            API.CloseHandle(handles.output());
        }
        Path output = outputs.remove(process.operationId());
        if (output != null) delete(output);
    }

    @Override public void close() {
        for (Path output : outputs.values()) delete(output);
        outputs.clear();
    }

    private static boolean isInvalid(WinNT.HANDLE handle) {
        return handle == null || Pointer.nativeValue(handle.getPointer()) == 0;
    }

    private static String fullId(String operationId) {
        return operationId == null ? java.util.UUID.randomUUID().toString().replace("-", "")
                : operationId.replace("-", "");
    }

    private static Memory unicodeEnvironment(StartRequest request) {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (request.environment() != null) env.putAll(request.environment());
        env.put(ProcessIdentityVerifier.NONCE_ENV, request.nonce());
        StringBuilder block = new StringBuilder();
        env.forEach((key, value) -> block.append(key).append('=').append(value).append('\0'));
        block.append('\0');
        char[] chars = block.toString().toCharArray();
        Memory memory = new Memory(Math.max(2L, (long) chars.length * 2L));
        memory.write(0, chars, 0, chars.length);
        return memory;
    }

    static String joinCommandLine(List<String> arguments) {
        StringBuilder out = new StringBuilder();
        for (String argument : arguments) {
            if (out.length() > 0) out.append(' ');
            out.append(quoteWindowsArgument(argument == null ? "" : argument));
        }
        return out.toString();
    }

    /** Implements the CommandLineToArgvW / Microsoft CRT backslash-and-quote rules. */
    static String quoteWindowsArgument(String argument) {
        if (argument.isEmpty()) return "\"\"";
        boolean needsQuotes = argument.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch == '\"');
        if (!needsQuotes) return argument;

        StringBuilder out = new StringBuilder(argument.length() + 2);
        out.append('\"');
        int backslashes = 0;
        for (int i = 0; i < argument.length(); i++) {
            char ch = argument.charAt(i);
            if (ch == '\\') {
                backslashes++;
                continue;
            }
            if (ch == '\"') {
                out.append("\\".repeat(backslashes * 2 + 1)).append('\"');
                backslashes = 0;
                continue;
            }
            if (backslashes > 0) {
                out.append("\\".repeat(backslashes));
                backslashes = 0;
            }
            out.append(ch);
        }
        if (backslashes > 0) out.append("\\".repeat(backslashes * 2));
        return out.append('\"').toString();
    }

    private static void delete(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
