package com.clix.config;

import com.clix.agent.Agent;
import com.clix.llm.DeepSeekClient;
import com.clix.llm.LlmClientFactory;
import com.clix.llm.ModelCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartupModelSelectionTest {
    private ClixConfig config(String explicit, String saved) {
        ClixConfig config = new ClixConfig() {
            @Override protected String loadModelFromEnv(String provider) {
                return "deepseek".equals(provider) ? explicit : null;
            }
        };
        config.getProviders().put("deepseek", new ClixConfig.ProviderConfig("synthetic-key", null, saved));
        return config;
    }

    @Test
    void explicitStartupModelOverridesSavedAliasAndReachesStatusBar() {
        var config = config("deepseek-flash", "deepseek-v4-flash");
        var client = LlmClientFactory.createFromConfig(config);
        assertEquals("deepseek-flash", client.getModelName());
        assertEquals("deepseek-v4-flash", config.getProviders().get("deepseek").getModel(), "启动不改写用户保存的选择");
        Agent agent = new Agent(client);
        assertEquals("DeepSeek V4.1 Flash", agent.currentStatus("idle").model());
        assertEquals("DeepSeek V4.1 Flash", agent.currentStatus("thinking").model());
        assertEquals("deepseek-flash", client.getModelName(), "显示名称不能写进 API 请求 ID");
    }

    @Test
    void manualSelectionDuringSessionWinsOverStartupEnvironment() {
        var config = config("deepseek-flash", "deepseek-v4-flash");
        var selected = LlmClientFactory.create("deepseek", "deepseek-v4-pro", config);
        assertEquals("deepseek-v4-pro", selected.getModelName());
        assertEquals("DeepSeek V4 Pro", new Agent(selected).currentStatus("idle").model());
    }

    @Test
    void absentExplicitStartupModelFallsBackToSavedModelThenProviderDefault() {
        assertEquals("deepseek-v4-pro", LlmClientFactory.createFromConfig(config(null, "deepseek-v4-pro")).getModelName());
        assertEquals("deepseek-flash", LlmClientFactory.createFromConfig(config(null, null)).getModelName());
    }

    @Test
    void labelsOnlyKnownDeepseekModelsAndNeverRewritesLegacyWireId() {
        DeepSeekClient legacy = new DeepSeekClient("synthetic-key", "deepseek-v4-flash");
        assertEquals("DeepSeek V4.1 Flash", ModelCatalog.statusName("deepseek", legacy.getModelName()));
        assertEquals("deepseek-v4-flash", legacy.getModelName());
        assertEquals("deepseek-next", ModelCatalog.statusName("deepseek", "deepseek-next"));
        assertEquals("deepseek-flash", ModelCatalog.statusName("other-provider", "deepseek-flash"));
    }
}
