package com.clix.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.clix.llm.ModelProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ModelConfigPersistenceTest {
    @TempDir Path temp;
    @Test
    void atomicSaveRoundTripsModelProfilesAndLeavesNoTemporaryFile() throws Exception {
        Path file = temp.resolve("config.json");
        ClixConfig config = new ClixConfig();
        var provider = new ClixConfig.ProviderConfig("synthetic-key", null, "next");
        provider.getModels().put("next", new ModelProfile("deepseek-flash", 200000, false, null, null, true, true, 8192, "manual"));
        config.getProviders().put("deepseek", provider);
        config.saveOrThrow(file);
        config.saveOrThrow(file);
        ClixConfig loaded = new ObjectMapper().readValue(file.toFile(), ClixConfig.class);
        assertEquals(provider.getModels(), loaded.getProviders().get("deepseek").getModels());
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }
}
