package com.clix.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClixConfigHunyuanKeyTest {

    @Test
    void resolvesHunyuanAliasesInDocumentedPriorityWithoutExposingValues() {
        Map<String, String> process = Map.of(
                "TOKENHUB_API_KEY", "tokenhub-marker",
                "TENCENTMAAS_API_KEY", "tencentmaas-marker");
        Map<String, String> dotEnv = Map.of("HUNYUAN_API_KEY", "canonical-marker");

        String resolved = ClixConfig.loadApiKeyFromSources(
                "hunyuan", process::get, dotEnv::get);

        assertEquals("canonical-marker", resolved);
    }

    @Test
    void fallsBackFromBlankCanonicalToTokenHubThenTencentMaas() {
        Map<String, String> process = Map.of(
                "HUNYUAN_API_KEY", "  ",
                "TENCENTMAAS_API_KEY", "tencentmaas-marker");
        Map<String, String> dotEnv = Map.of("TOKENHUB_API_KEY", " tokenhub-marker ");

        String tokenHubResolved = ClixConfig.loadApiKeyFromSources(
                "hunyuan", process::get, dotEnv::get);
        String tencentMaasResolved = ClixConfig.loadApiKeyFromSources(
                "hunyuan",
                Map.of("HUNYUAN_API_KEY", "  ", "TOKENHUB_API_KEY", " ")::get,
                Map.of("TENCENTMAAS_API_KEY", " tencentmaas-marker ")::get);

        assertEquals("tokenhub-marker", tokenHubResolved);
        assertEquals("tencentmaas-marker", tencentMaasResolved);
    }

    @Test
    void hunyuanAliasesDoNotAffectOtherProviders() {
        Map<String, String> process = Map.of(
                "TOKENHUB_API_KEY", "tokenhub-marker",
                "TENCENTMAAS_API_KEY", "tencentmaas-marker");

        String resolved = ClixConfig.loadApiKeyFromSources(
                "glm", process::get, ignored -> null);

        assertNull(resolved);
    }
}
