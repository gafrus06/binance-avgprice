package com.example.prices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "binance.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class PriceApiIT {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static WireMockServer binance = new WireMockServer(wireMockConfig().dynamicPort());
    static { binance.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("binance.base-url", binance::baseUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired PriceRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @TempDir Path directory;
    static final String FROM = "2026-09-01T00:00:00Z";
    static final String TO = "2026-09-30T23:59:59Z";

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM price_samples");
        binance.resetAll();
    }
    @AfterAll static void stopWireMock() { binance.stop(); }

    @Test
    void fullPollingAndFourEndpointsWithInclusiveBoundaries() throws Exception {
        var properties = PriceServiceTest.properties(binance.baseUrl(), directory);
        binance.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(anyUrl())
                .willReturn(okJson("{\"mins\":5,\"price\":\"10.2\",\"closeTime\":1}")));
        Clock clock = Clock.fixed(Instant.parse(FROM), ZoneOffset.UTC);
        var client = new BinanceClient(properties, mapper, clock);
        new PricePoller(client, repository, new PollStateStore(properties, mapper), clock, properties).poll();
        assertThat(repository.count("BNBUSDT", Instant.parse(FROM), Instant.parse(TO))).isEqualTo(1);
        repository.save(new PriceSample(UUID.randomUUID(), "BNBUSDT", new BigDecimal("20.2"), Instant.parse(TO)));
        repository.save(new PriceSample(UUID.randomUUID(), "ETHUSDT", new BigDecimal("999"), Instant.parse(TO)));
        repository.save(new PriceSample(UUID.randomUUID(), "BNBUSDT", new BigDecimal("99"), Instant.parse(FROM).minusSeconds(1)));
        mvc.perform(get("/api/v1/prices/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(20.2)).andExpect(jsonPath("$.fetchedAt").value(TO));
        mvc.perform(get("/api/v1/prices/average").param("from", FROM).param("to", TO))
                .andExpect(status().isOk()).andExpect(jsonPath("$.averagePrice").value(15.2));
        mvc.perform(get("/api/v1/prices").param("from", FROM).param("to", TO).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].price").value(10.2)).andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/v1/prices").param("from", FROM).param("to", TO).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.items[0].price").value(20.2)).andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/v1/prices/trend").param("from", FROM).param("to", TO))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trend").value("UPWARD"))
                .andExpect(jsonPath("$.symbol").value("BNBUSDT"))
                .andExpect(jsonPath("$.start_date").value("2026-09-01"))
                .andExpect(jsonPath("$.end_date").value("2026-09-30"));
    }

    @Test
    void replayIsIdempotentAndDecimalIsExact() {
        var sample = new PriceSample(UUID.randomUUID(), "BNBUSDT",
                new BigDecimal("123456789012345678.123456789012345678"), Instant.parse(FROM));
        repository.save(sample);
        repository.save(sample);
        assertThat(repository.count("BNBUSDT", Instant.parse(FROM), Instant.parse(TO))).isEqualTo(1);
        assertThat(repository.latest("BNBUSDT").orElseThrow().price()).isEqualByComparingTo(sample.price());
    }

    @Test
    void emptyAndInvalidRequests() throws Exception {
        mvc.perform(get("/api/v1/prices/latest")).andExpect(status().isNotFound());
        for (String endpoint : new String[]{"average", "trend"}) {
            mvc.perform(get("/api/v1/prices/" + endpoint).param("from", FROM).param("to", TO))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/prices").param("from", FROM).param("to", TO))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/v1/prices").param("from", TO).param("to", FROM)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/prices").param("from", FROM).param("to", TO).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/prices").param("from", FROM).param("to", TO).param("size", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/prices/average").param("from", "garbage").param("to", TO))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/prices/average").param("from", FROM)).andExpect(status().isBadRequest());
    }

    @Test
    void oneSampleTrendIsFlatAndSameTimePagesAreStable() throws Exception {
        repository.save(new PriceSample(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "BNBUSDT", BigDecimal.ONE, Instant.parse(FROM)));
        mvc.perform(get("/api/v1/prices/trend").param("from", FROM).param("to", FROM))
                .andExpect(jsonPath("$.trend").value("FLAT"));
        repository.save(new PriceSample(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "BNBUSDT", BigDecimal.TEN, Instant.parse(FROM)));
        mvc.perform(get("/api/v1/prices").param("from", FROM).param("to", FROM).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.items[0].price").value(10));
    }
}
