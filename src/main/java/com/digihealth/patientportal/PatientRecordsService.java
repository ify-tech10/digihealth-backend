package com.digihealth.patientportal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityRepository;
import com.digihealth.facility.FacilityRequest;
import com.digihealth.facility.FacilityRequestRepository;
import com.digihealth.file.FileService;
import com.digihealth.labportal.ServiceRequest;
import com.digihealth.labportal.ServiceRequestRepository;
import com.digihealth.patient.Patient;
import com.digihealth.patient.PatientRepository;
import com.digihealth.patientportal.PatientAccountService.DocumentView;

import tools.jackson.databind.ObjectMapper;

/*
 * What the patient sees of the work done by partner facilities and DiGi's lab / pharmacy:
 *   lab results (one row per test), medications delivered, and documents
 *   (lab reports, discharge summaries, delivery notes).
 */
@Service
public class PatientRecordsService {

    private final PatientRepository patients;
    private final FacilityRequestRepository facilityRequests;
    private final ServiceRequestRepository serviceRequests;
    private final FacilityRepository facilities;
    private final FileService files;
    private final ObjectMapper json;

    public PatientRecordsService(PatientRepository patients, FacilityRequestRepository facilityRequests,
                                 ServiceRequestRepository serviceRequests, FacilityRepository facilities,
                                 FileService files, ObjectMapper json) {
        this.patients = patients;
        this.facilityRequests = facilityRequests;
        this.serviceRequests = serviceRequests;
        this.facilities = facilities;
        this.files = files;
        this.json = json;
    }

