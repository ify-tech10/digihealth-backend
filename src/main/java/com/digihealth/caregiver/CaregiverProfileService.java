package com.digihealth.caregiver;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.carerequest.CareRequestDtos;
import com.digihealth.common.ApiException;
import com.digihealth.provider.Provider;
import com.digihealth.security.FieldCrypto;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/** My Availability, the availability switch and My Profile (incl. encrypted bank / BVN / NIN). */
@Service
public class CaregiverProfileService {

    private static final String AREA_RULE = "^[A-Z0-9_]{2,40}$";

    private final CaregiverSupport support;
    private final ProviderAvailabilityRepository availability;
    private final UserRepository users;
    private final FieldCrypto crypto;
    private final ActivityService activity;

    public CaregiverProfileService(CaregiverSupport support, ProviderAvailabilityRepository availability,
                                   UserRepository users, FieldCrypto crypto, ActivityService activity) {
        this.support = support;
        this.availability = availability;
        this.users = users;
        this.crypto = crypto;
        this.activity = activity;
    }

    /* ── DTOs ── */

    public record DayBody(@NotBlank String date, boolean available, String startTime, String endTime) {
    }

    public record MonthBody(@NotBlank(message = "Month is required") @Pattern(regexp = "^\\d{4}-\\d{2}$") String month,
                            String defaultStartTime, String defaultEndTime,
                            List<@Pattern(regexp = AREA_RULE, message = "Unknown area") String> areas,
                            @NotNull List<@Valid DayBody> days) {
    }

    public record DayView(String date, boolean available, String startTime, String endTime) {
    }

    public record MonthView(String month, String defaultStartTime, String defaultEndTime, List<String> areas,
                            List<DayView> days) {
    }

    public record StatusBody(@NotNull(message = "isAvailable is required") Boolean isAvailable) {
    }

    public record StatusView(boolean isAvailable, boolean available, List<String> areas, String schedule) {
    }

    public record ProfileView(Long id, String fullName, String email, String phoneNumber, String address,
                              String locationArea, String serviceProviderType, String status, List<String> coverageAreas,
                              String bankName, String accountName, String maskedAccountNumber, String maskedBvn,
                              String maskedNin) {
    }

    public record ProfileBody(
            @NotBlank(message = "Phone is required") @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phoneNumber,
            @Size(max = 300) String address,
            @Pattern(regexp = AREA_RULE, message = "Unknown area") String locationArea,
            @Size(max = 100) String bankName,
            @Size(max = 150) String accountName,
            @Pattern(regexp = "^\\d{10}$", message = "NUBAN account numbers have 10 digits") String accountNumber,
            @Pattern(regexp = "^\\d{11}$", message = "BVN has 11 digits") String bvn,
            @Pattern(regexp = "^\\d{11}$", message = "NIN has 11 digits") String nin) {
    }

    /* ── Availability ── */

