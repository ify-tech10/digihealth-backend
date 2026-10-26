package com.digihealth.caregiver;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.provider.Provider;
import com.digihealth.provider.ProviderRepository;

/** Finds the signed-in provider and formats times for the caregiver pages. */
@Component
public class CaregiverSupport {

    static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(AppTime.ZONE);
    static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(AppTime.ZONE);
    static final DateTimeFormatter FRIENDLY = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
        .withZone(AppTime.ZONE);

    private final ProviderRepository providers;

    public CaregiverSupport(ProviderRepository providers) {
        this.providers = providers;
    }

    /** The provider record of whoever is signed in. */
    public Provider me() {
        Provider p = providers.findByUserId(CurrentUser.id())
            .orElseThrow(() -> ApiException.forbidden("Your provider profile isn't set up yet. Please contact DiGi Health."));
        if (!Provider.APPROVED.equals(p.getStatus())) {
            throw ApiException.forbidden("Your provider account isn't active. Please contact DiGi Health.");
        }
        return p;
    }

    static String time(Instant i) {
        return i == null ? null : HH_MM.format(i);
    }

    static String day(Instant i) {
        return i == null ? null : DAY.format(i);
    }

    static String friendly(Instant i) {
        return i == null ? null : FRIENDLY.format(i);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
