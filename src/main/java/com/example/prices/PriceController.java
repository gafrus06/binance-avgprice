package com.example.prices;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/prices")
public class PriceController {
    private final PriceService service;

    public PriceController(PriceService service) { this.service = service; }

    @GetMapping("/latest")
    public PriceService.PriceView latest() { return service.latest(); }

    @GetMapping("/average")
    public PriceService.AverageView average(@RequestParam Instant from, @RequestParam Instant to) {
        return service.average(from, to);
    }

    @GetMapping
    public PriceService.HistoryView history(@RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.history(from, to, page, size);
    }

    @GetMapping("/trend")
    public PriceService.TrendView trend(@RequestParam Instant from, @RequestParam Instant to) {
        return service.trend(from, to);
    }
}
