package com.example.prices;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PriceRepository {
    private static final String RANGE = " WHERE symbol = ? AND fetched_at >= ? AND fetched_at <= ? ";
    private static final RowMapper<PriceSample> MAPPER = (rs, row) -> new PriceSample(
            rs.getObject("id", UUID.class), rs.getString("symbol"), rs.getBigDecimal("price"),
            rs.getTimestamp("fetched_at").toInstant());
    private final JdbcTemplate jdbc;

    public PriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(PriceSample sample) {
        // Replaying a durable pending sample after an uncertain commit is safe.
        jdbc.update("INSERT INTO price_samples(id, symbol, price, fetched_at) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                sample.id(), sample.symbol(), sample.price(), Timestamp.from(sample.fetchedAt()));
    }

    public Optional<PriceSample> latest(String symbol) {
        return jdbc.query("SELECT * FROM price_samples WHERE symbol = ? ORDER BY fetched_at DESC, id DESC LIMIT 1",
                MAPPER, symbol).stream().findFirst();
    }

    public BigDecimal average(String symbol, Instant from, Instant to) {
        return jdbc.queryForObject("SELECT avg(price) FROM price_samples" + RANGE,
                BigDecimal.class, symbol, Timestamp.from(from), Timestamp.from(to));
    }

    public long count(String symbol, Instant from, Instant to) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM price_samples" + RANGE,
                Long.class, symbol, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    public List<PriceSample> history(String symbol, Instant from, Instant to, int page, int size) {
        return jdbc.query("SELECT * FROM price_samples" + RANGE
                        + "ORDER BY fetched_at ASC, id ASC LIMIT ? OFFSET ?", MAPPER,
                symbol, Timestamp.from(from), Timestamp.from(to), size, (long) page * size);
    }

    public Optional<PriceSample> boundary(String symbol, Instant from, Instant to, boolean first) {
        String direction = first ? "ASC" : "DESC";
        return jdbc.query("SELECT * FROM price_samples" + RANGE
                        + "ORDER BY fetched_at " + direction + ", id " + direction + " LIMIT 1",
                MAPPER, symbol, Timestamp.from(from), Timestamp.from(to)).stream().findFirst();
    }
}