    /** [{ id, resultedAt, testName, labName, value, unit, referenceRange, flag, reportUrl }] newest first */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> labResults() {
        Long pid = myPatientId();
        if (pid == null) {
            return List.of();
        }
        List<FacilityRequest> labs = facilityRequests.findByPatientIdAndStatusOrderByCreatedAtDesc(pid, FacilityRequest.RESULTED);
        List<ServiceRequest> digi = serviceRequests.findByPatientIdAndStatusOrderByCreatedAtDesc(pid, ServiceRequest.COMPLETED)
            .stream().filter(r -> "LAB".equals(r.getKind())).toList();
        Map<Long, String> urls = files.urlsFor(Stream.concat(labs.stream().map(FacilityRequest::getReportFileId),
            digi.stream().map(ServiceRequest::getResultFileId)).filter(Objects::nonNull).toList());
        Map<Long, String> names = facilityNames(labs);

        List<Map<String, Object>> out = new ArrayList<>();
        for (FacilityRequest r : labs) {
            for (Map<String, Object> t : lines(r.getLines())) {
                if (Boolean.FALSE.equals(t.get("available"))) {
                    continue;
                }
                Map<String, Object> res = t.get("result") instanceof Map<?, ?> m ? cast(m) : Map.of();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", "F" + r.getId() + "-" + t.get("id"));
                row.put("resultedAt", r.getResultedAt());
                row.put("testName", t.get("name"));
                row.put("labName", names.get(r.getFacilityId()));
                row.put("value", res.get("value"));
                row.put("unit", res.get("unit"));
                row.put("referenceRange", res.get("referenceRange"));
                row.put("flag", res.get("flag"));
                row.put("comment", r.getResultComment());
                row.put("reportUrl", urls.get(r.getReportFileId()));
                out.add(row);
            }
        }
        for (ServiceRequest r : digi) {
            for (Map<String, Object> t : lines(r.getLines())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", "D" + r.getId() + "-" + t.get("id"));
                row.put("resultedAt", r.getCompletedAt());
                row.put("testName", t.get("name"));
                row.put("labName", "DiGi Health lab");
                row.put("value", r.isAbnormal() ? "Abnormal — see report" : "See report");
                row.put("flag", r.isAbnormal() ? "ABNORMAL" : "NORMAL");
                row.put("comment", r.getResultSummary());
                row.put("reportUrl", urls.get(r.getResultFileId()));
                out.add(row);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (Instant) m.get("resultedAt"),
            Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** Drugs delivered to the patient: [{ id, name, strength, instructions, startDate, prescribedByName, lastDeliveredAt, status }] */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> medications() {
        Long pid = myPatientId();
        if (pid == null) {
            return List.of();
        }
        /* newest delivery first, so the first time we see a drug is its latest delivery */
        record Delivery(Instant at, Instant orderedAt, String by, List<Map<String, Object>> items) {
        }
        List<Delivery> deliveries = new ArrayList<>();
        for (FacilityRequest r : facilityRequests.findByPatientIdAndStatusOrderByCreatedAtDesc(pid, FacilityRequest.DELIVERED)) {
            deliveries.add(new Delivery(r.getDeliveredAt(), r.getCreatedAt(), r.getRequestedByName(), lines(r.getLines())));
        }
        for (ServiceRequest r : serviceRequests.findByPatientIdAndStatusOrderByCreatedAtDesc(pid, ServiceRequest.COMPLETED)) {
            if ("PHARMACY".equals(r.getKind())) {
                deliveries.add(new Delivery(r.getCompletedAt(), r.getCreatedAt(), r.getRequestedByName(), lines(r.getLines())));
            }
        }
        deliveries.sort(Comparator.comparing(Delivery::at, Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Map<String, Object>> byDrug = new LinkedHashMap<>();
        for (Delivery d : deliveries) {
            for (Map<String, Object> it : d.items()) {
                if (Boolean.FALSE.equals(it.get("available"))) {
                    continue;
                }
                String name = String.valueOf(it.getOrDefault("name", "Medicine"));
                String strength = it.get("strength") == null ? "" : String.valueOf(it.get("strength"));
                String key = (name + "|" + strength).toLowerCase();
                Map<String, Object> m = byDrug.get(key);
                if (m == null) {
                    m = new LinkedHashMap<>();
                    m.put("id", key);
                    m.put("name", name);
                    m.put("strength", strength.isEmpty() ? null : strength);
                    m.put("instructions", it.get("instructions"));
                    m.put("prescribedByName", d.by());
                    m.put("lastDeliveredAt", d.at());
                    m.put("status", "ACTIVE");
                    byDrug.put(key, m);
                }
                /* the oldest delivery we see last becomes "taking since" */
                m.put("startDate", Ledger.day(d.orderedAt()));
            }
        }
        return new ArrayList<>(byDrug.values());
    }

    /** Lab reports, discharge summaries and delivery notes, for the Documents list. */
    @Transactional(readOnly = true)
    public List<DocumentView> documents() {
        Long pid = myPatientId();
        if (pid == null) {
            return List.of();
        }
        List<FacilityRequest> fr = new ArrayList<>();
        fr.addAll(facilityRequests.findByPatientIdAndStatusOrderByCreatedAtDesc(pid, FacilityRequest.RESULTED));
        fr.addAll(facilityRequests.findByPatientIdAndStatusOrderByCreatedAtDesc(pid, FacilityRequest.DISCHARGED));
        fr.addAll(facilityRequests.findByPatientIdAndStatusOrderByCreatedAtDesc(pid, FacilityRequest.DELIVERED));
        List<ServiceRequest> sr = serviceRequests.findByPatientIdAndStatusOrderByCreatedAtDesc(pid, ServiceRequest.COMPLETED);
        Map<Long, String> urls = files.urlsFor(Stream.of(
            fr.stream().map(FacilityRequest::getReportFileId), fr.stream().map(FacilityRequest::getDischargeFileId),
            fr.stream().map(FacilityRequest::getProofFileId), sr.stream().map(ServiceRequest::getResultFileId),
            sr.stream().map(ServiceRequest::getProofFileId)).flatMap(x -> x).filter(Objects::nonNull).toList());
        Map<Long, String> names = facilityNames(fr);

        List<DocumentView> out = new ArrayList<>();
        for (FacilityRequest r : fr) {
            String where = names.get(r.getFacilityId());
            switch (r.getStatus()) {
                case FacilityRequest.RESULTED -> out.add(new DocumentView("FL-" + r.getId(), "LAB_REPORT",
                    "Lab results " + r.getReference(), where, Ledger.day(r.getResultedAt()), r.getResultedAt(),
                    urls.get(r.getReportFileId()), null));
                case FacilityRequest.DISCHARGED -> out.add(new DocumentView("FD-" + r.getId(), "DISCHARGE_SUMMARY",
                    "Discharge summary — " + where, where, Ledger.day(r.getDischargedAt()), r.getDischargedAt(),
                    urls.get(r.getDischargeFileId()), null));
                default -> out.add(new DocumentView("FP-" + r.getId(), "PRESCRIPTION",
                    "Drug delivery " + r.getReference(), where, Ledger.day(r.getDeliveredAt()), r.getDeliveredAt(),
                    urls.get(r.getProofFileId()), null));
            }
        }
        for (ServiceRequest r : sr) {
            boolean lab = "LAB".equals(r.getKind());
            out.add(new DocumentView((lab ? "DL-" : "DP-") + r.getId(), lab ? "LAB_REPORT" : "PRESCRIPTION",
                (lab ? "Lab results " : "Drug delivery ") + r.getRequestNumber(), "DiGi Health",
                Ledger.day(r.getCompletedAt()), r.getCompletedAt(),
                urls.get(lab ? r.getResultFileId() : r.getProofFileId()), null));
        }
        return out;
    }

    /* ── helpers ── */

    private Long myPatientId() {
        return patients.findByUserId(CurrentUser.id()).map(Patient::getId).orElse(null);
    }

    private Map<Long, String> facilityNames(List<FacilityRequest> list) {
        return facilities.findAllById(list.stream().map(FacilityRequest::getFacilityId).distinct().toList()).stream()
            .collect(Collectors.toMap(Facility::getId, Facility::getName));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = json.readValue(text, List.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : raw == null ? List.of() : raw) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** Local helper so this file doesn't depend on the finance package. */
    private static final class Ledger {
        private Ledger() {
        }

        static LocalDate day(Instant t) {
            return t == null ? null : t.atZone(AppTime.ZONE).toLocalDate();
        }
    }
}
