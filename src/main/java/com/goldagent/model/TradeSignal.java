package com.goldagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeSignal(
        String action,          // "BUY", "SELL", or "NO_TRADE"
        int confidence,         // 0 to 100
        String entryZone,
        Double stopLoss,
        Double takeProfit1,
        Double takeProfit2,
        String atrRiskNote,
        String reasoning
) {}