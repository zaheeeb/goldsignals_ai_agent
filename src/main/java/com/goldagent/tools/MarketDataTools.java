package com.goldagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MarketDataTools {

    private static final String TWELVE_DATA_BASE_URL = "https://api.twelvedata.com/time_series";
    private static final String SYMBOL = "XAU/USD";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public MarketDataTools(String twelveDataApiKey) {
        this.apiKey = twelveDataApiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
    }

    private record Candle(
            String datetime,
            double open,
            double high,
            double low,
            double close
    ) {}

    public Map<String, Object> getXauUsdTechnicals(String timeframe, int bars) {
        String interval = normalizeInterval(timeframe);
        int requestedBars = Math.max(220, Math.min(bars, 500));

        try {
            List<Candle> candles = fetchCandlesChronological(interval, requestedBars);
            if (candles.size() < 30) {
                return Map.of(
                        "symbol", SYMBOL,
                        "timeframe", timeframe,
                        "error", "Insufficient candle history returned: " + candles.size()
                );
            }

            Candle latest = candles.get(candles.size() - 1);
            double ema20 = computeEma(candles, 20);
            double ema50 = computeEma(candles, 50);
            double ema200 = candles.size() >= 200 ? computeEma(candles, 200) : computeEma(candles, candles.size());
            double rsi14 = computeRsi(candles, 14);
            double atr14 = computeAtr(candles, 14);

            double estimatedSpreadPoints = estimateSpreadPoints(atr14, interval);
            String session = classifySessionUtc(ZonedDateTime.now(ZoneOffset.UTC));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("symbol", SYMBOL);
            result.put("timeframe", timeframe);
            result.put("interval", interval);
            result.put("barsAnalyzed", candles.size());
            result.put("candleDatetime", latest.datetime());
            result.put("evaluatedAtUtc", Instant.now().toString());
            result.put("open", round2(latest.open()));
            result.put("high", round2(latest.high()));
            result.put("low", round2(latest.low()));
            result.put("close", round2(latest.close()));
            result.put("ema20", round2(ema20));
            result.put("ema50", round2(ema50));
            result.put("ema200", round2(ema200));
            result.put("rsi14", round2(rsi14));
            result.put("atr14", round2(atr14));
            result.put("estimatedSpreadPoints", round1(estimatedSpreadPoints));
            result.put("session", session);
            return result;
        } catch (Exception ex) {
            return Map.of(
                    "symbol", SYMBOL,
                    "timeframe", timeframe,
                    "error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()
            );
        }
    }

    public Map<String, Object> getHigherTimeframeBias() {
        try {
            List<Candle> h1Candles = fetchCandlesChronological("1h", 220);
            if (h1Candles.size() < 50) {
                return Map.of("error", "Insufficient H1 candles for bias calculation");
            }

            Candle latestH1 = h1Candles.get(h1Candles.size() - 1);
            double h1Ema20 = computeEma(h1Candles, 20);
            double h1Ema50 = computeEma(h1Candles, 50);
            double h1Ema200 = h1Candles.size() >= 200 ? computeEma(h1Candles, 200) : h1Ema50;

            String h1Trend;
            if (latestH1.close() > h1Ema50 && h1Ema20 > h1Ema50) {
                h1Trend = "BULLISH";
            } else if (latestH1.close() < h1Ema50 && h1Ema20 < h1Ema50) {
                h1Trend = "BEARISH";
            } else {
                h1Trend = "RANGE_OR_MIXED";
            }

            int lookbackStart = Math.max(0, h1Candles.size() - 96); // ~4 trading days on H1
            double recentSwingLow = Double.POSITIVE_INFINITY;
            double recentSwingHigh = Double.NEGATIVE_INFINITY;
            for (int i = lookbackStart; i < h1Candles.size(); i++) {
                recentSwingLow = Math.min(recentSwingLow, h1Candles.get(i).low());
                recentSwingHigh = Math.max(recentSwingHigh, h1Candles.get(i).high());
            }

            Map<String, Object> bias = new LinkedHashMap<>();
            bias.put("symbol", SYMBOL);
            bias.put("h1Trend", h1Trend);
            bias.put("h1Close", round2(latestH1.close()));
            bias.put("h1Ema20", round2(h1Ema20));
            bias.put("h1Ema50", round2(h1Ema50));
            bias.put("h1Ema200", round2(h1Ema200));
            bias.put("recentSupport", round2(recentSwingLow));
            bias.put("recentResistance", round2(recentSwingHigh));
            return bias;
        } catch (Exception ex) {
            return Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    private List<Candle> fetchCandlesChronological(String interval, int outputSize) throws Exception {
        String encodedSymbol = URLEncoder.encode(SYMBOL, StandardCharsets.UTF_8);
        String encodedInterval = URLEncoder.encode(interval, StandardCharsets.UTF_8);
        String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        String url = String.format(
                "%s?symbol=%s&interval=%s&outputsize=%d&apikey=%s",
                TWELVE_DATA_BASE_URL, encodedSymbol, encodedInterval, outputSize, encodedKey
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String location = response.headers().firstValue("Location").orElse("<none>");

        if (status != 200) {
            String rawBody = response.body() == null ? "" : response.body();
            String snippet = rawBody.substring(0, Math.min(rawBody.length(), 300)).replaceAll("\\s+", " ");
            System.err.printf("[MarketDataTools] Non-200 response: interval=%s, status=%d, location=%s, bodySnippet=%s%n",
                    interval, status, location, snippet);
            throw new IllegalStateException("Market data HTTP " + status + " (Location: " + location + ")");
        }

        JsonNode root = mapper.readTree(response.body());
        if ("error".equalsIgnoreCase(root.path("status").asText())) {
            throw new IllegalStateException("Twelve Data API error: " + root.path("message").asText("unknown error"));
        }

        JsonNode values = root.path("values");
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalStateException("No OHLCV values returned in response");
        }

        List<Candle> parsed = new ArrayList<>(values.size());
        for (JsonNode node : values) {
            parsed.add(new Candle(
                    node.path("datetime").asText(""),
                    Double.parseDouble(node.path("open").asText()),
                    Double.parseDouble(node.path("high").asText()),
                    Double.parseDouble(node.path("low").asText()),
                    Double.parseDouble(node.path("close").asText())
            ));
        }

        // Twelve Data returns newest candle first; reverse for chronological indicator math
        Collections.reverse(parsed);
        return parsed;
    }

    static double computeEma(List<Candle> candles, int period) {
        int n = candles.size();
        int effectivePeriod = Math.min(period, n);
        double multiplier = 2.0 / (effectivePeriod + 1.0);

        double sma = 0.0;
        for (int i = 0; i < effectivePeriod; i++) {
            sma += candles.get(i).close();
        }
        double ema = sma / effectivePeriod;

        for (int i = effectivePeriod; i < n; i++) {
            double close = candles.get(i).close();
            ema = ((close - ema) * multiplier) + ema;
        }
        return ema;
    }

    static double computeRsi(List<Candle> candles, int period) {
        if (candles.size() <= period) {
            return 50.0;
        }

        double gainSum = 0.0;
        double lossSum = 0.0;
        for (int i = 1; i <= period; i++) {
            double diff = candles.get(i).close() - candles.get(i - 1).close();
            if (diff > 0) {
                gainSum += diff;
            } else {
                lossSum += Math.abs(diff);
            }
        }

        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;

        for (int i = period + 1; i < candles.size(); i++) {
            double diff = candles.get(i).close() - candles.get(i - 1).close();
            double gain = Math.max(diff, 0.0);
            double loss = Math.max(-diff, 0.0);
            avgGain = ((avgGain * (period - 1)) + gain) / period;
            avgLoss = ((avgLoss * (period - 1)) + loss) / period;
        }

        if (avgLoss == 0.0) {
            return 100.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    static double computeAtr(List<Candle> candles, int period) {
        if (candles.size() < 2) {
            return 0.0;
        }
        int effectivePeriod = Math.min(period, candles.size() - 1);

        List<Double> trList = new ArrayList<>(candles.size() - 1);
        for (int i = 1; i < candles.size(); i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);
            double hl = curr.high() - curr.low();
            double hc = Math.abs(curr.high() - prev.close());
            double lc = Math.abs(curr.low() - prev.close());
            trList.add(Math.max(hl, Math.max(hc, lc)));
        }

        double sum = 0.0;
        for (int i = 0; i < effectivePeriod; i++) {
            sum += trList.get(i);
        }
        double atr = sum / effectivePeriod;

        for (int i = effectivePeriod; i < trList.size(); i++) {
            atr = ((atr * (effectivePeriod - 1)) + trList.get(i)) / effectivePeriod;
        }
        return atr;
    }

    private String normalizeInterval(String timeframe) {
        if (timeframe == null) {
            return "15min";
        }
        return switch (timeframe.trim().toUpperCase()) {
            case "M1", "1MIN", "1M" -> "1min";
            case "M5", "5MIN", "5M" -> "5min";
            case "M15", "15MIN", "15M" -> "15min";
            case "M30", "30MIN", "30M" -> "30min";
            case "H1", "1H", "60MIN" -> "1h";
            case "H4", "4H" -> "4h";
            case "D1", "1D", "DAILY" -> "1day";
            default -> timeframe.trim().toLowerCase();
        };
    }

    private double estimateSpreadPoints(double atr, String interval) {
        // Spot/CFD APIs don't always return live broker bid/ask spread; flag rollover window explicitly
        int utcHour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        if (utcHour == 21 || utcHour == 22) {
            return 45.0; // Daily rollover liquidity gap window
        }
        if ("15min".equals(interval) && atr > 0) {
            return Math.min(35.0, Math.max(12.0, atr * 3.5));
        }
        return 18.0;
    }

    private String classifySessionUtc(ZonedDateTime utc) {
        int hour = utc.getHour();
        if (hour >= 13 && hour < 17) {
            return "LONDON_NY_OVERLAP";
        } else if (hour >= 7 && hour < 16) {
            return "LONDON";
        } else if (hour >= 13 && hour < 21) {
            return "NEW_YORK";
        } else {
            return "ASIAN_OR_OFFPEAK";
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}