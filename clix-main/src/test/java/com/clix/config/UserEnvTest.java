package com.clix.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UserEnvTest {
    @TempDir Path temp;

    @Test
    void readsOnlyTheGlobalClixEnvFile() throws Exception {
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temp.toString());
            Path global = temp.resolve(".clix").resolve(".env");
            Files.createDirectories(global.getParent());
            Files.writeString(global, "# comment\nCLIX_TEST_KEY=global-value\nQUOTED=\"hello world\"\n");
            Path project = temp.resolve("project");
            Files.createDirectories(project);
            Files.writeString(project.resolve(".env"), "CLIX_TEST_KEY=project-value\n");
            String oldDirectory = System.getProperty("user.dir");
            System.setProperty("user.dir", project.toString());
            try {
                assertEquals(global, UserEnv.path());
                assertEquals("global-value", UserEnv.get("CLIX_TEST_KEY"));
                assertEquals("hello world", UserEnv.get("QUOTED"));
            } finally {
                if (oldDirectory != null) System.setProperty("user.dir", oldDirectory);
            }
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void createsOneGlobalTemplateWhenMissing() throws Exception {
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temp.toString());
            Path path = UserEnv.ensureExists();
            assertTrue(Files.isRegularFile(path));
            assertTrue(Files.readString(path).contains("DEEPSEEK_API_KEY="));
            assertEquals(path, UserEnv.ensureExists());
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }
}
