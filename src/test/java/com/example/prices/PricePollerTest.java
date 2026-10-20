package com.example.prices;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricePollerTest {
    @TempDir Path directory;

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test
    void databaseFailureReplaysSameSampleAfterRestart() throws Exception {
        var properties = PriceServiceTest.properties("http://localhost", directory);
        var store = new PollStateStore(properties, new ObjectMapper().findAndRegisterModules());
        var client = mock(BinanceClient.class);
        var repository = mock(PriceRepository.class);
        var clock = new MutableClock();
        var sample = new PriceSample(UUID.randomUUID(), "BNBUSDT", new BigDecimal("10.2"), clock.instant());
        when(client.fetch()).thenReturn(sample);
        doThrow(new DataAccessResourceFailureException("offline")).doNothing().when(repository).save(sample);
        new PricePoller(client, repository, store, clock, properties).poll();
        assertThat(store.read().pending()).isEqualTo(sample);
        new PricePoller(client, repository, store, clock, properties).poll();
        verify(repository, times(2)).save(sample);
        verify(client, times(1)).fetch();
        assertThat(store.read().pending()).isNull();
    }

    @Test
    void cooldownSurvivesRestartAndThenRecovers() throws Exception {
        var properties = PriceServiceTest.properties("http://localhost", directory);
        var store = new PollStateStore(properties, new ObjectMapper().findAndRegisterModules());
        var client = mock(BinanceClient.class);
        var repository = mock(PriceRepository.class);
        var clock = new MutableClock();
        when(client.fetch()).thenThrow(new BinanceException("429", Duration.ofSeconds(120)))
                .thenReturn(new PriceSample(UUID.randomUUID(), "BNBUSDT", BigDecimal.ONE, clock.instant()));
        new PricePoller(client, repository, store, clock, properties).poll();
        var restarted = new PricePoller(client, repository, store, clock, properties);
        clock.now = clock.now.plusSeconds(119);
        restarted.poll();
        verify(client, times(1)).fetch();
        verifyNoInteractions(repository);
        clock.now = clock.now.plusSeconds(1);
        restarted.poll();
        verify(client, times(2)).fetch();
        verify(repository).save(any());
        assertThat(store.read().failures()).isZero();
    }
}
