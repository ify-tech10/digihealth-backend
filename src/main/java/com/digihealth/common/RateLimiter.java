package com.digihealth.common;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * Simple in-memory limit for public forms, keyed by something like
 * "care-request:" + IP. Fine for a single server.
 *   rateLimiter.check("care-request:" + ip, 5, Duration.ofMinutes(15));
 */
@Component
public class RateLimiter {

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** Counts this attempt; throws 429 if the key already used up its allowance. */
    public void check(String key, int max, Duration window) {
        Instant now = Instant.now();
        Deque<Instant> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(window))) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                throw ApiException.tooManyRequests("Too many submissions from this connection. Please try again later.");
            }
            times.addLast(now);
        }
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000)
    public void cleanUp() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(cutoff);
            }
        });
    }
}
