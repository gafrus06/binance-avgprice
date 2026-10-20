package com.example.prices;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "binance.enabled", havingValue = "true", matchIfMissing = true)
public class PricePoller {
    private static final Logger LOG = LoggerFactory.getLogger(PricePoller.class);
    private final BinanceClient client;
    private final PriceRepository repository;
    private final PollStateStore store;
    private final Clock clock;
    private final BinanceProperties properties;
    private PollStateStore.State state;

    public PricePoller(BinanceClient client, PriceRepository repository, PollStateStore store,
                       Clock clock, BinanceProperties properties) {
        this.client = client;
        this.repository = repository;
        this.store = store;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${binance.poll-interval}")
    public synchronized void poll() {
        try {
            if (state == null) state = store.read();
            // Persist in-memory state again if a previous disk write failed; never fetch over it.
            store.write(state);
            if (state.pending() != null) {
                repository.save(state.pending());
                state = new PollStateStore.State(null, state.nextAttempt(), state.failures());
                store.write(state);
                return;
            }
            if (clock.instant().isBefore(state.nextAttempt())) return;
            try {
                PriceSample sample = client.fetch();
                state = new PollStateStore.State(sample, clock.instant().plus(properties.pollInterval()), 0);
                store.write(state);
                repository.save(sample);
                state = new PollStateStore.State(null, state.nextAttempt(), 0);
                store.write(state);
                LOG.info("Stored price for {} fetched at {}", sample.symbol(), sample.fetchedAt());
            } catch (BinanceException exception) {
                int failures = Math.min(state.failures() + 1, 10);
                Duration backoff = Duration.ofSeconds(Math.min(300, 30L << (failures - 1)));
                Duration delay = backoff.compareTo(exception.retryAfter()) > 0 ? backoff : exception.retryAfter();
                state = new PollStateStore.State(null, clock.instant().plus(delay), failures);
                store.write(state);
                LOG.warn("{}; polling paused until {}", exception.getMessage(), state.nextAttempt());
            }
        } catch (Exception exception) {
            // Keep pending state for the next tick. No overlapping requests and no unbounded retry loop.
            LOG.error("Polling failed; state retained for retry", exception);
        }
    }
}
