package com.goldagent.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.goldagent.model.TradeSignal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class TelegramNotifier {

    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";
    private static final DateTimeFormatter UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT);

    private final String botToken;
    private final String chatId;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public TelegramNotifier(String botToken, String chatId) {
        this.botToken = botToken == null ? "" : botToken.trim();
        this.chatId = chatId == null ? "" : chatId.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public boolean isConfigured() {
        return !botToken.isBlank() && !chatId.isBlank();
    }

    public void sendSignalAlert(TradeSignal signal) {
        if (signal == null || signal.action() == null) {
            return;
        }

        String normalizedAction = signal.action().trim().toUpperCase(Locale.ROOT);
        if (!"BUY".equals(normalizedAction) && !"SELL".equals(normalizedAction)) {
            return;
        }

        if (!isConfigured()) {
            System.out.println("TelegramNotifier skipped: TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID is not configured.");
            return;
        }

        String messageText = formatSignalMessage(signal, normalizedAction);
        sendMessage(messageText);
    }

    public void sendMessage(String text) {
        if (!isConfigured() || text == null || text.isBlank()) {
            return;
        }

        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            payload.put("disable_web_page_preview", true);

            String jsonBody = mapper.writeValueAsString(payload);
            String endpoint = TELEGRAM_API_BASE + botToken + "/sendMessage";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.printf("Telegram API error (HTTP %d): %s%n", response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            System.err.printf("Failed to send Telegram alert: %s%n",
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    private String formatSignalMessage(TradeSignal signal, String action) {
        String directionLabel = "BUY".equals(action) ? "LONG / BUY" : "SHORT / SELL";
        String timestampUtc = ZonedDateTime.now(ZoneOffset.UTC).format(UTC_FORMATTER);

        StringBuilder sb = new StringBuilder();
        sb.append("XAU/USD M15 SIGNAL: ").append(directionLabel).append('\n');
        sb.append("Time: ").append(timestampUtc).append('\n');
        sb.append("Confidence: ").append(signal.confidence()).append("%\n\n");
        sb.append("Entry Zone: ").append(safeText(signal.entryZone(), "Market / Pullback")).append('\n');
        sb.append("Stop Loss: ").append(signal.stopLoss()).append('\n');
        sb.append("TP1: ").append(signal.takeProfit1()).append('\n');
        sb.append("TP2: ").append(signal.takeProfit2()).append('\n');

        if (signal.atrRiskNote() != null && !signal.atrRiskNote().isBlank()) {
            sb.append("\nRisk Note: ").append(signal.atrRiskNote().trim());
        }
        if (signal.reasoning() != null && !signal.reasoning().isBlank()) {
            sb.append("\nSetup: ").append(signal.reasoning().trim());
        }

        return sb.toString();
    }

    private String safeText(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}