    @Transactional(readOnly = true)
    public MonthView month(String monthText) {
        Provider me = support.me();
        YearMonth month = parseMonth(monthText);
        Map<LocalDate, ProviderAvailability> saved = new HashMap<>();
        availability.findByProviderIdAndDayBetweenOrderByDayAsc(me.getId(), month.atDay(1), month.atEndOfMonth())
            .forEach(a -> saved.put(a.getDay(), a));
        List<DayView> days = month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1)).map(d -> {
            ProviderAvailability a = saved.get(d);
            return a == null ? new DayView(d.toString(), false, null, null)
                : new DayView(d.toString(), a.isAvailable(), hhmm(a.getStartTime()), hhmm(a.getEndTime()));
        }).toList();
        return new MonthView(month.toString(), hhmm(start(me)), hhmm(end(me)), List.copyOf(coverage(me)), days);
    }

    @Transactional
    public MonthView saveMonth(MonthBody body) {
        Provider me = support.me();
        YearMonth month = parseMonth(body.month());
        LocalTime start = parseTime(body.defaultStartTime(), LocalTime.of(8, 0));
        LocalTime end = parseTime(body.defaultEndTime(), LocalTime.of(18, 0));
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("End time must be after start time.");
        }
        me.setDefaultStartTime(start);
        me.setDefaultEndTime(end);
        if (body.areas() != null) {
            me.setCoverageAreas(body.areas().isEmpty() ? null
                : String.join(",", body.areas().stream().map(String::trim).distinct().toList()));
        }

        Map<LocalDate, ProviderAvailability> saved = new HashMap<>();
        availability.findByProviderIdAndDayBetweenOrderByDayAsc(me.getId(), month.atDay(1), month.atEndOfMonth())
            .forEach(a -> saved.put(a.getDay(), a));
        for (DayBody d : body.days()) {
            LocalDate date = com.digihealth.common.AppTime.parseDate(d.date(), "date");
            if (!YearMonth.from(date).equals(month)) {
                throw ApiException.badRequest(d.date() + " isn't in " + month + ".");
            }
            ProviderAvailability a = saved.computeIfAbsent(date, x -> new ProviderAvailability(me.getId(), x));
            a.setAvailable(d.available());
            a.setStartTime(d.available() ? parseTime(d.startTime(), start) : null);
            a.setEndTime(d.available() ? parseTime(d.endTime(), end) : null);
            availability.save(a);
        }
        activity.record("AVAILABILITY_UPDATED", "Updated availability for " + month, "PROVIDER", me.getId());
        return month(body.month());
    }

    @Transactional(readOnly = true)
    public StatusView status() {
        Provider me = support.me();
        return statusOf(me);
    }

    @Transactional
    public StatusView setStatus(boolean available) {
        Provider me = support.me();
        me.setAvailable(available);
        activity.record(available ? "PROVIDER_AVAILABLE" : "PROVIDER_UNAVAILABLE",
            available ? "Marked themselves available" : "Marked themselves unavailable", "PROVIDER", me.getId());
        return statusOf(me);
    }

    /* ── Profile ── */

    @Transactional(readOnly = true)
    public ProfileView profile() {
        Provider me = support.me();
        User u = users.findById(me.getUserId()).orElseThrow();
        return new ProfileView(me.getId(), me.getFullName(), u.getEmail(), u.getPhoneNumber(), me.getAddress(),
            me.getLocationArea(), me.getProviderType() != null ? me.getProviderType() : me.getServiceType(),
            u.isActive() ? "ACTIVE" : "INACTIVE", List.copyOf(coverage(me)), me.getBankName(), me.getAccountName(),
            mask(me.getAccountLast4(), 10), mask(me.getBvnLast4(), 11), mask(me.getNinLast4(), 11));
    }

    @Transactional
    public ProfileView updateProfile(ProfileBody body) {
        Provider me = support.me();
        User u = users.findById(me.getUserId()).orElseThrow();
        String phone = body.phoneNumber().trim();
        me.setPhoneNumber(phone);
        u.setPhoneNumber(phone);
        me.setAddress(CaregiverSupport.blankToNull(body.address()));
        if (body.locationArea() != null) {
            me.setLocationArea(body.locationArea());
            u.setLocationArea(body.locationArea());
        }
        me.setBankName(CaregiverSupport.blankToNull(body.bankName()));
        me.setAccountName(CaregiverSupport.blankToNull(body.accountName()));

        /* Sensitive numbers: only replaced when a new value is sent; stored encrypted. */
        boolean changedIds = false;
        if (body.accountNumber() != null) {
            if (me.getBankName() == null || me.getAccountName() == null) {
                throw ApiException.badRequest("Choose the bank and enter the account name for this account.");
            }
            me.setAccountNumberEnc(crypto.encrypt(body.accountNumber()));
            me.setAccountLast4(last4(body.accountNumber()));
            changedIds = true;
        }
        if (body.bvn() != null) {
            me.setBvnEnc(crypto.encrypt(body.bvn()));
            me.setBvnLast4(last4(body.bvn()));
            changedIds = true;
        }
        if (body.nin() != null) {
            me.setNinEnc(crypto.encrypt(body.nin()));
            me.setNinLast4(last4(body.nin()));
            changedIds = true;
        }
        /* Never log the numbers themselves. */
        activity.record("PROVIDER_PROFILE_UPDATED", changedIds ? "Updated profile and payout details" : "Updated profile",
            "PROVIDER", me.getId());
        return profile();
    }

    /* ── helpers ── */

    private StatusView statusOf(Provider me) {
        return new StatusView(me.isAvailable(), me.isAvailable(), List.copyOf(coverage(me)),
            hhmm(start(me)) + " – " + hhmm(end(me)));
    }

    private static java.util.Set<String> coverage(Provider me) {
        return me.areasCovered();
    }

    private static LocalTime start(Provider me) {
        return me.getDefaultStartTime() == null ? LocalTime.of(8, 0) : me.getDefaultStartTime();
    }

    private static LocalTime end(Provider me) {
        return me.getDefaultEndTime() == null ? LocalTime.of(18, 0) : me.getDefaultEndTime();
    }

    private static YearMonth parseMonth(String text) {
        if (text == null || text.isBlank()) {
            return YearMonth.now(com.digihealth.common.AppTime.ZONE);
        }
        try {
            return YearMonth.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Month must look like 2026-10.");
        }
    }

    private static LocalTime parseTime(String text, LocalTime fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            String t = text.trim();
            return LocalTime.parse(t.length() == 4 ? "0" + t : t);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Use times like 08:00.");
        }
    }

    private static String hhmm(LocalTime t) {
        return t == null ? null : String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static String last4(String digits) {
        return digits.substring(digits.length() - 4);
    }

    private static String mask(String last4, int length) {
        return last4 == null ? null : "•".repeat(length - 4) + last4;
    }
}
