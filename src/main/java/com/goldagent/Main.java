package com.goldagent;

import com.goldagent.model.TradeSignal;
import com.goldagent.notify.TelegramNotifier;
import com.goldagent.tools.CalendarTools;
import com.goldagent.tools.MarketDataTools;
import io.github.cdimascio.dotenv.Dotenv;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    private static final int DEFAULT_EVALUATION_INTERVAL_MINUTES = 5;
    private static final long POST_CLOSE_DELAY_SECONDS = 20L;
    private static final int COOLDOWN_MINUTES = 15;
    private static final int MIN_CONFIDENCE = 65;
    private static final long STARTUP_DELAY_MINUTES = 15L;

    // Active London + New York overlap/session window in UTC
    private static final int SESSION_START_HOUR_UTC = 7;
    private static final int SESSION_END_HOUR_UTC = 20;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String lastAlertedAction = "NONE";
    private static volatile Instant lastAlertedAt = Instant.EPOCH;

    private static final ZoneOffset DISPLAY_ZONE = ZoneOffset.ofHours(1);
    private static final DateTimeFormatter DISPLAY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC+1'", Locale.ROOT);

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String geminiApiKey = firstNonBlank(dotenv.get("GEMINI_API_KEY"), System.getenv("GEMINI_API_KEY"));
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            System.err.println("ERROR: GEMINI_API_KEY is not set in .env or environment.");
            System.exit(1);
        }

        String twelveDataApiKey = defaultEmpty(firstNonBlank(dotenv.get("TWELVE_DATA_API_KEY"), System.getenv("TWELVE_DATA_API_KEY")));
        String newsCalendarUrl = defaultEmpty(firstNonBlank(dotenv.get("NEWS_CALENDAR_URL"), System.getenv("NEWS_CALENDAR_URL")));
        String telegramBotToken = defaultEmpty(firstNonBlank(dotenv.get("TELEGRAM_BOT_TOKEN"), System.getenv("TELEGRAM_BOT_TOKEN")));
        String telegramChatId = defaultEmpty(firstNonBlank(dotenv.get("TELEGRAM_CHAT_ID"), System.getenv("TELEGRAM_CHAT_ID")));

        int evaluationIntervalMinutes = parseIntervalMinutes(
                firstNonBlank(dotenv.get("EVALUATION_INTERVAL_MINUTES"), System.getenv("EVALUATION_INTERVAL_MINUTES")),
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
            Thread t = new Thread(r, "xauusd-m5-scheduler");
            t.setDaemon(false);
            return t;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Gold M5/M15 Signal Agent...");
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

        if (STARTUP_DELAY_MINUTES > 0) {
            ZonedDateTime resumeAtPlusOne = ZonedDateTime.now(ZoneOffset.UTC)
                    .plusMinutes(STARTUP_DELAY_MINUTES)
                    .withZoneSameInstant(DISPLAY_ZONE);
            System.out.printf("Pausing startup for %d minutes; first schedule check at %s.%n",
                    STARTUP_DELAY_MINUTES, resumeAtPlusOne.format(DISPLAY_TIME_FORMAT));
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(STARTUP_DELAY_MINUTES));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        scheduleNextTick(scheduler, agentRunner, telegramNotifier, evaluationIntervalMinutes);
        System.out.printf(
                "Gold M5/M15 Signal Agent started (interval=%dm, +%ds settle, minConf=%d%%, cooldown=%dm, session=%02d:00-%02d:00 UTC).%n",
                evaluationIntervalMinutes, POST_CLOSE_DELAY_SECONDS, MIN_CONFIDENCE, COOLDOWN_MINUTES,
                SESSION_START_HOUR_UTC, SESSION_END_HOUR_UTC
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
        ZonedDateTime nextRunPlusOne = nextRunUtc.withZoneSameInstant(DISPLAY_ZONE);
        long delaySeconds = Math.max(1L, Duration.between(nowUtc, nextRunUtc).getSeconds());

        System.out.printf("Next M5 evaluation at %s (in %d seconds)%n",
                nextRunPlusOne.format(DISPLAY_TIME_FORMAT), delaySeconds);

        scheduler.schedule(() -> {
            try {
                ZonedDateTime tickUtc = ZonedDateTime.now(ZoneOffset.UTC);
                if (isActiveTradingSession(tickUtc)) {
                    runEvaluationCycle(agentRunner, telegramNotifier);
                } else {
                    String formattedTick = tickUtc.withZoneSameInstant(DISPLAY_ZONE).format(DISPLAY_TIME_FORMAT);
                    System.out.printf("[%s] Outside active London/NY session (%02d:00-%02d:00 UTC); skipping tick.%n",
                            formattedTick, SESSION_START_HOUR_UTC, SESSION_END_HOUR_UTC);
                }
            } finally {
                if (!scheduler.isShutdown()) {
                    scheduleNextTick(scheduler, agentRunner, telegramNotifier, intervalMinutes);
                }
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    static boolean isActiveTradingSession(ZonedDateTime utcTime) {
        DayOfWeek day = utcTime.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        int hour = utcTime.getHour();
        return hour >= SESSION_START_HOUR_UTC && hour < SESSION_END_HOUR_UTC;
    }

    static ZonedDateTime nextCandleClosePlusDelay(ZonedDateTime nowUtc, int intervalMinutes, long delaySeconds) {
        ZonedDateTime baseHour = nowUtc.truncatedTo(ChronoUnit.HOURS);
        int minuteOfHour = nowUtc.getMinute();
        int nextSlotMinute = ((minuteOfHour / intervalMinutes) + 1) * intervalMinutes;

        ZonedDateTime candidate = baseHour.plusMinutes(nextSlotMinute).plusSeconds(delaySeconds);
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
            if (parsed == 5 || parsed == 15 || parsed == 30 || parsed == 60) {
                return parsed;
            }
            return fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void runEvaluationCycle(AgentRunner agentRunner, TelegramNotifier telegramNotifier) {
        if (!RUNNING.compareAndSet(false, true)) {
            System.out.println("Skipping M5 cycle: previous evaluation is still running.");
            return;
        }

        try {
            Instant cycleStart = Instant.now();
            String formattedCycleStart = cycleStart.atZone(DISPLAY_ZONE).format(DISPLAY_TIME_FORMAT);
            System.out.printf("[%s] Evaluating XAU/USD M5 candle (M15 bias)...%n", formattedCycleStart);

            TradeSignal signal = agentRunner.evaluateM5Close();
            System.out.printf("Signal result: action=%s, confidence=%d, entry=%s, sl=%s, tp1=%s, tp2=%s | %s%n",
                    signal.action(), signal.confidence(), signal.entryZone(),
                    signal.stopLoss(), signal.takeProfit1(), signal.takeProfit2(), signal.reasoning());

            Instant now = Instant.now();
            if (shouldAlert(signal, now)) {
                lastAlertedAction = signal.action().trim().toUpperCase();
                lastAlertedAt = now;
                System.out.printf(">>> ALERT TRIGGERED: XAU/USD %s | Conf: %d%% | Entry: %s | SL: %s | TP1: %s | TP2: %s%n",
                        signal.action(), signal.confidence(), signal.entryZone(),
                        signal.stopLoss(), signal.takeProfit1(), signal.takeProfit2());
                telegramNotifier.sendSignalAlert(signal);
            } else {
                System.out.printf("No new alert dispatched (action=%s, confidence=%d%%, minConfidence=%d%%, cooldown=%dm)%n",
                        signal.action(), signal.confidence(), MIN_CONFIDENCE, COOLDOWN_MINUTES);
            }
        } catch (Exception ex) {
            System.err.println("ERROR during M5 evaluation cycle: " + ex.getMessage());
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
        boolean withinCooldown = Duration.between(lastAlertedAt, now).toMinutes() < COOLDOWN_MINUTES;
        return !(sameDirection && withinCooldown);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private static String defaultEmpty(String value) {
        return value == null ? "" : value;
    }
}