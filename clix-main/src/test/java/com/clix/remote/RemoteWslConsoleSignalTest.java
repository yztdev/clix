package com.clix.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RemoteWslConsoleSignalTest {
    @Test void ctrlCInSeparateWindowsConsoleStopsWslCommand(@TempDir Path project) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        Path script = project.resolve("controller.py");
        Files.writeString(script, """
            import ctypes, pathlib, subprocess, sys, time
            if sys.argv[1] == 'parent':
                startup = subprocess.STARTUPINFO()
                startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
                startup.wShowWindow = 0
                result = subprocess.run([sys.executable, __file__, 'worker', *sys.argv[2:]],
                    creationflags=subprocess.CREATE_NEW_CONSOLE, startupinfo=startup,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=40)
                sys.stdout.buffer.write(result.stdout)
                sys.exit(result.returncode)
            root, java, classpath = sys.argv[2:]
            handler_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_uint)
            handler = handler_type(lambda signal: True)
            ctypes.windll.kernel32.SetConsoleCtrlHandler(handler, True)
            launcher = pathlib.Path(root, 'launcher.cmd')
            launcher.write_text('@"%CLIX_TEST_JAVA%" -cp "%CLIX_TEST_CLASSPATH%" com.clix.remote.WslChildHarness "%CLIX_TEST_ROOT%" console\\n', encoding='ascii')
            env = dict(__import__('os').environ, CLIX_TEST_JAVA=java, CLIX_TEST_CLASSPATH=classpath, CLIX_TEST_ROOT=root)
            child = subprocess.Popen(['cmd.exe', '/d', '/c', str(launcher)], env=env,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                deadline = time.monotonic() + 20
                while not pathlib.Path(root, 'ready.txt').exists() and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.025)
                if not pathlib.Path(root, 'ready.txt').exists():
                    print('command did not start'); sys.exit(2)
                if not ctypes.windll.kernel32.GenerateConsoleCtrlEvent(0, 0):
                    print('signal failed'); sys.exit(3)
                time.sleep(6.5)
                if pathlib.Path(root, 'marker.txt').exists():
                    print('command continued after Ctrl+C'); sys.exit(4)
                print('cancelled successfully')
            finally:
                if child.poll() is None: child.kill()
            """);
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process controller = new ProcessBuilder("python", script.toString(), "parent", project.toString(), javaExe,
                System.getProperty("java.class.path")).redirectErrorStream(true).start();
        assertTrue(controller.waitFor(45, TimeUnit.SECONDS), "控制台测试超时");
        String output = new String(controller.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, controller.exitValue(), output);
    }
}
