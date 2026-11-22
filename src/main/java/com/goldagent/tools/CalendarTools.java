package com.goldagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CalendarTools {

    private final String feedUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public CalendarTools(String newsCalendarUrl) {
        this.feedUrl = newsCalendarUrl == null ? "" : newsCalendarUrl.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public Map<String, Object> checkUsdNewsWindow() {
        return checkUsdNewsWindow(30);
    }

    public Map<String, Object> checkUsdNewsWindow(int minutesAhead) {
        int windowMinutes = Math.max(1, minutesAhead);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lookaheadMinutes", windowMinutes);

        if (feedUrl.isBlank()) {
            result.put("status", "NO_FEED_CONFIGURED");
            result.put("highImpactUsdEventNearby", false);
            result.put("upcomingEvents", List.of());
            return result;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(feedUrl))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                result.put("status", "FEED_HTTP_" + response.statusCode());
                result.put("highImpactUsdEventNearby", false);
                return result;
            }

            Instant now = Instant.now();
            Instant windowEnd = now.plus(Duration.ofMinutes(windowMinutes));
            ArrayList matchingEvents = new ArrayList<>();

            JsonNode root = mapper.readTree(response.body());
            JsonNode events = root.isArray() ? root : root.path("events");
            if (events.isArray()) {
                for (JsonNode item : events) {
                    String currency = item.path("currency").asText("").trim().toUpperCase();
                    String impact = item.path("impact").asText("").trim().toUpperCase();
                    String timeRaw = item.path("timeUtc").asText(item.path("date").asText("")).trim();
                    if (!"USD".equals(currency) || !"HIGH".equals(impact) || timeRaw.isEmpty()) {
                        continue;
                    }
                    Instant eventTime = Instant.parse(timeRaw);
                    if (!eventTime.isBefore(now.minus(Duration.ofMinutes(15))) && !eventTime.isAfter(windowEnd)) {
                        matchingEvents.add(Map.of(
                                "title", item.path("event").asText(item.path("title").asText("USD Event")),
                                "timeUtc", eventTime.toString(),
                                "impact", impact
                        ));
                    }
                }
            }

            result.put("status", "OK");
            result.put("highImpactUsdEventNearby", !matchingEvents.isEmpty());
            result.put("upcomingEvents", matchingEvents);
            return result;
        } catch (Exception ex) {
            result.put("status", "FEED_ERROR");
            result.put("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            result.put("highImpactUsdEventNearby", false);
            return result;
        }
    }
}