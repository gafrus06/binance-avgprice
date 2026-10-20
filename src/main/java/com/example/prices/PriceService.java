package com.example.prices;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PriceService {
    private final PriceRepository repository;
    private final String symbol;

    public PriceService(PriceRepository repository, BinanceProperties properties) {
        this.repository = repository;
        this.symbol = properties.symbol();
    }

    public record PriceView(String symbol, BigDecimal price,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant fetchedAt) {
        static PriceView of(PriceSample sample) {
            return new PriceView(sample.symbol(), sample.price(), sample.fetchedAt());
        }
    }
    public record AverageView(BigDecimal averagePrice) {}
    public record HistoryView(List<PriceView> items, int page, int size, long totalElements, boolean hasNext) {}
    public record TrendView(String symbol, @JsonProperty("start_date") LocalDate startDate,
                            @JsonProperty("end_date") LocalDate endDate, Trend trend) {}
    public enum Trend { UPWARD, DOWNWARD, FLAT }

    public PriceView latest() {
        return PriceView.of(repository.latest(symbol).orElseThrow(PriceService::notFound));
    }

    public AverageView average(Instant from, Instant to) {
        validateRange(from, to);
        BigDecimal average = repository.average(symbol, from, to);
        if (average == null) throw notFound();
        return new AverageView(average.setScale(18, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    public HistoryView history(Instant from, Instant to, int page, int size) {
        validateRange(from, to);
        if (page < 0 || size < 1 || size > 1000) {
            throw new IllegalArgumentException("page must be >= 0; size must be between 1 and 1000");
        }
        long count = repository.count(symbol, from, to);
        var items = repository.history(symbol, from, to, page, size).stream().map(PriceView::of).toList();
        return new HistoryView(items, page, size, count, ((long) page + 1) * size < count);
    }

    public TrendView trend(Instant from, Instant to) {
        validateRange(from, to);
        PriceSample first = repository.boundary(symbol, from, to, true).orElseThrow(PriceService::notFound);
        PriceSample last = repository.boundary(symbol, from, to, false).orElseThrow(PriceService::notFound);
        return new TrendView(symbol, from.atOffset(ZoneOffset.UTC).toLocalDate(),
                to.atOffset(ZoneOffset.UTC).toLocalDate(), direction(first.price(), last.price()));
    }

    public static Trend direction(BigDecimal first, BigDecimal last) {
        int comparison = last.compareTo(first);
        return comparison > 0 ? Trend.UPWARD : comparison < 0 ? Trend.DOWNWARD : Trend.FLAT;
    }

    static void validateRange(Instant from, Instant to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("from and to are required; from must be <= to");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved prices in the requested range");
    }
}
