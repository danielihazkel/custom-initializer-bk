package com.menora.initializr.observability;

import com.menora.initializr.db.entity.GenerationEventEntity.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class GenerationMetrics {

    private final Map<Status, Counter> counters = new EnumMap<>(Status.class);
    private final Map<Status, Timer> timers = new EnumMap<>(Status.class);

    public GenerationMetrics(MeterRegistry registry) {
        for (Status s : Status.values()) {
            String tag = s.name().toLowerCase();
            counters.put(s, Counter.builder("menora.generation.count")
                    .description("Number of Spring project generations")
                    .tag("status", tag)
                    .register(registry));
            timers.put(s, Timer.builder("menora.generation.duration")
                    .description("Duration of Spring project generations")
                    .tag("status", tag)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry));
        }
    }

    public void record(Status status, long durationMs) {
        counters.get(status).increment();
        timers.get(status).record(durationMs, TimeUnit.MILLISECONDS);
    }
}
