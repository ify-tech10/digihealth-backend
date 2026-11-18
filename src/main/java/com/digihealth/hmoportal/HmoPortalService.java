package com.digihealth.hmoportal;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.digihealth.activity.ActivityService;
import com.digihealth.auth.PasswordLinks;
import com.digihealth.carerequest.CareRequest;
import com.digihealth.carerequest.CareRequestRepository;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.finance.Invoice;
import com.digihealth.finance.InvoiceRepository;
import com.digihealth.hmo.HmoEmployee;
import com.digihealth.hmo.HmoEmployeeRepository;
import com.digihealth.hmo.HmoOrganisation;
import com.digihealth.hmo.HmoOrganisationRepository;
import com.digihealth.hmo.HmoPlan;
import com.digihealth.hmo.HmoPlanChangeRequest;
import com.digihealth.hmo.HmoPlanChangeRequestRepository;
import com.digihealth.hmo.HmoPlanRepository;
import com.digihealth.hmo.HmoSubscription;
import com.digihealth.hmo.HmoSubscriptionRepository;
import com.digihealth.hmo.HmoUpload;
import com.digihealth.hmo.HmoUploadRepository;
import com.digihealth.notification.NotificationService;
import com.digihealth.patient.Patient;
import com.digihealth.patient.PatientRepository;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/*
 * The HMO organisation admin's portal (/hmo/me): the company's subscription, its employees
 * (each one a PATIENT with patient_type HMO), bulk CSV uploads, utilisation and plan changes.
 * The company always comes from the signed-in user.
 */
@Service
public class HmoPortalService {

