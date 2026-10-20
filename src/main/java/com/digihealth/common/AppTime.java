package com.digihealth.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** DiGi Health works on Lagos time (WAT, UTC+1) for "today" and date filters. */
public final class AppTime {

    public static final ZoneId ZONE = ZoneId.of("Africa/Lagos");

    private AppTime() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    public static Instant endOf(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    /** "2026-10-08" -> date; blank -> null; anything else -> 400. */
    public static LocalDate parseDate(String s, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("'" + field + "' must be a date like 2026-10-08.");
        }
    }
}
