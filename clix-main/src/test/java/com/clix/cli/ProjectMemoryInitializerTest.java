package com.clix.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesConciseClixProjectMemory() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# clix\n\nJava Agent CLI");
        Files.writeString(tempDir.resolve("AGENTS.md"), "项目名：clix\n改命令入口要联动");
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        String content = Files.readString(tempDir.resolve("CLIX.md"));
        assertTrue(result.written());
        assertTrue(content.contains("# CLIX.md"));
        assertTrue(content.contains("clix 是面向商业使用的 Java Agent CLI 产品"));
        assertTrue(content.contains("mvn test -Pquick"));
        assertTrue(content.contains("不要为某个模式创建孤立能力"));
        assertTrue(content.lines().count() < 45, content);
    }

    @Test
    void doesNotOverwriteExistingFileWithoutForce() throws Exception {
        Files.writeString(tempDir.resolve("CLIX.md"), "existing");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        assertFalse(result.written());
        assertTrue(Files.readString(tempDir.resolve("CLIX.md")).equals("existing"));
    }

    @Test
    void forceOverwritesExistingFile() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# clix\n");
        Files.writeString(tempDir.resolve("CLIX.md"), "existing");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, true);

        assertTrue(result.written());
        assertTrue(Files.readString(tempDir.resolve("CLIX.md")).contains("# CLIX.md"));
    }
}