    private static final int MAX_ROWS = 2000;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]{7,20}$");
    private static final Set<String> STATUSES = Set.of("REGISTERED", "PENDING", "INACTIVE");

    /* CSV header text -> field; "First Name", "first_name" and "FIRSTNAME" all mean firstName. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("firstname", "firstName"), Map.entry("givenname", "firstName"),
        Map.entry("lastname", "lastName"), Map.entry("surname", "lastName"), Map.entry("familyname", "lastName"),
        Map.entry("fullname", "fullName"), Map.entry("name", "fullName"),
        Map.entry("email", "email"), Map.entry("emailaddress", "email"),
        Map.entry("phone", "phone"), Map.entry("phonenumber", "phone"), Map.entry("mobile", "phone"),
        Map.entry("telephone", "phone"),
        Map.entry("department", "department"), Map.entry("dept", "department"), Map.entry("unit", "department"),
        Map.entry("staffid", "staffId"), Map.entry("employeeid", "staffId"), Map.entry("staffnumber", "staffId"),
        Map.entry("employeenumber", "staffId"),
        Map.entry("dateofbirth", "dateOfBirth"), Map.entry("dob", "dateOfBirth"), Map.entry("birthdate", "dateOfBirth"),
        Map.entry("gender", "gender"), Map.entry("sex", "gender"));

    private final HmoOrganisationRepository organisations;
    private final HmoEmployeeRepository employees;
    private final HmoUploadRepository uploads;
    private final HmoPlanRepository plans;
    private final HmoSubscriptionRepository subscriptions;
    private final HmoPlanChangeRequestRepository planChanges;
    private final UserRepository users;
    private final PatientRepository patients;
    private final CareRequestRepository careRequests;
    private final InvoiceRepository invoices;
    private final PasswordLinks passwordLinks;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final ObjectMapper json;

    public HmoPortalService(HmoOrganisationRepository organisations, HmoEmployeeRepository employees,
                            HmoUploadRepository uploads, HmoPlanRepository plans, HmoSubscriptionRepository subscriptions,
                            HmoPlanChangeRequestRepository planChanges, UserRepository users, PatientRepository patients,
                            CareRequestRepository careRequests, InvoiceRepository invoices, PasswordLinks passwordLinks,
                            NotificationService notifications, ActivityService activity, ObjectMapper json) {
        this.organisations = organisations;
        this.employees = employees;
        this.uploads = uploads;
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.planChanges = planChanges;
        this.users = users;
        this.patients = patients;
        this.careRequests = careRequests;
        this.invoices = invoices;
        this.passwordLinks = passwordLinks;
        this.notifications = notifications;
        this.activity = activity;
        this.json = json;
    }

    /* ── bodies & views ── */

    public record EmployeeBody(
        @NotBlank(message = "Required") @Size(max = 80) String firstName,
        @NotBlank(message = "Required") @Size(max = 80) String lastName,
        @NotBlank(message = "Enter a valid email") @Email(message = "Enter a valid email") @Size(max = 255) String email,
        @NotBlank(message = "Enter a valid phone number")
        @jakarta.validation.constraints.Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "Enter a valid phone number") String phone,
        @Size(max = 100) String department,
        @Size(max = 50) String staffId,
        String dateOfBirth,
        String gender) {
    }

    public record DeactivateBody(@NotBlank(message = "Say why they're leaving the plan") @Size(max = 1000) String reason) {
    }

    public record PlanChangeBody(@NotNull(message = "Choose a plan") Long planId, @Size(max = 2000) String note) {
    }

    public record EmployeeView(Long id, Long userId, String firstName, String lastName, String fullName, String email,
                               String phone, String department, String staffId, LocalDate dateOfBirth, String gender,
                               String status, boolean nextOfKinCompleted, boolean consentSigned, String nextOfKinName,
                               Instant consentSignedAt, boolean accountActivated, Instant invitedAt,
                               Instant deactivatedAt, String deactivatedReason, long requestCount,
                               BigDecimal benefitUsed, Instant createdAt) {
    }

    public record UploadResult(int created, int updated, int skipped, List<RowError> errors) {
    }

    public record RowError(int row, String message) {
    }

    public record UploadView(Long id, String fileName, int totalRows, int created, int updated, int skipped,
                             List<RowError> errors, String status, String uploadedByName, Instant uploadedAt) {
    }

    public record PlanView(Long id, String name, BigDecimal annualPrice, BigDecimal benefitLimit, int durationMonths,
                           String description, List<String> coveredServices, boolean active) {
        static PlanView of(HmoPlan p) {
            return new PlanView(p.getId(), p.getName(), p.getAnnualPrice(), p.getBenefitLimit(), p.getDurationMonths(),
                p.getDescription(), p.coveredServiceList(), p.isActive());
        }
    }

    /* ── summary & subscription ── */

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        HmoOrganisation org = myOrganisation();
        List<EmployeeView> list = employeeViews(org, employees.findByOrganisationIdOrderByCreatedAtDesc(org.getId()));
        Instant monthStart = ZonedDateTime.now(AppTime.ZONE).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registered", list.stream().filter(e -> "REGISTERED".equals(e.status())).count());
        m.put("totalEmployees", list.stream().filter(e -> !"INACTIVE".equals(e.status())).count());
        m.put("pending", list.stream().filter(e -> "PENDING".equals(e.status())).count());
        m.put("inactive", list.stream().filter(e -> "INACTIVE".equals(e.status())).count());
        m.put("newUploads", uploads.countByOrganisationIdAndCreatedAtGreaterThanEqual(org.getId(), monthStart));
        return m;
    }

    /** The company's current (or most recent) subscription; null when it has none yet. */
    @Transactional(readOnly = true)
    public Map<String, Object> subscription() {
        HmoOrganisation org = myOrganisation();
        HmoSubscription sub = currentSubscription(org);
        if (sub == null) {
            return null;
        }
        HmoPlan plan = plans.findById(sub.getPlanId()).orElse(null);
        long members = employees.findByOrganisationIdOrderByCreatedAtDesc(org.getId()).stream()
            .filter(HmoEmployee::isActive).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sub.getId());
        m.put("organisationName", org.getCompanyName());
        m.put("planId", sub.getPlanId());
        m.put("planName", plan == null ? null : plan.getName());
        m.put("benefitLimit", plan == null ? null : plan.getBenefitLimit());
        m.put("benefitUsed", sub.getBenefitUsed());
        m.put("memberCount", members);
        m.put("memberLimit", sub.getMemberCount());
        m.put("startDate", sub.getStartDate());
        m.put("expiryDate", sub.getExpiryDate());
        m.put("status", sub.effectiveStatus(AppTime.today()));
        m.put("coveredServices", plan == null ? List.of() : plan.coveredServiceList());
        m.put("amountPaid", sub.getAmountPaid());
        m.put("planChangePending", planChanges.existsByOrganisationIdAndStatus(org.getId(), "PENDING"));
        return m;
    }

    /* ── employees ── */

    @Transactional(readOnly = true)
    public List<EmployeeView> employees(String status) {
        HmoOrganisation org = myOrganisation();
        List<EmployeeView> list = employeeViews(org, employees.findByOrganisationIdOrderByCreatedAtDesc(org.getId()));
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return list;
        }
        String st = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(st)) {
            throw ApiException.badRequest("Unknown status: " + status);
        }
        return list.stream().filter(e -> st.equals(e.status())).toList();
    }

    @Transactional
    public EmployeeView addEmployee(EmployeeBody body) {
        HmoOrganisation org = myOrganisation();
        Row row = new Row(body.firstName(), body.lastName(), body.email(), body.phone(), body.department(),
            body.staffId(), body.dateOfBirth(), body.gender());
        String problem = row.problem();
        if (problem != null) {
            throw ApiException.badRequest(problem);
        }
        Outcome out = save(org, row, null, false);
        if (!out.created()) {
            throw ApiException.conflict(row.email + " is already on your list.");
        }
        HmoEmployee e = employees.findByOrganisationIdAndUserId(org.getId(), out.userId()).orElseThrow();
        activity.record("HMO_EMPLOYEE_ADDED", org.getCompanyName() + " added " + e.fullName(), "HMO_EMPLOYEE", e.getId());
        return employeeViews(org, List.of(e)).get(0);
    }

    @Transactional
    public void resendInvite(Long id) {
        HmoOrganisation org = myOrganisation();
        HmoEmployee e = mine(org, id);
        if (!e.isActive()) {
            throw ApiException.conflict(e.fullName() + " is no longer on the plan.");
        }
        User u = users.findById(e.getUserId()).orElseThrow(() -> ApiException.notFound("Employee not found."));
        if (!u.hasPassword()) {
            passwordLinks.sendInvite(u);
        } else {
            /* account is set up; only next of kin / consent are missing */
            notifications.notify(u.getId(), "Finish your registration",
                org.getCompanyName() + " asked you to add your next of kin and sign the consent form to use your HMO cover.",
                "/patient/profile");
        }
        e.setInvitedAt(Instant.now());
        activity.record("HMO_EMPLOYEE_REINVITED", "Re-sent invite to " + e.fullName(), "HMO_EMPLOYEE", e.getId());
    }

    @Transactional
    public EmployeeView deactivate(Long id, DeactivateBody body) {
        HmoOrganisation org = myOrganisation();
        HmoEmployee e = mine(org, id);
        if (!e.isActive()) {
            throw ApiException.conflict(e.fullName() + " is already off the plan.");
        }
        e.setActive(false);
        e.setDeactivatedAt(Instant.now());
        e.setDeactivatedReason(body.reason().trim());
        patients.findByUserId(e.getUserId()).ifPresent(p -> {
            if (org.getId().equals(p.getHmoOrganisationId())) {
                p.setPatientType(Patient.STANDARD);
                p.setHmoOrganisationId(null);
            }
        });
        notifications.notify(e.getUserId(), "HMO cover ended",
            "You've been removed from " + org.getCompanyName() + "'s HMO plan. You can still book care and pay yourself.",
            "/patient");
        activity.record("HMO_EMPLOYEE_DEACTIVATED", org.getCompanyName() + " removed " + e.fullName() + " from its plan",
            "HMO_EMPLOYEE", e.getId());
        return employeeViews(org, List.of(e)).get(0);
    }

    /* ── bulk upload ── */

    @Transactional
    public UploadResult upload(MultipartFile file) {
        HmoOrganisation org = myOrganisation();
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a file to upload.");
        }
        if (file.getSize() > 2L * 1024 * 1024) {
            throw ApiException.badRequest("The file can be up to 2 MB.");
        }
        String name = file.getOriginalFilename() == null ? "employees.csv" : file.getOriginalFilename();
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw ApiException.badRequest("Upload a CSV file. In Excel, use File → Save As → CSV.");
        }
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw ApiException.badRequest("The file couldn't be read. Please try again.");
        }
        List<List<String>> table = parseCsv(text);
        if (table.isEmpty()) {
            throw ApiException.badRequest("The file is empty.");
        }
        List<String> fields = table.get(0).stream()
            .map(h -> ALIASES.get(h.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""))).toList();
        boolean hasNames = fields.contains("fullName") || (fields.contains("firstName") && fields.contains("lastName"));
        if (!hasNames || !fields.contains("email") || !fields.contains("phone")) {
            throw ApiException.badRequest("The file needs First name, Last name, Email and Phone columns. Download the template.");
        }
        int rowsCount = table.size() - 1;
        if (rowsCount > MAX_ROWS) {
            throw ApiException.badRequest("Upload up to " + MAX_ROWS + " employees at a time.");
        }

        User me = users.findById(CurrentUser.id()).orElseThrow();
        HmoUpload up = new HmoUpload(org.getId(), name.length() > 200 ? name.substring(name.length() - 200) : name);
        up.setUploadedById(me.getId());
        up.setUploadedByName(me.getFullName());
        HmoUpload savedUpload = uploads.save(up);

        int created = 0;
        int updated = 0;
        List<RowError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> cells = table.get(i);
            Map<String, String> d = new HashMap<>();
            for (int c = 0; c < fields.size(); c++) {
                if (fields.get(c) != null) {
                    d.put(fields.get(c), c < cells.size() ? cells.get(c).trim() : "");
                }
            }
            String first = d.get("firstName");
            String last = d.get("lastName");
            String full = d.get("fullName");
            if ((first == null || first.isBlank()) && full != null && !full.isBlank()) {
                String[] parts = full.trim().split("\\s+", 2);
                first = parts[0];
                last = (last == null || last.isBlank()) && parts.length > 1 ? parts[1] : last;
            }
            Row row = new Row(first, last, d.get("email"), d.get("phone"), d.get("department"), d.get("staffId"),
                d.get("dateOfBirth"), d.get("gender"));
            String problem = row.problem();
            if (problem == null && !seen.add(row.email)) {
                problem = "email repeated in file";
            }
            if (problem != null) {
                errors.add(new RowError(i + 1, problem));
                continue;
            }
            try {
                Outcome out = save(org, row, savedUpload.getId(), true);
                if (out.created()) {
                    created++;
                } else {
                    updated++;
                }
            } catch (ApiException ex) {
                errors.add(new RowError(i + 1, ex.getMessage()));
            }
        }

        savedUpload.setTotalRows(rowsCount);
        savedUpload.setCreated(created);
        savedUpload.setUpdated(updated);
        savedUpload.setSkipped(errors.size());
        savedUpload.setErrors(errors.isEmpty() ? null : json.writeValueAsString(errors));
        savedUpload.setStatus(errors.isEmpty() ? "COMPLETED" : (created + updated) == 0 ? "FAILED" : "PARTIAL");

        activity.record("HMO_EMPLOYEES_UPLOADED", org.getCompanyName() + " uploaded " + name + ": " + created
            + " added, " + updated + " updated, " + errors.size() + " skipped", "HMO_UPLOAD", savedUpload.getId());
        return new UploadResult(created, updated, errors.size(), errors);
    }

    @Transactional(readOnly = true)
    public List<UploadView> uploads() {
        HmoOrganisation org = myOrganisation();
        return uploads.findByOrganisationIdOrderByCreatedAtDesc(org.getId()).stream()
            .map(u -> new UploadView(u.getId(), u.getFileName(), u.getTotalRows(), u.getCreated(), u.getUpdated(),
                u.getSkipped(), readErrors(u.getErrors()), u.getStatus(), u.getUploadedByName(), u.getCreatedAt()))
            .toList();
    }

    /* ── utilisation ── */

    /**
     * Care requested by the company's employees in a year.
     * { year, monthly: [{ month, requests, amount }], byService: [{ service, requests, amount }],
     *   byEmployee: [{ employeeId, name, department, requests, amount }], totalRequests, totalAmount }
     * Amounts are what Finance invoiced each employee (cancelled invoices left out), by invoice date.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> utilisation(Integer year) {
        HmoOrganisation org = myOrganisation();
        int y = year == null ? AppTime.today().getYear() : year;
        if (y < 2000 || y > 2100) {
            throw ApiException.badRequest("Invalid year.");
        }
        Instant from = AppTime.startOf(LocalDate.of(y, 1, 1));
        Instant to = AppTime.startOf(LocalDate.of(y + 1, 1, 1));

        List<HmoEmployee> staff = employees.findByOrganisationIdOrderByCreatedAtDesc(org.getId());
        Map<String, Long> monthly = new LinkedHashMap<>();
        for (int mth = 1; mth <= 12; mth++) {
            monthly.put(String.format("%d-%02d", y, mth), 0L);
        }
        Map<String, Long> byService = new LinkedHashMap<>();
        Map<String, BigDecimal> monthAmount = new LinkedHashMap<>();
        Map<String, BigDecimal> serviceAmount = new LinkedHashMap<>();
        List<Map<String, Object>> byEmployee = new ArrayList<>();
        long total = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (HmoEmployee e : staff) {
            long count = 0;
            for (CareRequest r : careRequests.findByPatientIdOrderByCreatedAtDesc(e.getUserId())) {
                if (r.getCreatedAt().isBefore(from) || !r.getCreatedAt().isBefore(to)
                    || CareRequest.CANCELLED.equals(r.getStatus())) {
                    continue;
                }
                count++;
                String month = r.getCreatedAt().atZone(AppTime.ZONE).toLocalDate().toString().substring(0, 7);
                monthly.merge(month, 1L, Long::sum);
                byService.merge(r.getServiceNeeded() == null ? "OTHER" : r.getServiceNeeded(), 1L, Long::sum);
            }
            total += count;
            BigDecimal spent = BigDecimal.ZERO;
            for (Invoice inv : invoices.findByPatientUserIdAndStatusNotOrderByCreatedAtDesc(e.getUserId(), Invoice.CANCELLED)) {
                if (inv.getCreatedAt().isBefore(from) || !inv.getCreatedAt().isBefore(to)) {
                    continue;
                }
                String month = inv.getCreatedAt().atZone(AppTime.ZONE).toLocalDate().toString().substring(0, 7);
                monthAmount.merge(month, inv.getTotalAmount(), BigDecimal::add);
                serviceAmount.merge(inv.getCategory(), inv.getTotalAmount(), BigDecimal::add);
                byService.putIfAbsent(inv.getCategory(), 0L);
                spent = spent.add(inv.getTotalAmount());
            }
            totalAmount = totalAmount.add(spent);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeId", e.getId());
            row.put("name", e.fullName());
            row.put("department", e.getDepartment());
            row.put("requests", count);
            row.put("amount", spent);
            byEmployee.add(row);
        }
        byEmployee.sort((a, b) -> Long.compare((Long) b.get("requests"), (Long) a.get("requests")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", y);
        out.put("monthly", monthly.entrySet().stream()
            .map(x -> Map.<String, Object>of("month", x.getKey(), "requests", x.getValue(),
                "amount", monthAmount.getOrDefault(x.getKey(), BigDecimal.ZERO)))
            .toList());
        out.put("byService", byService.entrySet().stream()
            .map(x -> Map.<String, Object>of("service", x.getKey(), "requests", x.getValue(),
                "amount", serviceAmount.getOrDefault(x.getKey(), BigDecimal.ZERO)))
            .toList());
        out.put("byEmployee", byEmployee);
        out.put("totalRequests", total);
        out.put("totalAmount", totalAmount);
        return out;
    }

    /* ── plans ── */

    @Transactional(readOnly = true)
    public List<PlanView> plans() {
        return plans.findAllByOrderByActiveDescNameAsc().stream().filter(HmoPlan::isActive).map(PlanView::of).toList();
    }

    @Transactional
    public void requestPlanChange(PlanChangeBody body) {
        HmoOrganisation org = myOrganisation();
        HmoPlan plan = plans.findById(body.planId()).filter(HmoPlan::isActive)
            .orElseThrow(() -> ApiException.notFound("Plan not found."));
        HmoSubscription sub = currentSubscription(org);
        if (sub != null && sub.getPlanId().equals(plan.getId())) {
            throw ApiException.conflict("You're already on " + plan.getName() + ".");
        }
        if (planChanges.existsByOrganisationIdAndStatus(org.getId(), "PENDING")) {
            throw ApiException.conflict("You already have a plan change waiting for DiGi Health to review.");
        }
        HmoPlanChangeRequest req = new HmoPlanChangeRequest(org.getId(), plan.getId());
        req.setCurrentPlanId(sub == null ? null : sub.getPlanId());
        req.setNote(body.note() == null || body.note().isBlank() ? null : body.note().trim());
        req.setRequestedById(CurrentUser.id());
        HmoPlanChangeRequest saved = planChanges.save(req);

        List<Role> reviewers = new ArrayList<>(Role.ADMINS);
        reviewers.add(Role.RELATIONSHIP_MANAGER);
        notifications.notifyRoles(reviewers, "HMO plan change request",
            org.getCompanyName() + " asked to move to " + plan.getName()
                + (saved.getNote() == null ? "." : ": " + saved.getNote()), "/admin/hmo-subscriptions");
        activity.record("HMO_PLAN_CHANGE_REQUESTED", org.getCompanyName() + " asked to move to " + plan.getName(),
            "HMO_ORGANISATION", org.getId());
    }

    /* ── helpers ── */

    /** What save() did: created (or re-added) vs updated, and whose login it was. */
    private record Outcome(boolean created, Long userId) {
    }

    /** One employee row from the form or a CSV line, cleaned. */
    private static final class Row {
        final String firstName;
        final String lastName;
        final String email;
        final String phone;
        final String department;
        final String staffId;
        final String dateOfBirth;
        final String gender;

        Row(String firstName, String lastName, String email, String phone, String department, String staffId,
            String dateOfBirth, String gender) {
            this.firstName = clean(firstName);
            this.lastName = clean(lastName);
            this.email = email == null ? null : User.normalizeEmail(email);
            this.phone = clean(phone);
            this.department = clean(department);
            this.staffId = clean(staffId);
            this.dateOfBirth = clean(dateOfBirth);
            this.gender = clean(gender);
        }

        String problem() {
            if (firstName == null) {
                return "first name missing";
            }
            if (lastName == null) {
                return "last name missing";
            }
            if (firstName.length() > 80 || lastName.length() > 80) {
                return "name too long";
            }
            if (email == null || email.isBlank()) {
                return "email missing";
            }
            if (!EMAIL.matcher(email).matches() || email.length() > 255) {
                return "email looks wrong";
            }
            if (phone == null) {
                return "phone missing";
            }
            if (!PHONE.matcher(phone).matches()) {
                return "phone looks wrong";
            }
            if ((department != null && department.length() > 100) || (staffId != null && staffId.length() > 50)) {
                return "department or staff ID too long";
            }
            if (gender != null && genderCode(gender) == null) {
                return "gender should be Female or Male";
            }
            if (dateOfBirth != null) {
                try {
                    LocalDate dob = LocalDate.parse(dateOfBirth);
                    if (dob.isAfter(LocalDate.now()) || dob.isBefore(LocalDate.of(1900, 1, 1))) {
                        return "date of birth looks wrong";
                    }
                } catch (RuntimeException ex) {
                    return "date of birth should be YYYY-MM-DD";
                }
            }
            return null;
        }

        private static String clean(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    private static String genderCode(String g) {
        String x = g.trim().toUpperCase(Locale.ROOT);
        if (x.equals("F") || x.equals("FEMALE")) {
            return "FEMALE";
        }
        if (x.equals("M") || x.equals("MALE")) {
            return "MALE";
        }
        return null;
    }

    /**
     * Adds the person to the company's plan: creates their PATIENT login (and invite) if needed,
     * or links an existing patient. In an upload, a person already on the list is updated.
     */
    private Outcome save(HmoOrganisation org, Row row, Long uploadId, boolean updateExisting) {
        User u = users.findByEmail(row.email).orElse(null);
        boolean newUser = false;
        if (u == null) {
            u = new User(row.email, row.firstName + " " + row.lastName, Role.PATIENT);
            u.setPhoneNumber(row.phone);
            u.setCreatedById(CurrentUser.idOrNull());
            u = users.save(u);
            newUser = true;
        } else if (u.getRole() != Role.PATIENT) {
            throw ApiException.conflict(row.email + " belongs to a DiGi Health staff account. Use another email.");
        }
        if (!newUser && employees.existsByUserIdAndActiveTrueAndOrganisationIdNot(u.getId(), org.getId())) {
            throw ApiException.conflict(row.email + " is already covered by another company's plan.");
        }

        Patient p = patients.findByUserId(u.getId()).orElse(null);
        if (p == null) {
            p = new Patient(u.getId(), Patient.HMO);
        }
        p.setPatientType(Patient.HMO);
        p.setHmoOrganisationId(org.getId());
        if (row.dateOfBirth != null && p.getDateOfBirth() == null) {
            p.setDateOfBirth(LocalDate.parse(row.dateOfBirth));
        }
        if (row.gender != null && p.getGender() == null) {
            p.setGender(genderCode(row.gender));
        }
        patients.save(p);

        HmoEmployee e = employees.findByOrganisationIdAndUserId(org.getId(), u.getId()).orElse(null);
        boolean created;
        if (e == null) {
            e = new HmoEmployee(org.getId(), u.getId(), row.firstName, row.lastName);
            e.setCreatedById(CurrentUser.idOrNull());
            e.setUploadId(uploadId);
            created = true;
        } else if (e.isActive() && !updateExisting) {
            return new Outcome(false, u.getId());
        } else {
            created = !e.isActive();   // re-adding someone removed earlier counts as added
            e.setActive(true);
            e.setDeactivatedAt(null);
            e.setDeactivatedReason(null);
            e.setFirstName(row.firstName);
            e.setLastName(row.lastName);
        }
        if (row.department != null) {
            e.setDepartment(row.department);
        }
        if (row.staffId != null) {
            e.setStaffId(row.staffId);
        }
        if (created) {
            e.setInvitedAt(Instant.now());
        }
        employees.save(e);

        if (created) {
            if (!u.hasPassword()) {
                passwordLinks.sendInvite(u);
            } else {
                notifications.notify(u.getId(), "You're on your company's HMO plan",
                    org.getCompanyName() + " added you to its DiGi Health HMO plan.", "/patient");
            }
        }
        return new Outcome(created, u.getId());
    }

    private List<EmployeeView> employeeViews(HmoOrganisation org, List<HmoEmployee> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Collection<Long> userIds = list.stream().map(HmoEmployee::getUserId).distinct().toList();
        Map<Long, User> userById = users.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Patient> patientByUser = patients.findByUserIdIn(userIds).stream()
            .collect(Collectors.toMap(Patient::getUserId, Function.identity()));

        List<EmployeeView> out = new ArrayList<>();
        for (HmoEmployee e : list) {
            User u = userById.get(e.getUserId());
            Patient p = patientByUser.get(e.getUserId());
            boolean nok = p != null && p.getNextOfKinName() != null && !p.getNextOfKinName().isBlank();
            boolean consent = p != null && p.getConsentSignedAt() != null;
            String status = !e.isActive() ? "INACTIVE" : nok && consent ? "REGISTERED" : "PENDING";
            long requests = careRequests.findByPatientIdOrderByCreatedAtDesc(e.getUserId()).stream()
                .filter(r -> !CareRequest.CANCELLED.equals(r.getStatus()) && !r.getCreatedAt().isBefore(e.getCreatedAt()))
                .count();
            BigDecimal used = invoices.findByPatientUserIdAndStatusNotOrderByCreatedAtDesc(e.getUserId(), Invoice.CANCELLED)
                .stream().filter(inv -> !inv.getCreatedAt().isBefore(e.getCreatedAt()))
                .map(Invoice::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new EmployeeView(e.getId(), e.getUserId(), e.getFirstName(), e.getLastName(), e.fullName(),
                u == null ? null : u.getEmail(), u == null ? null : u.getPhoneNumber(), e.getDepartment(),
                e.getStaffId(), p == null ? null : p.getDateOfBirth(), p == null ? null : p.getGender(), status, nok,
                consent, p == null ? null : p.getNextOfKinName(), p == null ? null : p.getConsentSignedAt(),
                u != null && u.hasPassword(), e.getInvitedAt(), e.getDeactivatedAt(), e.getDeactivatedReason(),
                requests, used, e.getCreatedAt()));
        }
        return out;
    }

    private HmoOrganisation myOrganisation() {
        HmoOrganisation org = organisations.findByAdminUserId(CurrentUser.id())
            .orElseThrow(() -> ApiException.notFound("Your account isn't linked to a company yet. Please contact DiGi Health."));
        if (!HmoOrganisation.APPROVED.equals(org.getStatus())) {
            throw ApiException.forbidden("Your company hasn't been approved yet.");
        }
        return org;
    }

    private HmoEmployee mine(HmoOrganisation org, Long id) {
        return employees.findByIdAndOrganisationId(id, org.getId())
            .orElseThrow(() -> ApiException.notFound("Employee not found."));
    }

    /** Active and in date first, then the latest by expiry. */
    private HmoSubscription currentSubscription(HmoOrganisation org) {
        List<HmoSubscription> list = subscriptions.findByOrganisationIdOrderByExpiryDateDesc(org.getId());
        LocalDate today = AppTime.today();
        return list.stream().filter(s -> HmoSubscription.ACTIVE.equals(s.getStatus()) && !s.getExpiryDate().isBefore(today))
            .findFirst()
            .orElse(list.stream().filter(s -> !"CANCELLED".equals(s.getStatus())).findFirst().orElse(null));
    }

    @SuppressWarnings("unchecked")
    private List<RowError> readErrors(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = json.readValue(text, List.class);
            List<RowError> out = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Map<?, ?> m) {
                    Object row = m.get("row");
                    out.add(new RowError(row instanceof Number n ? n.intValue() : 0, String.valueOf(m.get("message"))));
                }
            }
            return out;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** RFC 4180-ish: quoted fields, escaped quotes, commas and newlines inside quotes. Blank lines dropped. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        String src = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < src.length() && src.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < src.length() && src.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                if (row.stream().anyMatch(c -> !c.isBlank())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        row.add(field.toString());
        if (row.stream().anyMatch(c -> !c.isBlank())) {
            rows.add(row);
        }
        return rows;
    }
}
