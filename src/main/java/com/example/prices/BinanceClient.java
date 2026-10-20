package com.example.prices;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BinanceClient {
    private static final Logger LOG = LoggerFactory.getLogger(BinanceClient.class);
    private final BinanceProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http;

    public BinanceClient(BinanceProperties properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public PriceSample fetch() {
        var uri = URI.create(properties.baseUrl().toString().replaceAll("/$", "")
                + "/api/v3/avgPrice?symbol=" + properties.symbol());
        var request = HttpRequest.newBuilder(uri).timeout(properties.readTimeout()).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Instant fetchedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            response.headers().map().forEach((name, values) -> {
                if (name.startsWith("x-mbx-used-weight")) LOG.debug("{}={}", name, values);
            });
            int status = response.statusCode();
            if (status != 200) {
                String code = "";
                try { code = mapper.readTree(response.body()).path("code").asText(); }
                catch (IOException ignored) { /* Some WAF responses are HTML, not JSON. */ }
                Duration delay = status == 418 ? Duration.ofDays(3)
                        : status == 429 ? Duration.ofMinutes(1)
                        : status >= 400 && status < 500 ? Duration.ofMinutes(5) : Duration.ofSeconds(30);
                delay = retryAfter(response.headers().firstValue("Retry-After").orElse(null), delay);
                throw new BinanceException("Binance HTTP " + status + ", code=" + code, delay);
            }
            var json = mapper.readTree(response.body());
            var priceNode = json == null ? null : json.get("price");
            if (priceNode == null || !priceNode.isTextual()) {
                throw new BinanceException("Missing or invalid Binance price", Duration.ofSeconds(30));
            }
            BigDecimal price = new BigDecimal(priceNode.textValue());
            if (price.signum() <= 0 || price.precision() > 100 || Math.abs((long) price.scale()) > 100) {
                throw new BinanceException("Binance price outside supported range", Duration.ofSeconds(30));
            }
            return new PriceSample(UUID.randomUUID(), properties.symbol(), price, fetchedAt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BinanceException("Binance request interrupted", Duration.ofSeconds(30));
        } catch (IOException | NumberFormatException exception) {
            throw new BinanceException("Binance transport or response failure: "
                    + exception.getClass().getSimpleName(), Duration.ofSeconds(30));
        }
    }

    private Duration retryAfter(String value, Duration fallback) {
        if (value == null) return fallback;
        try {
            Duration delay = Duration.ofSeconds(Long.parseLong(value));
            return delay.isNegative() ? fallback : delay;
        } catch (RuntimeException ignored) {
            try {
                Duration delay = Duration.between(clock.instant(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return delay.isNegative() ? fallback : delay;
            } catch (RuntimeException invalid) {
                return fallback;
            }
        }
    }
}
