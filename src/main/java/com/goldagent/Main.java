package com.goldagent;

import com.goldagent.model.TradeSignal;
import com.goldagent.notify.TelegramNotifier;
import com.goldagent.tools.CalendarTools;
import com.goldagent.tools.MarketDataTools;
import io.github.cdimascio.dotenv.Dotenv;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    private static final int DEFAULT_EVALUATION_INTERVAL_MINUTES = 15; // Supports 15, 30, or 60
    private static final long POST_CLOSE_DELAY_SECONDS = 2L;
    private static final int COOLDOWN_MINUTES = 45;
    private static final int MIN_CONFIDENCE = 70;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String lastAlertedAction = "NONE";
    private static volatile Instant lastAlertedAt = Instant.EPOCH;

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String geminiApiKey = firstNonBlank(
                dotenv.get("GEMINI_API_KEY"),
                System.getenv("GEMINI_API_KEY")
        );
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            System.err.println("ERROR: GEMINI_API_KEY is not set in .env or environment.");
            System.exit(1);
        }

        String twelveDataApiKey = firstNonBlank(
                dotenv.get("TWELVE_DATA_API_KEY"),
                System.getenv("TWELVE_DATA_API_KEY")
        );
        if (twelveDataApiKey == null) {
            twelveDataApiKey = "";
        }

        String newsCalendarUrl = firstNonBlank(
                dotenv.get("NEWS_CALENDAR_URL"),
                System.getenv("NEWS_CALENDAR_URL")
        );
        if (newsCalendarUrl == null) {
            newsCalendarUrl = "";
        }

        String telegramBotToken = firstNonBlank(
                dotenv.get("TELEGRAM_BOT_TOKEN"),
                System.getenv("TELEGRAM_BOT_TOKEN")
        );
        if (telegramBotToken == null) {
            telegramBotToken = "";
        }

        String telegramChatId = firstNonBlank(
                dotenv.get("TELEGRAM_CHAT_ID"),
                System.getenv("TELEGRAM_CHAT_ID")
        );
        if (telegramChatId == null) {
            telegramChatId = "";
        }

        int evaluationIntervalMinutes = parseIntervalMinutes(
                firstNonBlank(
                        dotenv.get("EVALUATION_INTERVAL_MINUTES"),
                        System.getenv("EVALUATION_INTERVAL_MINUTES")
                ),
                DEFAULT_EVALUATION_INTERVAL_MINUTES
        );

        MarketDataTools marketTools = new MarketDataTools(twelveDataApiKey);
        CalendarTools calendarTools = new CalendarTools(newsCalendarUrl);
        AgentRunner agentRunner = new AgentRunner(geminiApiKey, marketTools, calendarTools);
        TelegramNotifier telegramNotifier = new TelegramNotifier(telegramBotToken, telegramChatId);

        if (!telegramNotifier.isConfigured()) {
            System.out.println("WARNING: TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID missing; alerts will log locally only.");
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "xauusd-signal-scheduler");
            t.setDaemon(false);
            return t;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Gold Signal Agent...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));

        scheduleNextTick(scheduler, agentRunner, telegramNotifier, evaluationIntervalMinutes);
        System.out.printf(
                "Gold Signal Agent started (interval=%dm, +%ds delay). Waiting for next candle close...%n",
                evaluationIntervalMinutes,
                POST_CLOSE_DELAY_SECONDS
        );
    }

    private static void scheduleNextTick(
            ScheduledExecutorService scheduler,
            AgentRunner agentRunner,
            TelegramNotifier telegramNotifier,
            int intervalMinutes
    ) {
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime nextRunUtc = nextCandleClosePlusDelay(nowUtc, intervalMinutes, POST_CLOSE_DELAY_SECONDS);
        long delaySeconds = Math.max(1L, Duration.between(nowUtc, nextRunUtc).getSeconds());

        System.out.printf("Next evaluation at %s UTC (in %d seconds)%n", nextRunUtc, delaySeconds);

        scheduler.schedule(() -> {
            try {
                runEvaluationCycle(agentRunner, telegramNotifier);
            } finally {
                if (!scheduler.isShutdown()) {
                    scheduleNextTick(scheduler, agentRunner, telegramNotifier, intervalMinutes);
                }
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    static ZonedDateTime nextCandleClosePlusDelay(
            ZonedDateTime nowUtc,
            int intervalMinutes,
            long delaySeconds
    ) {
        ZonedDateTime baseHour = nowUtc.truncatedTo(ChronoUnit.HOURS);
        int minuteOfHour = nowUtc.getMinute();
        int nextSlotMinute = ((minuteOfHour / intervalMinutes) + 1) * intervalMinutes;

        ZonedDateTime candidate = baseHour
                .plusMinutes(nextSlotMinute)
                .plusSeconds(delaySeconds);

        if (!candidate.isAfter(nowUtc)) {
            candidate = candidate.plusMinutes(intervalMinutes);
        }
        return candidate;
    }

    private static int parseIntervalMinutes(String rawValue, int fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            if (parsed == 15 || parsed == 30 || parsed == 60) {
                return parsed;
            }
            System.err.printf(
                    "WARNING: Unsupported EVALUATION_INTERVAL_MINUTES=%d; using %d minutes.%n",
                    parsed,
                    fallback
            );
            return fallback;
        } catch (NumberFormatException ex) {
            System.err.printf(
                    "WARNING: Invalid EVALUATION_INTERVAL_MINUTES='%s'; using %d minutes.%n",
                    rawValue,
                    fallback
            );
            return fallback;
        }
    }

    private static void runEvaluationCycle(AgentRunner agentRunner, TelegramNotifier telegramNotifier) {
        if (!RUNNING.compareAndSet(false, true)) {
            System.out.println("Skipping evaluation cycle: previous evaluation is still running.");
            return;
        }

        try {
            Instant cycleStart = Instant.now();
            System.out.printf("[%s] Evaluating XAU/USD candle...%n", cycleStart);

            TradeSignal signal = agentRunner.evaluateM15Close();
            System.out.printf("Signal result: action=%s, confidence=%d, entry=%s, sl=%s, tp1=%s, tp2=%s | %s%n",
                    signal.action(),
                    signal.confidence(),
                    signal.entryZone(),
                    signal.stopLoss(),
                    signal.takeProfit1(),
                    signal.takeProfit2(),
                    signal.reasoning()
            );

            Instant now = Instant.now();
            if (shouldAlert(signal, now)) {
                lastAlertedAction = signal.action().trim().toUpperCase();
                lastAlertedAt = now;
                String formattedAlert = String.format(
                        "XAU/USD %s | Confidence: %d/100 | Entry: %s | SL: %s | TP1: %s | TP2: %s | Risk: %s",
                        signal.action(),
                        signal.confidence(),
                        signal.entryZone(),
                        signal.stopLoss(),
                        signal.takeProfit1(),
                        signal.takeProfit2(),
                        signal.atrRiskNote()
                );
                System.out.println(">>> ALERT TRIGGERED: " + formattedAlert);
                telegramNotifier.sendSignalAlert(signal);
            } else {
                System.out.printf(
                        "No new alert dispatched (action=%s, confidence=%d%%, minConfidence=%d%%, cooldown=%dm)%n",
                        signal.action(), signal.confidence(), MIN_CONFIDENCE, COOLDOWN_MINUTES
                );
            }
        } catch (Exception ex) {
            System.err.println("ERROR during evaluation cycle: " + ex.getMessage());
            ex.printStackTrace(System.err);
        } finally {
            RUNNING.set(false);
        }
    }

    static boolean shouldAlert(TradeSignal signal, Instant now) {
        if (signal == null || signal.action() == null) {
            return false;
        }
        String action = signal.action().trim().toUpperCase();
        if (!"BUY".equals(action) && !"SELL".equals(action)) {
            return false;
        }
        if (signal.confidence() < MIN_CONFIDENCE) {
            return false;
        }

        boolean sameDirection = Objects.equals(lastAlertedAction, action);
        Duration elapsed = Duration.between(lastAlertedAt, now);
        boolean withinCooldown = elapsed.toMinutes() < COOLDOWN_MINUTES;

        return !(sameDirection && withinCooldown);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}