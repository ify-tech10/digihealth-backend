package com.digihealth.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.digihealth.common.ApiException;

/*
 * Slows down password guessing: after 5 wrong passwords for one email within
 * 15 minutes, that email is refused until the oldest attempt is 15 minutes old.
 * Kept in memory, which is fine for a single server.
 */
@Component
public class LoginAttempts {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public void check(String email) {
        Deque<Instant> times = failures.get(email);
        if (times == null) {
            return;
        }
        synchronized (times) {
            prune(times, Instant.now());
            if (times.size() >= MAX_FAILURES) {
                long minutes = Math.max(1,
                    Duration.between(Instant.now(), times.peekFirst().plus(WINDOW)).toMinutes() + 1);
                throw ApiException.tooManyRequests(
                    "Too many sign-in attempts. Please wait " + minutes + " minute" + (minutes == 1 ? "" : "s") + " and try again.");
            }
        }
    }

    public void failed(String email) {
        Deque<Instant> times = failures.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(Instant.now());
        }
    }

    public void succeeded(String email) {
        failures.remove(email);
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void cleanUp() {
        Instant now = Instant.now();
        failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
    }

    private static void prune(Deque<Instant> times, Instant now) {
        while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(WINDOW))) {
            times.pollFirst();
        }
    }
}
