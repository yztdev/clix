package com.clix.eval.benchmark.relay;

import com.clix.web.NetworkPolicy;
import com.clix.web.SearchProvider;
import com.clix.web.SearchResult;
import com.clix.web.WebFetcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/** Transport only: product ToolRegistry still owns formatting, typed URL discovery and HTML extraction. */
public final class RelayWebDependencies {
    private final RelayLlmClient relay;
    public RelayWebDependencies(RelayLlmClient relay) {
        this.relay = Objects.requireNonNull(relay);
        if (relay.session().toolProfile() != BenchmarkRelayProtocol.ToolProfile.MOCK_WEB)
            throw new IllegalArgumentException("Web dependencies require the frozen MOCK_WEB profile");
    }
    public SearchProvider searchProvider() {
        return new SearchProvider() {
            @Override public String name() { return "d4-offline"; }
            @Override public boolean isReady() { return true; }
            @Override public String unavailableHint() { return "offline host Web relay unavailable"; }
            @Override public List<SearchResult> search(String query, int topK) throws IOException {
                return relay.exchangeWeb(BenchmarkRelayProtocol.WebOperation.SEARCH, query, topK).results().stream()
                        .map(r -> new SearchResult(r.position(), r.title(), r.url(), r.snippet(), r.source())).toList();
            }
        };
    }
    public NetworkPolicy networkPolicy() {
        return new NetworkPolicy() {
            @Override public String checkUrl(String url) {
                try {
                    String denial = relay.exchangeWeb(BenchmarkRelayProtocol.WebOperation.CHECK_URL, url, 0).denial();
                    return denial.isEmpty() ? null : denial;
                } catch (IOException error) { throw new UncheckedIOException("host Web policy unavailable", error); }
            }
        };
    }
    public WebFetcher fetcher() {
        return new WebFetcher() {
            @Override public RawResponse fetch(String url) throws IOException {
                var page = relay.exchangeWeb(BenchmarkRelayProtocol.WebOperation.FETCH, url, 0).page();
                return new RawResponse(page.url(), page.body(), page.contentType(), page.charset(), page.truncated());
            }
        };
    }
}
