package com.clix.remote;

import com.clix.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the installed-client shape: clix.cmd, RemoteClientCommand, socket, approval and Ctrl+C. */
class RemoteCliCtrlCEndToEndTest {
    private static final String TOKEN = "test-token-123456789012345678901234567890";

    @Test
    void ctrlCThroughCmdAndSocketStopsApprovedWslCommand(@TempDir Path root) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
        String configuredProject = System.getProperty("clix.test.project", "");
        Path project = configuredProject.isBlank()
                ? Files.createDirectory(root.resolve("client"))
                : Files.createDirectories(Path.of(configuredProject).toAbsolutePath().normalize());
        Path script = root.resolve("controller.py");
        Files.writeString(script, """
                import ctypes, os, pathlib, subprocess, sys, time
                if sys.argv[1] == 'parent':
                    startup = subprocess.STARTUPINFO()
                    startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
                    startup.wShowWindow = 0
                    result = subprocess.run([sys.executable, __file__, 'worker', *sys.argv[2:]],
                        creationflags=subprocess.CREATE_NEW_CONSOLE, startupinfo=startup,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
                    sys.stdout.buffer.write(result.stdout)
                    sys.exit(result.returncode)

                root, java, launch_target, use_jar, wait_for_start, url, token = sys.argv[2:]
                handler_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_uint)
                handler = handler_type(lambda signal: True)
                ctypes.windll.kernel32.SetConsoleCtrlHandler(handler, True)
                launcher = pathlib.Path(root, 'clix.cmd')
                java_command = ('-jar "%CLIX_TEST_TARGET%"' if use_jar == 'true'
                    else '-cp "%CLIX_TEST_TARGET%" com.clix.cli.Main')
                launcher.write_text(
                    '@if "%~1"=="" (\\n'
                    '  @"%CLIX_TEST_JAVA%" ' + java_command + ' remote\\n'
                    ') else (\\n'
                    '  @"%CLIX_TEST_JAVA%" ' + java_command + ' %*\\n'
                    ')\\n', encoding='ascii')
                env = dict(os.environ, CLIX_TEST_JAVA=java, CLIX_TEST_TARGET=launch_target)
                if url:
                    env['CLIX_REMOTE_URL'] = url
                    env['CLIX_REMOTE_TOKEN'] = token
                else:
                    env.pop('CLIX_REMOTE_URL', None)
                    env.pop('CLIX_REMOTE_TOKEN', None)
                output_path = pathlib.Path(root, 'client-output.log')
                output = output_path.open('wb')
                child = subprocess.Popen(['cmd.exe', '/d', '/c', str(launcher)], cwd=root, env=env,
                    stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT)
                try:
                    request = ('Please use execute_command to execute exactly: '
                        'printf started > ready.txt; sleep 15; printf done > marker.txt')
                    child.stdin.write(('/mode ask\\n' + request + '\\ny\\n').encode('utf-8'))
                    child.stdin.flush()
                    deadline = time.monotonic() + 25
                    if wait_for_start == 'true':
                        ready = pathlib.Path(root, 'ready.txt')
                        while not ready.exists() and child.poll() is None and time.monotonic() < deadline:
                            time.sleep(0.025)
                        if not ready.exists():
                            output.flush()
                            print('approved command did not start, exit=', child.poll(),
                                'client log=', output_path.read_bytes()[:2000]); sys.exit(2)
                    else:
                        approved_utf8 = '已批准'.encode('utf-8')
                        approved_gbk = '已批准'.encode('gbk')
                        approved = False
                        while child.poll() is None and time.monotonic() < deadline:
                            output.flush()
                            data = output_path.read_bytes()
                            if approved_utf8 in data or approved_gbk in data:
                                approved = True
                                break
                            time.sleep(0.025)
                        if not approved:
                            print('approval did not complete, exit=', child.poll()); sys.exit(2)
                    if not ctypes.windll.kernel32.GenerateConsoleCtrlEvent(0, 0):
                        print('signal failed'); sys.exit(3)
                    # Match the real clix.cmd interaction: cmd.exe asks whether to
                    # terminate the batch job and the user immediately answers y.
                    child.stdin.write('y\\n'.encode('ascii'))
                    child.stdin.flush()
                    time.sleep(16.5)
                    if pathlib.Path(root, 'marker.txt').exists():
                        print('WSL command continued after Ctrl+C'); sys.exit(4)
                    print('full CLI cancellation succeeded')
                finally:
                    if child.poll() is None: child.kill()
                    output.close()
                """);

        boolean realServer = Boolean.getBoolean("clix.test.realServer");
        RemoteSocketServer server = realServer ? null : new RemoteSocketServer(0,
                new RemoteIdentityStore(Map.of("alice", TOKEN)),
                (user, id, sender) -> new RemoteAgentSession(user, root.resolve("server"), new CommandModel(), sender));
        try {
            if (server != null) server.start();
            String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            String url = realServer ? "" : "ws://127.0.0.1:" + server.port() + "/v1/remote";
            String token = realServer ? "" : TOKEN;
            String configuredJar = System.getProperty("clix.test.jar", "");
            boolean useJar = !configuredJar.isBlank();
            String launchTarget = useJar ? configuredJar : System.getProperty("java.class.path");
            boolean waitForStart = Boolean.parseBoolean(
                    System.getProperty("clix.test.waitForCommandStart", "true"));
            Process controller = new ProcessBuilder("python", script.toString(), "parent", project.toString(),
                    javaExe, launchTarget, Boolean.toString(useJar), Boolean.toString(waitForStart), url, token)
                    .redirectErrorStream(true).start();
            assertTrue(controller.waitFor(50, TimeUnit.SECONDS), "完整 CLI 取消测试超时");
            String output = new String(controller.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, controller.exitValue(), output);
        } finally {
            if (server != null) server.close();
        }
    }

    private static final class CommandModel implements LlmClient {
        private int step;

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            if (step++ == 0) {
                var function = new ToolCall.Function("execute_command",
                        "{\"command\":\"printf started > ready.txt; sleep 15; printf done > marker.txt\"}");
                return new ChatResponse("assistant", "", List.of(new ToolCall("one", function)), 1, 1);
            }
            return new ChatResponse("assistant", "done", null, 1, 1);
        }

        @Override public String getModelName() { return "test"; }
        @Override public String getProviderName() { return "test"; }
    }
}
