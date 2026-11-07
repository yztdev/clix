package com.clix.effect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileEffectProbeTest {
    @TempDir Path root;

    @Test void rejectsAbsoluteTraversalAndBlankPaths() {
        FileEffectProbe probe = new FileEffectProbe(root);
        assertThrows(FileEffectProbe.PathRejected.class, () -> probe.resolve("../secret.txt"));
        assertThrows(FileEffectProbe.PathRejected.class, () -> probe.resolve("a/../../secret.txt"));
        assertThrows(FileEffectProbe.PathRejected.class, () -> probe.resolve("/etc/passwd"));
        assertThrows(FileEffectProbe.PathRejected.class, () -> probe.resolve("C:/secret.txt"));
        assertThrows(FileEffectProbe.PathRejected.class, () -> probe.resolve("   "));
    }

    @Test void resolvesRelativePathInsideRoot() throws Exception {
        FileEffectProbe probe = new FileEffectProbe(root);
        assertEquals(root.resolve("src").resolve("App.java").normalize(), probe.resolve("src/App.java"));
    }

    @Test void captureDistinguishesAbsentPresentAndFailedWithoutReadingContentIntoTheReport()
            throws Exception {
        FileEffectProbe probe = new FileEffectProbe(root);
        Path file = root.resolve("a.txt");
        assertEquals(FileEffectProbe.Status.ABSENT, probe.capture(file).status());
        assertTrue(probe.capture(file).captured());

        Files.writeString(file, "hello");
        FileEffectProbe.State present = probe.capture(file);
        assertEquals(FileEffectProbe.Status.PRESENT, present.status());
        assertEquals(EffectEvent.sha256("hello"), present.sha256());
        assertEquals(5L, present.size());
        assertEquals(FileEffectProbe.Status.FAILED, probe.capture(root).status());
        assertFalse(probe.capture(root).captured());
    }
}
