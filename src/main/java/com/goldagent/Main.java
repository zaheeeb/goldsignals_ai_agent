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

        MarketDataTools marketTools = new MarketDataTools(twelveDataApiKey);
        CalendarTools calendarTools = new CalendarTools(newsCalendarUrl);
        AgentRunner agentRunner = new AgentRunner(geminiApiKey, marketTools, calendarTools);
        TelegramNotifier telegramNotifier = new TelegramNotifier(telegramBotToken, telegramChatId);

        if (!telegramNotifier.isConfigured()) {
            System.out.println("WARNING: TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID missing; alerts will log locally only.");
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "xauusd-m15-scheduler");
            t.setDaemon(false);
            return t;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Gold M15 Signal Agent...");
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

        scheduleNextM15Tick(scheduler, agentRunner, telegramNotifier);
        System.out.println("Gold M15 Signal Agent started. Waiting for next M15 close (+2s)...");
    }

    private static void scheduleNextM15Tick(
            ScheduledExecutorService scheduler,
            AgentRunner agentRunner,
            TelegramNotifier telegramNotifier
    ) {
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime nextRunUtc = computeNextM15PlusTwoSeconds(nowUtc);
        long delaySeconds = Math.max(1L, Duration.between(nowUtc, nextRunUtc).getSeconds());

        System.out.printf("Next M15 evaluation at %s UTC (in %d seconds)%n", nextRunUtc, delaySeconds);

        scheduler.schedule(() -> {
            try {
                runEvaluationCycle(agentRunner, telegramNotifier);
            } finally {
                if (!scheduler.isShutdown()) {
                    scheduleNextM15Tick(scheduler, agentRunner, telegramNotifier);
                }
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    static ZonedDateTime computeNextM15PlusTwoSeconds(ZonedDateTime nowUtc) {
        ZonedDateTime truncatedMinute = nowUtc.truncatedTo(ChronoUnit.MINUTES);
        int minute = truncatedMinute.getMinute();
        int nextQuarterMinute = ((minute / 15) + 1) * 15;

        ZonedDateTime candidate = truncatedMinute
                .withMinute(0)
                .plusMinutes(nextQuarterMinute)
                .plusSeconds(2);

        if (!candidate.isAfter(nowUtc)) {
            candidate = candidate.plusMinutes(15);
        }
        return candidate;
    }

    private static void runEvaluationCycle(AgentRunner agentRunner, TelegramNotifier telegramNotifier) {
        if (!RUNNING.compareAndSet(false, true)) {
            System.out.println("Skipping M15 cycle: previous evaluation is still running.");
            return;
        }

        try {
            Instant cycleStart = Instant.now();
            System.out.printf("[%s] Evaluating XAU/USD M15 candle...%n", cycleStart);

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
                System.out.printf(">>> ALERT TRIGGERED: %s (confidence=%d%%) | %s%n",
                        signal.action(), signal.confidence(), signal.atrRiskNote());
                telegramNotifier.sendSignalAlert(signal);
            } else {
                System.out.println("No new alert dispatched (NO_TRADE, below confidence threshold, or cooldown active).");
            }
        } catch (Exception ex) {
            System.err.println("ERROR during M15 evaluation cycle: " + ex.getMessage());
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