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
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.ThinkingLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class AgentRunner {

    private static final String DEFAULT_MODEL = "gemini-3.6-flash";
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1_500L;

    // Deterministic pre-filter thresholds (tuned for XAU/USD M15)
    private static final double MIN_M15_ATR = 1.20;
    private static final double MIN_EMA_SPREAD_ATR_RATIO = 0.30;
    private static final double NEUTRAL_RSI_LOW = 48.0;
    private static final double NEUTRAL_RSI_HIGH = 52.0;

    private static final String SYSTEM_INSTRUCTION = """
            You are a disciplined XAU/USD CFD intraday technical and event-risk analyst.
            Evaluate the provided M15 market data, H1 higher-timeframe bias, and USD economic calendar context.
            Default to NO_TRADE unless trend, volatility, momentum, and risk/reward all align cleanly.

            MANDATORY NO_TRADE RULES (return action="NO_TRADE" immediately if ANY apply):
            1. Higher-Timeframe Consolidation: h1Trend is RANGE_OR_MIXED. Never suggest entries inside a mixed H1 range.
            2. Choppy / Tangled EMAs: M15 close, ema20, and ema50 are compressed or not stacked cleanly in trade direction (BUY requires close > ema20 > ema50 > ema200; SELL requires close < ema20 < ema50 < ema200).
            3. Flat or Exhausted Momentum: M15 rsi14 is between 48 and 52, above 72 for BUY, or below 28 for SELL.
            4. Poor Volatility / Spread Edge: M15 atr14 is below 1.20 or spread drag erodes edge.
            5. Event Risk: High-impact USD news is imminent or active and creates unsafe spike risk.

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
        if (marketDataTools == null) {
            return noTradeSignal("NO_MARKET_DATA_TOOL");
        }

        Map<String, Object> m15Technicals;
        Map<String, Object> h1Bias;
        try {
            m15Technicals = marketDataTools.getXauUsdTechnicals("M15", 220);
            h1Bias = marketDataTools.getHigherTimeframeBias();
        } catch (Exception ex) {
            return noTradeSignal("MARKET_DATA_ERROR: " + ex.getMessage());
        }

        // Fast deterministic pre-filter: skip Gemini call during RANGE_OR_MIXED or dead-volatility chop
        TradeSignal preFilterNoTrade = checkDeterministicNoTrade(m15Technicals, h1Bias);
        if (preFilterNoTrade != null) {
            return preFilterNoTrade;
        }

        String marketSnapshot;
        try {
            Map<String, Object> combined = new LinkedHashMap<>();
            combined.put("m15Technicals", m15Technicals);
            combined.put("higherTimeframeBias", h1Bias);
            marketSnapshot = objectMapper.writeValueAsString(combined);
        } catch (Exception ex) {
            return noTradeSignal("MARKET_SERIALIZATION_ERROR: " + ex.getMessage());
        }

        String calendarSnapshot = fetchCalendarContext();

        String prompt = """
                Evaluate the latest closed XAU/USD M15 candle and return a TradeSignal JSON object.

                === MARKET DATA (XAU/USD M15 & H1) ===
                %s

                === USD ECONOMIC CALENDAR ===
                %s
                """.formatted(marketSnapshot, calendarSnapshot);

        List<Content> contents = List.of(
                Content.fromParts(Part.fromText(prompt))
        );

        ThinkingConfig thinkingConfig = ThinkingConfig.builder()
                .thinkingLevel(new ThinkingLevel("LOW"))
                .includeThoughts(false)
                .build();

        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(SYSTEM_INSTRUCTION)))
                .temperature(0.2f)
                .responseMimeType("application/json")
                .thinkingConfig(thinkingConfig)
                .build();

        GenerateContentResponse response = generateWithRetry(modelName, contents, config);
        logTokenUsage(response);
        String rawText = response != null ? response.text() : null;
        return parseTradeSignal(rawText);
    }

    private TradeSignal checkDeterministicNoTrade(Map<String, Object> m15, Map<String, Object> h1Bias) {
        if (m15 == null || m15.isEmpty() || m15.containsKey("error")) {
            return noTradeSignal("M15 market data unavailable: " + (m15 != null ? m15.get("error") : "null"));
        }
        if (h1Bias == null || h1Bias.isEmpty() || h1Bias.containsKey("error")) {
            return noTradeSignal("H1 bias data unavailable: " + (h1Bias != null ? h1Bias.get("error") : "null"));
        }

        String h1Trend = String.valueOf(h1Bias.getOrDefault("h1Trend", "RANGE_OR_MIXED")).trim();
        if ("RANGE_OR_MIXED".equalsIgnoreCase(h1Trend)) {
            return noTradeSignal("Pre-filter NO_TRADE: H1 bias is RANGE_OR_MIXED.");
        }

        double atr14 = asDouble(m15.get("atr14"), 0.0);
        if (atr14 < MIN_M15_ATR) {
            return noTradeSignal(String.format(Locale.ROOT,
                    "Pre-filter NO_TRADE: M15 ATR %.2f below minimum %.2f.", atr14, MIN_M15_ATR));
        }

        double ema20 = asDouble(m15.get("ema20"), 0.0);
        double ema50 = asDouble(m15.get("ema50"), 0.0);
        if (ema20 > 0.0 && ema50 > 0.0 && Math.abs(ema20 - ema50) < (MIN_EMA_SPREAD_ATR_RATIO * atr14)) {
            return noTradeSignal(String.format(Locale.ROOT,
                    "Pre-filter NO_TRADE: M15 EMA20/EMA50 compressed (spread %.2f < %.2f * ATR).",
                    Math.abs(ema20 - ema50), MIN_EMA_SPREAD_ATR_RATIO));
        }

        double rsi14 = asDouble(m15.get("rsi14"), 50.0);
        if (rsi14 >= NEUTRAL_RSI_LOW && rsi14 <= NEUTRAL_RSI_HIGH) {
            return noTradeSignal(String.format(Locale.ROOT,
                    "Pre-filter NO_TRADE: M15 RSI %.2f in neutral consolidation zone (%.0f-%.0f).",
                    rsi14, NEUTRAL_RSI_LOW, NEUTRAL_RSI_HIGH));
        }

        return null;
    }

    private double asDouble(Object value, double fallback) {
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private void logTokenUsage(GenerateContentResponse response) {
        if (response == null) {
            return;
        }
        response.usageMetadata().ifPresent(usage -> {
            int promptTokens = usage.promptTokenCount().orElse(0);
            int candidateTokens = usage.candidatesTokenCount().orElse(0);
            int thoughtTokens = usage.thoughtsTokenCount().orElse(0);
            int totalTokens = usage.totalTokenCount().orElse(promptTokens + candidateTokens + thoughtTokens);
            System.out.printf("[TokenUsage] prompt=%d, output=%d, thoughts=%d, total=%d%n",
                    promptTokens, candidateTokens, thoughtTokens, totalTokens);
        });
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