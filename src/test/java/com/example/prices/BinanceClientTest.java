package com.example.prices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

class BinanceClientTest {
    @RegisterExtension
    static WireMockExtension server = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();
    private final Instant now = Instant.parse("2026-09-01T12:30:15.123Z");

    private BinanceClient client() {
        return new BinanceClient(PriceServiceTest.properties(server.baseUrl(), Path.of("data")),
                new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void fetchesExactDecimalAndUsesReceiptTime() {
        server.stubFor(get(urlEqualTo("/api/v3/avgPrice?symbol=BNBUSDT")).willReturn(okJson(
                "{\"mins\":5,\"price\":\"9.357518340000000001\",\"closeTime\":1}")));
        PriceSample sample = client().fetch();
        assertThat(sample.price()).isEqualByComparingTo("9.357518340000000001");
        assertThat(sample.fetchedAt()).isEqualTo(Instant.parse("2026-09-01T12:30:15Z"));
        assertThat(sample.symbol()).isEqualTo("BNBUSDT");
    }

    @ParameterizedTest
    @CsvSource({"429,120", "418,86400", "503,45"})
    void honorsRetryAfter(int status, long seconds) {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(status)
                .withHeader("Retry-After", Long.toString(seconds)).withBody("{\"code\":-1003}")));
        assertThatThrownBy(() -> client().fetch()).isInstanceOfSatisfying(BinanceException.class,
                ex -> assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(seconds)));
    }

    @ParameterizedTest
    @CsvSource({"429,60", "418,259200", "400,300", "403,300", "500,30"})
    void usesSafeFallbackForMalformedHeader(int status, long seconds) {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(status)
                .withHeader("Retry-After", "invalid").withBody("<html>Error</html>")));
        assertThatThrownBy(() -> client().fetch()).isInstanceOfSatisfying(BinanceException.class,
                ex -> assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(seconds)));
    }

    @Test
    void rejectsMalformedPrice() {
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"price\":\"NaN\"}")));
        assertThatThrownBy(() -> client().fetch()).isInstanceOf(BinanceException.class);
    }

    @Test
    void timesOut() {
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"price\":\"1\"}").withFixedDelay(1000)));
        var properties = PriceServiceTest.properties(server.baseUrl(), Path.of("data"));
        var shortTimeout = new BinanceProperties(properties.baseUrl(), properties.symbol(),
                properties.pollInterval(), properties.connectTimeout(), Duration.ofMillis(100), true, properties.stateDir());
        var client = new BinanceClient(shortTimeout, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(client::fetch).isInstanceOf(BinanceException.class);
    }
}
