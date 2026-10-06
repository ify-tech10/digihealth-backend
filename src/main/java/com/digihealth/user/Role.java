package com.digihealth.user;

import java.util.EnumSet;
import java.util.Set;

/**
 * Every role the frontend knows (src/config/roles.js). The names are sent to
 * the frontend at login and must match exactly. Adding a role also needs a
 * migration that updates the ck_users_role check.
 */
public enum Role {
    SUPER_ADMIN,
    ADMIN,

    FINANCE_MANAGER,
    CUSTOMER_CARE,
    RELATIONSHIP_MANAGER,

    CNO_MEDICAL_DIRECTOR,
    CNO,
    CHIEF_MEDICAL_OFFICER,
    HEAD_OF_CLINICAL_OPERATIONS,
    NURSING_SUPERVISOR,

    SERVICE_PROVIDER,
    CLINICAL_PERSONNEL,
    LAB_SCIENTIST,
    PHARMACIST,

    HOSPITAL_ADMIN,
    PHARMACY_ADMIN,
    LAB_ADMIN,

    PATIENT,
    HMO;

    /** Super admin and admin are the same role in practice. */
    public static final Set<Role> ADMINS = EnumSet.of(SUPER_ADMIN, ADMIN);

    /** The /cno portal. */
    public static final Set<Role> CLINICAL_LEADERSHIP = EnumSet.of(
        CNO_MEDICAL_DIRECTOR, CNO, CHIEF_MEDICAL_OFFICER, HEAD_OF_CLINICAL_OPERATIONS, NURSING_SUPERVISOR);

    /** The /caregiver portal. */
    public static final Set<Role> PROVIDERS = EnumSet.of(SERVICE_PROVIDER, CLINICAL_PERSONNEL);

    /** Partner facility portals (/hospital, /pharmacy, /laboratory). */
    public static final Set<Role> FACILITY_ADMINS = EnumSet.of(HOSPITAL_ADMIN, PHARMACY_ADMIN, LAB_ADMIN);

    public boolean isAdmin() {
        return ADMINS.contains(this);
    }

    /** Spring Security authority name, e.g. ROLE_PATIENT. */
    public String authority() {
        return "ROLE_" + name();
    }
}
