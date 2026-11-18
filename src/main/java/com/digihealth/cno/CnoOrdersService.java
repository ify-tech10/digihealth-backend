package com.digihealth.cno;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityKind;
import com.digihealth.facility.FacilityRepository;
import com.digihealth.facility.FacilityRequest;
import com.digihealth.facility.FacilityRequestRepository;
import com.digihealth.facility.LabTest;
import com.digihealth.facility.LabTestRepository;
import com.digihealth.facilityportal.FacilityRequestService;
import com.digihealth.labportal.CatalogueItem;
import com.digihealth.labportal.CatalogueItemRepository;
import com.digihealth.labportal.LabPortalService;
import com.digihealth.labportal.ServiceRequest;
import com.digihealth.labportal.ServiceRequestRepository;
import com.digihealth.patient.Patient;
import com.digihealth.patient.PatientRepository;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;
import com.digihealth.visit.VisitReport;
import com.digihealth.visit.VisitReportRepository;

/*
 * The medical team sending work out (CNO portal > Orders):
 *   - to a partner facility: hospital referral, pharmacy drug order, lab test request
 *   - to DiGi's own lab scientists / pharmacists
 * and following what happened to it. The patient's details, latest vitals, medications and
 * allergies are copied onto a facility request so the facility has what it needs.
 */
@Service
public class CnoOrdersService {

    private final PatientRepository patients;
    private final UserRepository users;
    private final VisitReportRepository reports;
    private final FacilityRepository facilities;
    private final LabTestRepository labTests;
    private final CatalogueItemRepository catalogue;
    private final FacilityRequestRepository facilityRequests;
    private final ServiceRequestRepository serviceRequests;
    private final FacilityRequestService facilityRequestService;
    private final LabPortalService labPortal;

    public CnoOrdersService(PatientRepository patients, UserRepository users, VisitReportRepository reports,
                            FacilityRepository facilities, LabTestRepository labTests, CatalogueItemRepository catalogue,
                            FacilityRequestRepository facilityRequests, ServiceRequestRepository serviceRequests,
                            FacilityRequestService facilityRequestService, LabPortalService labPortal) {
        this.patients = patients;
        this.users = users;
        this.reports = reports;
        this.facilities = facilities;
        this.labTests = labTests;
        this.catalogue = catalogue;
        this.facilityRequests = facilityRequests;
        this.serviceRequests = serviceRequests;
        this.facilityRequestService = facilityRequestService;
        this.labPortal = labPortal;
    }

    /**
     * target FACILITY: facilityId + (hospital) reason, or (pharmacy / lab) items.
     * target DIGI:     kind LAB | PHARMACY + items.
     * items: [{ name, strength, quantity, instructions, sampleType, code, testId, catalogueItemId }]
     */
    public record OrderBody(@NotBlank(message = "Choose where to send it") String target,
                            String kind,
                            Long facilityId,
                            @NotNull(message = "Choose the patient") Long patientId,
                            String urgency,
                            @Size(max = 4000) String reason,
                            @Size(max = 2000) String diagnosis,
                            @Size(max = 4000) String priorCareSummary,
                            @Size(max = 4000) String notes,
                            LocalDate dueDate,
                            @Size(max = 50, message = "At most 50 items") List<Map<String, Object>> items) {
    }

