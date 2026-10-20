package com.example.prices;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PriceSample(UUID id, String symbol, BigDecimal price, Instant fetchedAt) {}
