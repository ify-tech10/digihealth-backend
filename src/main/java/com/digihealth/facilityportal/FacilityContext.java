package com.digihealth.facilityportal;

import org.springframework.stereotype.Component;

import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityKind;
import com.digihealth.facility.FacilityRepository;

/** Works out the signed-in facility admin's facility. Never trust a facility id from the browser. */
@Component
public class FacilityContext {

    private final FacilityRepository facilities;

    public FacilityContext(FacilityRepository facilities) {
        this.facilities = facilities;
    }

    public Facility current() {
        Facility f = facilities.findByAdminUserIdAndDeletedAtIsNull(CurrentUser.id())
            .orElseThrow(() -> ApiException.notFound(
                "Your account isn't linked to a facility yet. Please contact DiGi Health."));
        if (!f.isActive()) {
            throw ApiException.forbidden(
                "Your facility is inactive on DiGi Health. Please contact your relationship manager.");
        }
        return f;
    }

    public Facility current(FacilityKind kind) {
        Facility f = current();
        requireKind(f, kind);
        return f;
    }

    public static void requireKind(Facility f, FacilityKind kind) {
        if (f.getKind() != kind) {
            throw ApiException.badRequest("This is only available to " + kind.path + ".");
        }
    }
}
