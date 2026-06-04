package com.compliance.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WebSearchService {

    private final WebClient webClient;

    @Value("${tavily.api.key:}")
    private String tavilyApiKey;

    public WebSearchService(WebClient webClient) {
        this.webClient = webClient;
    }

    public String searchMarketData(String address) {
        if (tavilyApiKey.isBlank() || address == null || address.isBlank()) {
            log.debug("Web search skipped (keySet={}, address='{}')", !tavilyApiKey.isBlank(), address);
            return "";
        }
        try {
            String query = "real estate market trends comparable sales neighborhood " + address;
            TavilyRequest request = new TavilyRequest(tavilyApiKey, query, "basic", 3);

            TavilyResponse response = webClient.post()
                    .uri("https://api.tavily.com/search")
                    .header("Content-Type", "application/json")
                    .body(BodyInserters.fromValue(request))
                    .retrieve()
                    .bodyToMono(TavilyResponse.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response == null || response.results() == null || response.results().isEmpty()) return "";

            return response.results().stream()
                    .map(r -> "Source: " + r.title() + "\n" + r.content())
                    .collect(Collectors.joining("\n\n"));

        } catch (WebClientResponseException e) {
            log.warn("Tavily HTTP {} {}: returning empty market data", e.getStatusCode().value(), e.getStatusText());
            return "";
        } catch (Exception e) {
            log.warn("Web search failed for '{}' ({}): {}", address, e.getClass().getSimpleName(), e.getMessage());
            return "";
        }
    }

    private record TavilyRequest(
            @JsonProperty("api_key")      String apiKey,
            @JsonProperty("query")        String query,
            @JsonProperty("search_depth") String searchDepth,
            @JsonProperty("max_results")  int maxResults
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResponse(List<TavilyResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResult(String title, String content, String url) {}
}
