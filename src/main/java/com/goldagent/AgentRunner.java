package com.goldagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldagent.model.TradeSignal;
import com.goldagent.tools.CalendarTools;
import com.goldagent.tools.MarketDataTools;
import com.google.genai.Client;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class AgentRunner {

    private static final String DEFAULT_MODEL = "gemini-3.6-flash";
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1_500L;

    private static final String SYSTEM_INSTRUCTION = """
            You are a disciplined XAU/USD CFD intraday technical and event-risk analyst.
            Evaluate the provided M15 market data and USD economic calendar context.
            Return ONLY a single valid JSON object (no markdown fences, no extra text) with these exact fields:
            {
              "action": "BUY" | "SELL" | "NO_TRADE",
              "confidence": <integer 0-100>,
              "entryZone": "<price range or market entry note>",
              "stopLoss": <number>,
              "takeProfit1": <number>,
              "takeProfit2": <number>,
              "atrRiskNote": "<short volatility/position-sizing note>",
              "reasoning": "<concise technical and news rationale>"
            }
            Use "NO_TRADE" whenever price structure is choppy, risk/reward is poor, or high-impact USD news creates unsafe volatility.
            """;

    private final Client client;
    private final MarketDataTools marketDataTools;
    private final CalendarTools calendarTools;
    private final String modelName;
    private final ObjectMapper objectMapper;

    public AgentRunner(String apiKey, MarketDataTools marketDataTools, CalendarTools calendarTools) {
        this(apiKey, marketDataTools, calendarTools, DEFAULT_MODEL);
    }

    public AgentRunner(
            String apiKey,
            MarketDataTools marketDataTools,
            CalendarTools calendarTools,
            String modelName
    ) {
        this.marketDataTools = marketDataTools;
        this.calendarTools = calendarTools;
        this.modelName = (modelName == null || modelName.isBlank()) ? DEFAULT_MODEL : modelName.trim();
        this.client = Client.builder().apiKey(apiKey).build();
        this.objectMapper = new ObjectMapper();
    }

    public TradeSignal evaluateM15Close() {
        String marketSnapshot = fetchMarketContext();
        String calendarSnapshot = fetchCalendarContext();

        String prompt = """
                Evaluate the latest closed XAU/USD M15 candle and return a TradeSignal JSON object.

                === MARKET DATA (XAU/USD M15) ===
                %s

                === USD ECONOMIC CALENDAR ===
                %s
                """.formatted(marketSnapshot, calendarSnapshot);

        List<Content> contents = List.of(
                Content.fromParts(Part.fromText(prompt))
        );

        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(SYSTEM_INSTRUCTION)))
                .temperature(0.2f)
                .responseMimeType("application/json")
                .build();

        GenerateContentResponse response = generateWithRetry(modelName, contents, config);
        String rawText = response != null ? response.text() : null;
        return parseTradeSignal(rawText);
    }

    private String fetchMarketContext() {
        if (marketDataTools == null) {
            return "NO_MARKET_DATA_TOOL";
        }
        try {
            Map<String, Object> m5Technicals = marketDataTools.getXauUsdTechnicals("M5", 220);
            Map<String, Object> h1Bias = marketDataTools.getHigherTimeframeBias();

            Map<String, Object> combined = new LinkedHashMap<>();
            combined.put("intradayTechnicals", m5Technicals);
            combined.put("higherTimeframeBias", h1Bias);
            return objectMapper.writeValueAsString(combined);
        } catch (Exception ex) {
            return "MARKET_DATA_ERROR: " + ex.getMessage();
        }
    }

    private String fetchCalendarContext() {
        if (calendarTools == null) {
            return "NO_FEED_CONFIGURED";
        }
        try {
            String status = String.valueOf(calendarTools.checkUsdNewsWindow());
            return status.isBlank() ? "NO_FEED_CONFIGURED" : status;
        } catch (Exception ex) {
            return "CALENDAR_ERROR: " + ex.getMessage();
        }
    }

    private GenerateContentResponse generateWithRetry(
            String model,
            List<Content> contents,
            GenerateContentConfig config
    ) {
        long delayMs = BASE_BACKOFF_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                return client.models.generateContent(model, contents, config);
            } catch (ServerException ex) {
                boolean is503 = ex.code() == 503
                        || (ex.getMessage() != null && ex.getMessage().contains("503"));
                if (!is503 || attempt > MAX_RETRIES) {
                    throw ex;
                }
                sleepBackoff(attempt, delayMs);
                delayMs *= 2;
            } catch (ClientException ex) {
                if (ex.code() != 429 || attempt > MAX_RETRIES) {
                    throw ex;
                }
                sleepBackoff(attempt, delayMs);
                delayMs *= 2;
            }
        }
    }

    private void sleepBackoff(int attempt, long baseDelayMs) {
        long jitterMs = ThreadLocalRandom.current().nextLong(200L, 600L);
        long sleepMs = baseDelayMs + jitterMs;
        System.err.printf("Gemini transient error on attempt %d; retrying in %d ms...%n", attempt, sleepMs);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Gemini retry backoff", ie);
        }
    }

    private TradeSignal parseTradeSignal(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return noTradeSignal("Empty response from Gemini");
        }

        String cleaned = stripCodeFences(rawText.trim());
        try {
            JsonNode node = objectMapper.readTree(cleaned);

            String actionRaw = node.path("action").asText("NO_TRADE").trim().toUpperCase(Locale.ROOT);
            String action = switch (actionRaw) {
                case "BUY", "SELL", "NO_TRADE" -> actionRaw;
                default -> "NO_TRADE";
            };

            int confidence = Math.max(0, Math.min(100, node.path("confidence").asInt(0)));
            String entryZone = node.path("entryZone").asText("N/A");
            double stopLoss = node.path("stopLoss").asDouble(0.0);
            double takeProfit1 = node.path("takeProfit1").asDouble(0.0);
            double takeProfit2 = node.path("takeProfit2").asDouble(0.0);
            String atrRiskNote = node.path("atrRiskNote").asText("");
            String reasoning = node.path("reasoning").asText("Parsed signal");

            return new TradeSignal(
                    action,
                    confidence,
                    entryZone,
                    stopLoss,
                    takeProfit1,
                    takeProfit2,
                    atrRiskNote,
                    reasoning
            );
        } catch (Exception ex) {
            return noTradeSignal("Failed to parse Gemini JSON: " + ex.getMessage());
        }
    }

    private TradeSignal noTradeSignal(String reason) {
        return new TradeSignal("NO_TRADE", 0, "N/A", 0.0, 0.0, 0.0, "", reason);
    }

    private String stripCodeFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) {
                text = text.substring(0, lastFence);
            }
        }
        return text.trim();
    }
}