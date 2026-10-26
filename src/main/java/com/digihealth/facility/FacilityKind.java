package com.digihealth.facility;

import com.digihealth.common.ApiException;
import com.digihealth.user.Role;

/** The three partner types, their URL segment and their admin role. */
public enum FacilityKind {
    HOSPITAL("hospitals", Role.HOSPITAL_ADMIN, "Hospital"),
    PHARMACY("pharmacies", Role.PHARMACY_ADMIN, "Pharmacy"),
    LABORATORY("laboratories", Role.LAB_ADMIN, "Laboratory");

    public final String path;
    public final Role adminRole;
    public final String label;

    FacilityKind(String path, Role adminRole, String label) {
        this.path = path;
        this.adminRole = adminRole;
        this.label = label;
    }

    /** "hospitals" -> HOSPITAL */
    public static FacilityKind fromPath(String path) {
        for (FacilityKind k : values()) {
            if (k.path.equalsIgnoreCase(path)) {
                return k;
            }
        }
        throw ApiException.notFound("Unknown facility type: " + path);
    }

    /** HOSPITAL_ADMIN -> HOSPITAL */
    public static FacilityKind forRole(Role role) {
        for (FacilityKind k : values()) {
            if (k.adminRole == role) {
                return k;
            }
        }
        return null;
    }
}