    /* ── pickers ── */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> patients(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Patient> all = patients.findAll();
        Map<Long, User> byUser = users.findAllById(all.stream().map(Patient::getUserId).toList()).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Patient p : all) {
            User u = byUser.get(p.getUserId());
            if (u == null || !u.isActive() || u.getRole() != Role.PATIENT) {
                continue;
            }
            if (!needle.isEmpty() && !(contains(u.getFullName(), needle) || contains(u.getEmail(), needle)
                || contains(u.getPhoneNumber(), needle))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("userId", u.getId());
            m.put("name", u.getFullName());
            m.put("email", u.getEmail());
            m.put("phone", u.getPhoneNumber());
            m.put("address", p.getAddress());
            m.put("locationArea", p.getAreaLga() != null ? p.getAreaLga() : u.getLocationArea());
            m.put("patientType", p.getPatientType());
            m.put("age", age(p.getDateOfBirth()));
            m.put("gender", p.getGender());
            out.add(m);
        }
        out.sort(Comparator.<Map<String, Object>, String>comparing(m -> String.valueOf(m.get("name")), String.CASE_INSENSITIVE_ORDER));
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> facilities(String kindText) {
        FacilityKind kind = facilityKind(kindText);
        return facilities.findByKindAndDeletedAtIsNullOrderByNameAsc(kind).stream()
            .filter(Facility::isActive)
            .map(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.getId());
                m.put("kind", f.getKind().name());
                m.put("name", f.getName());
                m.put("address", f.getAddress());
                m.put("locationArea", f.getLocationArea());
                m.put("phoneNumber", f.getPhoneNumber());
                m.put("accepting", f.isAccepting());
                m.put("hasLogin", f.getAdminUserId() != null);
                m.put("bedsAvailable", f.getBedsAvailable());
                m.put("homeCollection", f.isHomeCollection());
                m.put("openingHours", f.getOpeningHours());
                return m;
            }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> facilityTests(Long facilityId) {
        return labTests.findByFacilityIdOrderByNameAsc(facilityId).stream().filter(LabTest::isActive)
            .map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.getId());
                m.put("name", t.getName());
                m.put("code", t.getCode());
                m.put("sampleType", t.getSampleType());
                m.put("price", t.getPrice());
                m.put("turnaroundHours", t.getTurnaroundHours());
                m.put("fasting", t.isFasting());
                m.put("homeCollection", t.isHomeCollection());
                return m;
            }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> catalogue(String kindText) {
        String kind = digiKind(kindText);
        return catalogue.findByKindOrderByNameAsc(kind).stream().filter(CatalogueItem::isActive)
            .map(i -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", i.getId());
                m.put("name", i.getName());
                m.put("code", i.getCode());
                m.put("strength", i.getStrength());
                m.put("form", i.getForm());
                m.put("sampleType", i.getSampleType());
                m.put("unitPrice", i.getUnitPrice());
                m.put("stock", i.getStock());
                return m;
            }).toList();
    }

    /* ── sending ── */

    @Transactional
    public Map<String, Object> send(OrderBody b) {
        Patient p = patients.findById(b.patientId()).orElseThrow(() -> ApiException.notFound("Patient not found."));
        User u = users.findById(p.getUserId()).orElseThrow(() -> ApiException.notFound("Patient not found."));
        String urgency = b.urgency() == null || b.urgency().isBlank() ? "ROUTINE" : b.urgency().trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> items = b.items() == null ? List.of() : b.items();
        String target = b.target().trim().toUpperCase(Locale.ROOT);

        if ("FACILITY".equals(target)) {
            if (b.facilityId() == null) {
                throw ApiException.badRequest("Choose the facility.");
            }
            Facility f = facilities.findById(b.facilityId()).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Facility not found."));
            List<Map<String, Object>> lines = new ArrayList<>();
            if (f.getKind() == FacilityKind.HOSPITAL) {
                if (blank(b.reason())) {
                    throw ApiException.badRequest("Say why the patient is being referred.");
                }
            } else {
                for (Map<String, Object> raw : items) {
                    lines.add(f.getKind() == FacilityKind.LABORATORY ? labLine(f, raw) : drugLine(raw));
                }
                if (lines.isEmpty()) {
                    throw ApiException.badRequest(f.getKind() == FacilityKind.PHARMACY ? "Add at least one drug." : "Add at least one test.");
                }
            }
            VisitReport latest = reports.findByPatientIdAndStatusOrderByVisitDateDesc(u.getId(), VisitReport.APPROVED)
                .stream().findFirst().orElse(null);
            String prior = !blank(b.priorCareSummary()) ? b.priorCareSummary().trim()
                : latest == null ? null : "Last home visit " + latest.getVisitDate() + ": " + latest.getNotes();
            FacilityRequest saved = facilityRequestService.send(new FacilityRequestService.NewRequest(
                f.getId(), p.getId(), urgency, patientSnapshot(p, u), trim(b.reason()),
                !blank(b.diagnosis()) ? b.diagnosis().trim() : p.getConditions(), prior, vitals(latest),
                split(p.getCurrentMedications()), split(p.getAllergies()), trim(b.notes()),
                lines.isEmpty() ? null : lines, null));
            return Map.<String, Object>of("id", saved.getId(), "reference", saved.getReference(), "source", "FACILITY",
                "sentTo", f.getName());
        }

        if ("DIGI".equals(target)) {
            String kind = digiKind(b.kind());
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> raw : items) {
                lines.add(digiLine(kind, raw));
            }
            String priority = "EMERGENCY".equals(urgency) ? "STAT" : urgency;
            ServiceRequest saved = labPortal.send(new LabPortalService.NewServiceRequest(kind, p.getId(), priority,
                b.dueDate(), trim(b.notes()), lines));
            return Map.<String, Object>of("id", saved.getId(), "reference", saved.getRequestNumber(), "source", "DIGI",
                "sentTo", LabPortalService.PHARMACY.equals(kind) ? "DiGi pharmacists" : "DiGi lab scientists");
        }
        throw ApiException.badRequest("Choose where to send it.");
    }

    /* ── tracking ── */

    /** Everything sent, newest first: [{ id, source, kind, reference, status, urgency, patientName, sentTo, ... }] */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> orders() {
        Map<Long, String> facilityNames = facilities.findAll().stream()
            .collect(Collectors.toMap(Facility::getId, Facility::getName));
        List<Map<String, Object>> out = new ArrayList<>();
        for (FacilityRequest r : facilityRequests.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("source", "FACILITY");
            m.put("kind", r.getKind().name());
            m.put("reference", r.getReference());
            m.put("status", r.getStatus());
            m.put("urgency", r.getUrgency());
            m.put("patientName", r.getPatientName());
            m.put("sentTo", facilityNames.get(r.getFacilityId()));
            m.put("sentBy", r.getRequestedByName());
            m.put("reason", r.getReason());
            m.put("declineReason", r.getDeclineReason());
            m.put("critical", r.isCritical());
            m.put("resultComment", r.getResultComment());
            m.put("dischargeSummary", r.getDischargeSummary());
            m.put("expectedArrival", r.getExpectedArrival());
            m.put("scheduledAt", r.getScheduledAt());
            m.put("createdAt", r.getCreatedAt());
            m.put("updatedAt", r.getUpdatedAt());
            out.add(m);
        }
        for (ServiceRequest r : serviceRequests.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("source", "DIGI");
            m.put("kind", r.getKind());
            m.put("reference", r.getRequestNumber());
            m.put("status", r.getStatus());
            m.put("urgency", r.getPriority());
            m.put("patientName", r.getPatientName());
            m.put("sentTo", LabPortalService.PHARMACY.equals(r.getKind()) ? "DiGi pharmacists" : "DiGi lab scientists");
            m.put("sentBy", r.getRequestedByName());
            m.put("reason", r.getNotes());
            m.put("declineReason", r.getDeclineReason());
            m.put("critical", r.isAbnormal());
            m.put("resultComment", r.getResultSummary());
            m.put("scheduledAt", r.getScheduledAt());
            m.put("createdAt", r.getCreatedAt());
            m.put("updatedAt", r.getUpdatedAt());
            out.add(m);
        }
        out.sort((a, c) -> ((Instant) c.get("createdAt")).compareTo((Instant) a.get("createdAt")));
        return out;
    }

    /* ── helpers ── */

    private Map<String, Object> patientSnapshot(Patient p, User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", u.getFullName());
        m.put("age", age(p.getDateOfBirth()));
        m.put("gender", p.getGender());
        m.put("phone", u.getPhoneNumber());
        m.put("address", p.getAddress());
        m.put("patientType", p.getPatientType());
        m.put("bloodGroup", p.getBloodGroup());
        m.put("genotype", p.getGenotype());
        if (p.getNextOfKinName() != null) {
            Map<String, Object> nok = new LinkedHashMap<>();
            nok.put("name", p.getNextOfKinName());
            nok.put("relationship", p.getNextOfKinRelationship());
            nok.put("phone", p.getNextOfKinPhone());
            m.put("nextOfKin", nok);
        }
        return m;
    }

    private static Map<String, Object> vitals(VisitReport r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bloodPressure", r.getBloodPressure());
        m.put("pulse", r.getPulse());
        m.put("temperature", r.getTemperature());
        m.put("spo2", r.getSpo2());
        m.put("respiratoryRate", r.getRespiratoryRate());
        m.put("bloodSugar", r.getBloodSugar());
        m.put("recordedAt", r.getVisitDate());
        return m;
    }

    private Map<String, Object> labLine(Facility f, Map<String, Object> raw) {
        Map<String, Object> line = new LinkedHashMap<>();
        Object testId = raw.get("testId");
        if (testId != null) {
            LabTest t = labTests.findByIdAndFacilityId(Long.valueOf(String.valueOf(testId)), f.getId())
                .orElseThrow(() -> ApiException.badRequest("That test isn't on " + f.getName() + "'s menu."));
            line.put("name", t.getName());
            line.put("code", t.getCode());
            line.put("sampleType", t.getSampleType());
            line.put("fasting", t.isFasting());
            line.put("price", t.getPrice());
            return line;
        }
        String name = text(raw.get("name"));
        if (name == null) {
            throw ApiException.badRequest("Every test needs a name.");
        }
        line.put("name", name);
        line.put("code", text(raw.get("code")));
        line.put("sampleType", text(raw.get("sampleType")));
        line.put("fasting", Boolean.TRUE.equals(raw.get("fasting")));
        return line;
    }

    private static Map<String, Object> drugLine(Map<String, Object> raw) {
        String name = text(raw.get("name"));
        if (name == null) {
            throw ApiException.badRequest("Every drug needs a name.");
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("name", name);
        line.put("strength", text(raw.get("strength")));
        line.put("quantity", quantity(raw.get("quantity")));
        line.put("instructions", text(raw.get("instructions")));
        return line;
    }

    private Map<String, Object> digiLine(String kind, Map<String, Object> raw) {
        Map<String, Object> line = new LinkedHashMap<>();
        Object cid = raw.get("catalogueItemId");
        if (cid != null) {
            CatalogueItem c = catalogue.findByIdAndKind(Long.valueOf(String.valueOf(cid)), kind)
                .orElseThrow(() -> ApiException.badRequest("That item isn't in the catalogue."));
            line.put("catalogueItemId", c.getId());
            line.put("name", c.getName());
            line.put("strength", c.getStrength());
            line.put("sampleType", c.getSampleType());
        } else {
            String name = text(raw.get("name"));
            if (name == null) {
                throw ApiException.badRequest("Every item needs a name.");
            }
            line.put("name", name);
            line.put("strength", text(raw.get("strength")));
            line.put("sampleType", text(raw.get("sampleType")));
        }
        line.put("quantity", quantity(raw.get("quantity")));
        line.put("instructions", text(raw.get("instructions")));
        return line;
    }

    private static FacilityKind facilityKind(String k) {
        String x = k == null ? "" : k.trim().toUpperCase(Locale.ROOT);
        return switch (x) {
            case "HOSPITAL", "HOSPITALS" -> FacilityKind.HOSPITAL;
            case "PHARMACY", "PHARMACIES" -> FacilityKind.PHARMACY;
            case "LAB", "LABORATORY", "LABORATORIES" -> FacilityKind.LABORATORY;
            default -> throw ApiException.badRequest("Choose hospital, pharmacy or laboratory.");
        };
    }

    private static String digiKind(String k) {
        String x = k == null ? "" : k.trim().toUpperCase(Locale.ROOT);
        if (x.equals("LAB") || x.equals("LABORATORY")) {
            return LabPortalService.LAB;
        }
        if (x.equals("PHARMACY")) {
            return LabPortalService.PHARMACY;
        }
        throw ApiException.badRequest("Choose lab or pharmacy.");
    }

    private static Integer age(LocalDate dob) {
        return dob == null ? null : Period.between(dob, AppTime.today()).getYears();
    }

    private static List<String> split(String text) {
        return text == null || text.isBlank() ? null
            : Arrays.stream(text.split("[,\\n;]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static int quantity(Object o) {
        try {
            int q = (int) Math.round(Double.parseDouble(String.valueOf(o)));
            return q < 1 ? 1 : Math.min(q, 10000);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static String text(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }

    private static boolean contains(String s, String needle) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }
}
