package com.clix.process;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WindowsJobObjectContainmentTest {

    private static ProcessContainment.StartRequest request(List<String> argv, String shell) {
        return new ProcessContainment.StartRequest("op-1", "turn-1", "call-1", "nonce-1",
                "echo hi", argv, Path.of("."), Map.of(), ".", shell, 5_000L);
    }

    @Test void wrapsSingleCommandWithNativePowerShellArguments() {
        WindowsJobObjectContainment containment = new WindowsJobObjectContainment();
        List<String> wrapped = containment.wrapCommand(List.of("Write-Output \"hello world\""),
                request(List.of("Write-Output \"hello world\""), "powershell.exe"));
        assertEquals(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", "Write-Output \"hello world\""), wrapped);
    }

    @Test void preservesSandboxArgumentVectorWithoutShellReparsing() {
        WindowsJobObjectContainment containment = new WindowsJobObjectContainment();
        List<String> argv = List.of("bwrap.exe", "--ro-bind", "C:/Program Files", "C:/Program Files", "--", "tool.exe");
        assertSame(argv, containment.wrapCommand(argv, request(argv, "powershell.exe")));
    }

    @Test void quotesWindowsArgumentsWithSpacesQuotesAndTrailingBackslashes() {
        assertEquals("\"C:\\Program Files\\\\\"",
                WindowsJobObjectContainment.quoteWindowsArgument("C:\\Program Files\\"));
        assertEquals("\"say \\\"hello\\\"\"",
                WindowsJobObjectContainment.quoteWindowsArgument("say \"hello\""));
        assertEquals("plain", WindowsJobObjectContainment.quoteWindowsArgument("plain"));
    }
}
