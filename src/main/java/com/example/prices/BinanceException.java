package com.example.prices;

import java.time.Duration;

public class BinanceException extends RuntimeException {
    private final Duration retryAfter;

    public BinanceException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() { return retryAfter; }
}
