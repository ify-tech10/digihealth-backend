package com.digihealth.caregiver;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.caregiver.CaregiverEarningsService.PayoutRow;
import com.digihealth.caregiver.CaregiverEarningsService.StructureBody;
import com.digihealth.caregiver.CaregiverEarningsService.StructureView;
import com.digihealth.caregiver.CaregiverEarningsService.Summary;
import com.digihealth.caregiver.CaregiverProfileService.MonthBody;
import com.digihealth.caregiver.CaregiverProfileService.MonthView;
import com.digihealth.caregiver.CaregiverProfileService.ProfileBody;
import com.digihealth.caregiver.CaregiverProfileService.ProfileView;
import com.digihealth.caregiver.CaregiverProfileService.StatusView;
import com.digihealth.caregiver.CaregiverService.CloseBody;
import com.digihealth.caregiver.CaregiverService.Dashboard;
import com.digihealth.caregiver.CaregiverService.PatientRow;
import com.digihealth.caregiver.CaregiverService.ReportBody;
import com.digihealth.caregiver.CaregiverService.ReportRow;
import com.digihealth.caregiver.CaregiverService.ScheduleVisitBody;
import com.digihealth.caregiver.CaregiverService.VisitRow;

/** /caregivers/me/... for SERVICE_PROVIDER and CLINICAL_PERSONNEL (19 endpoints). */
@RestController
@RequestMapping("/caregivers/me")
public class CaregiverController {

    private final CaregiverService work;
    private final CaregiverProfileService profile;
    private final CaregiverEarningsService earnings;

    public CaregiverController(CaregiverService work, CaregiverProfileService profile, CaregiverEarningsService earnings) {
        this.work = work;
        this.profile = profile;
        this.earnings = earnings;
    }

    /* ── Dashboard ── */

    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        return work.dashboard();
    }

    @GetMapping("/schedule/today")
    public List<VisitRow> today() {
        return work.today();
    }

    /* ── Availability ── */

    @GetMapping("/availability/status")
    public StatusView availabilityStatus() {
        return profile.status();
    }

    @PutMapping("/availability/status")
    public StatusView setAvailabilityStatus(@Valid @RequestBody CaregiverProfileService.StatusBody body) {
        return profile.setStatus(body.isAvailable());
    }

    @GetMapping("/availability")
    public MonthView availability(@RequestParam(name = "month", required = false) String month) {
        return profile.month(month);
    }

    @PutMapping("/availability")
    public MonthView saveAvailability(@Valid @RequestBody MonthBody body) {
        return profile.saveMonth(body);
    }

    /* ── Patients and requests ── */

    @GetMapping("/patients")
    public List<PatientRow> patients() {
        return work.patients();
    }

    @PutMapping("/requests/{requestId}/close")
    public PatientRow close(@PathVariable("requestId") Long requestId, @Valid @RequestBody CloseBody body) {
        return work.close(requestId, body.closureNote());
    }

    /* ── Visits ── */

    @GetMapping("/visits")
    public List<VisitRow> visits(@RequestParam(name = "from", required = false) String from,
                                 @RequestParam(name = "to", required = false) String to) {
        return work.visits(from, to);
    }

    @PostMapping("/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public VisitRow scheduleVisit(@Valid @RequestBody ScheduleVisitBody body) {
        return work.schedule(body);
    }

    @PutMapping("/visits/{id}/status")
    public VisitRow updateVisitStatus(@PathVariable("id") Long id,
                                      @Valid @RequestBody CaregiverService.StatusBody body) {
        return work.updateStatus(id, body.status());
    }

    /* ── Visit reports ── */

    @GetMapping("/visit-reports")
    public List<ReportRow> visitReports() {
        return work.reports();
    }

    @PostMapping("/visit-reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportRow createVisitReport(@Valid @RequestBody ReportBody body) {
        return work.createReport(body);
    }

    /* ── Profile ── */

    @GetMapping("/profile")
    public ProfileView profile() {
        return profile.profile();
    }

    @PutMapping("/profile")
    public ProfileView updateProfile(@Valid @RequestBody ProfileBody body) {
        return profile.updateProfile(body);
    }

    /* ── Earnings ── */

    @GetMapping("/earnings/summary")
    public Summary earningsSummary() {
        return earnings.summary();
    }

    @GetMapping("/earnings/structure")
    public StructureView earningStructure() {
        return earnings.structure();
    }

    @PutMapping("/earnings/structure")
    public StructureView setEarningStructure(@Valid @RequestBody StructureBody body) {
        return earnings.setStructure(body);
    }

    @GetMapping("/earnings")
    public List<PayoutRow> payouts() {
        return earnings.payouts();
    }
}
