package com.goldagent.notify;

import com.goldagent.model.TradeSignal;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TelegramNotifier {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final ZoneOffset ALERT_ZONE = ZoneOffset.ofHours(1);
    private static final DateTimeFormatter ALERT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC+1'", Locale.ROOT);

    private final String botToken;
    private final String chatId;
    private final HttpClient httpClient;

    public TelegramNotifier(String botToken, String chatId) {
        this.botToken = botToken == null ? "" : botToken.trim();
        this.chatId = chatId == null ? "" : chatId.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isConfigured() {
        return !botToken.isBlank() && !chatId.isBlank();
    }

    public String formatSignalAlert(TradeSignal signal) {
        String action = safeUpper(signal.action(), "NO_TRADE");
        String directionTag = switch (action) {
            case "BUY" -> "🟢 BUY";
            case "SELL" -> "🔴 SELL";
            default -> "⚪ " + action;
        };

        String entryZone = safeText(signal.entryZone(), "N/A");
        double sl = signal.stopLoss();
        double tp1 = signal.takeProfit1();
        double tp2 = signal.takeProfit2();
        String atrRiskNote = safeText(signal.atrRiskNote(), "None provided");
        String reasoning = safeText(signal.reasoning(), "No rationale provided");
        String timestampUtc = ZonedDateTime.now(ZoneOffset.UTC).format(ALERT_TIME_FORMAT);

        String rrSummary = formatRiskReward(action, entryZone, sl, tp1, tp2);

        return """
                <b>XAU/USD M15 Signal — %s</b>
                <b>Confidence:</b> %d/100 | <b>Time:</b> %s

                <b>Entry Zone:</b> <code>%s</code>
                <b>Stop Loss (SL):</b> <code>%s</code>
                <b>Take Profit 1 (TP1):</b> <code>%s</code>
                <b>Take Profit 2 (TP2):</b> <code>%s</code>
                <b>Risk / Reward:</b> %s

                <b>ATR &amp; Risk Note:</b> %s
                <b>Rationale:</b> %s
                """.formatted(
                escapeHtml(directionTag),
                signal.confidence(),
                escapeHtml(timestampUtc),
                escapeHtml(entryZone),
                formatPrice(sl),
                formatPrice(tp1),
                formatPrice(tp2),
                escapeHtml(rrSummary),
                escapeHtml(atrRiskNote),
                escapeHtml(reasoning)
        ).trim();
    }

    public void sendSignalAlert(TradeSignal signal) {
        if (signal == null) {
            return;
        }
        String message = formatSignalAlert(signal);
        if (!isConfigured()) {
            System.out.println("[Telegram Dry-Run]\n" + message);
            return;
        }

        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            String formBody = "chat_id=" + urlEncode(chatId)
                    + "&text=" + urlEncode(message)
                    + "&parse_mode=HTML"
                    + "&disable_web_page_preview=true";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.printf("Telegram send failed (HTTP %d): %s%n",
                        response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            System.err.println("Error sending Telegram alert: " + ex.getMessage());
        }
    }

    private String formatRiskReward(String action, String entryZone, double sl, double tp1, double tp2) {
        Double referenceEntry = extractReferenceEntry(entryZone, action, sl, tp1);
        if (referenceEntry == null || sl <= 0.0) {
            return "N/A";
        }

        double risk = Math.abs(referenceEntry - sl);
        if (risk < 1e-6) {
            return "N/A (SL too close to entry)";
        }

        boolean validDirection = switch (action) {
            case "BUY" -> referenceEntry > sl;
            case "SELL" -> referenceEntry < sl;
            default -> false;
        };
        if (!validDirection) {
            return String.format(Locale.ROOT, "Check levels (risk=%.2f)", risk);
        }

        StringBuilder parts = new StringBuilder();
        if (tp1 > 0.0) {
            double reward1 = Math.abs(tp1 - referenceEntry);
            parts.append(String.format(Locale.ROOT, "TP1 1:%.2f ($%.2f risk)", reward1 / risk, risk));
        }
        if (tp2 > 0.0) {
            double reward2 = Math.abs(tp2 - referenceEntry);
            if (!parts.isEmpty()) {
                parts.append(" | ");
            }
            parts.append(String.format(Locale.ROOT, "TP2 1:%.2f", reward2 / risk));
        }

        return parts.isEmpty() ? "N/A" : parts.toString();
    }

    private Double extractReferenceEntry(String entryZone, String action, double sl, double tp1) {
        Matcher matcher = NUMBER_PATTERN.matcher(entryZone);
        double sum = 0.0;
        int count = 0;
        while (matcher.find()) {
            try {
                sum += Double.parseDouble(matcher.group());
                count++;
            } catch (NumberFormatException ignored) {
            }
        }
        if (count > 0) {
            return sum / count;
        }
        if (tp1 > 0.0 && sl > 0.0) {
            return "BUY".equals(action) ? Math.max(sl, tp1 - (tp1 - sl) * 0.5) : Math.min(sl, tp1 + (sl - tp1) * 0.5);
        }
        return null;
    }

    private String formatPrice(double value) {
        return value > 0.0 ? String.format(Locale.ROOT, "%.2f", value) : "N/A";
    }

    private String safeText(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private String safeUpper(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}