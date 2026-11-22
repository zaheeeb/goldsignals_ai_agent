package com.goldagent.notify;

import com.goldagent.model.TradeSignal;
import io.github.cdimascio.dotenv.Dotenv;

public class TelegramVerify {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String token = dotenv.get("TELEGRAM_BOT_TOKEN", System.getenv("TELEGRAM_BOT_TOKEN"));
        String chatId = dotenv.get("TELEGRAM_CHAT_ID", System.getenv("TELEGRAM_CHAT_ID"));

        TelegramNotifier notifier = new TelegramNotifier(token, chatId);
        if (!notifier.isConfigured()) {
            System.err.println("Missing TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID.");
            return;
        }

        TradeSignal testSignal = new TradeSignal(
                "BUY",
                78,
                "2650.00 - 2652.50",
                2642.00,
                2662.00,
                2672.00,
                "ATR(14) normal; size conservatively",
                "Test verification alert for XAU/USD M15 setup"
        );

        notifier.sendSignalAlert(testSignal);
        System.out.println("Test alert dispatch finished.");
    }
}