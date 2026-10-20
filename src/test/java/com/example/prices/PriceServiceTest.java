package com.example.prices;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceServiceTest {
    static BinanceProperties properties(String url, Path state) {
        return new BinanceProperties(URI.create(url), "BNBUSDT", Duration.ofSeconds(30),
                Duration.ofSeconds(2), Duration.ofSeconds(3), true, state);
    }

    @ParameterizedTest
    @CsvSource({"1,2,UPWARD", "2,1,DOWNWARD", "1.0,1.00,FLAT",
            "100000000000000000.00000001,100000000000000000.00000002,UPWARD"})
    void comparesWithoutFloatingPointLoss(String first, String last, PriceService.Trend expected) {
        assertThat(PriceService.direction(new BigDecimal(first), new BigDecimal(last))).isEqualTo(expected);
    }

    @Test
    void validatesRangeAndPaginationBeforeQuerying() {
        PriceRepository repository = mock(PriceRepository.class);
        PriceService service = new PriceService(repository, properties("http://localhost", Path.of("data")));
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        assertThatThrownBy(() -> service.average(now, now.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history(now, now, -1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history(now, now, 0, 1001)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void emptyLatestIs404() {
        PriceRepository repository = mock(PriceRepository.class);
        when(repository.latest("BNBUSDT")).thenReturn(Optional.empty());
        PriceService service = new PriceService(repository, properties("http://localhost", Path.of("data")));
        assertThatThrownBy(service::latest).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void rejectsZeroPollInterval() {
        assertThatThrownBy(() -> new BinanceProperties(URI.create("http://localhost"), "BNBUSDT",
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), true, Path.of("data")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
