package com.example.prices;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("binance")
public record BinanceProperties(URI baseUrl, String symbol, Duration pollInterval,
                                Duration connectTimeout, Duration readTimeout,
                                boolean enabled, Path stateDir) {
    public BinanceProperties {
        if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("BINANCE_BASE_URL must be an HTTP(S) URL");
        }
        if (symbol == null || !symbol.matches("[A-Z0-9]{2,32}")) {
            throw new IllegalArgumentException("BINANCE_SYMBOL must contain 2..32 uppercase letters/digits");
        }
        if (pollInterval == null || pollInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("BINANCE_POLL_INTERVAL must be an ISO-8601 duration >= PT1S");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero() || stateDir == null) {
            throw new IllegalArgumentException("Positive HTTP timeouts and state directory are required");
        }
    }
}
