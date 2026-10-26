package com.digihealth.patientportal;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.common.ReasonBody;
import com.digihealth.patientportal.PatientAccountService.DocumentView;
import com.digihealth.patientportal.PatientAccountService.PaymentView;
import com.digihealth.patientportal.PatientAccountService.ProfileBody;
import com.digihealth.patientportal.PatientAccountService.ProfileView;
import com.digihealth.patientportal.PatientBookingService.BookBody;
import com.digihealth.patientportal.PatientBookingService.BookingView;
import com.digihealth.patientportal.PatientBookingService.CarePlan;
import com.digihealth.patientportal.PatientBookingService.FeedbackBody;
import com.digihealth.patientportal.PatientBookingService.RescheduleBody;
import com.digihealth.patientportal.PatientBookingService.VisitView;
import com.digihealth.support.PatientSupportService;
import com.digihealth.support.PatientSupportService.TicketView;

/** /patient/me/... for the signed-in PATIENT (19 endpoints). */
@RestController
@RequestMapping("/patient/me")
public class PatientPortalController {

    private final PatientBookingService bookings;
    private final PatientAccountService account;
    private final PatientSupportService support;

    public PatientPortalController(PatientBookingService bookings, PatientAccountService account,
                                   PatientSupportService support) {
        this.bookings = bookings;
        this.account = account;
        this.support = support;
    }

    /* ── Care ── */

    @GetMapping("/care-plan")
    public CarePlan carePlan() {
        return bookings.carePlan();
    }

    @GetMapping("/bookings")
    public List<BookingView> bookings() {
        return bookings.bookings();
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView book(@Valid @RequestBody BookBody body) {
        return bookings.book(body);
    }

    @PutMapping("/bookings/{id}/reschedule")
    public BookingView reschedule(@PathVariable("id") Long id, @Valid @RequestBody RescheduleBody body) {
        return bookings.reschedule(id, body);
    }

    @PutMapping("/bookings/{id}/cancel")
    public BookingView cancel(@PathVariable("id") Long id, @Valid @RequestBody(required = false) ReasonBody.Reason body) {
        return bookings.cancel(id, body == null ? null : body.reason());
    }

    @GetMapping("/visits")
    public List<VisitView> visits() {
        return bookings.visits();
    }

    @PostMapping("/visits/{id}/feedback")
    public VisitView rateVisit(@PathVariable("id") Long id, @Valid @RequestBody FeedbackBody body) {
        return bookings.rate(id, body);
    }

    /* ── Records (lab results and medications arrive with the lab / pharmacy portals) ── */

    @GetMapping("/lab-results")
    public List<Object> labResults() {
        return List.of();
    }

    @GetMapping("/medications")
    public List<Object> medications() {
        return List.of();
    }

    @GetMapping("/documents")
    public List<DocumentView> documents() {
        return account.documents();
    }

    /* ── Bills (patient invoices arrive with Finance, Phase 12) ── */

    @GetMapping("/invoices")
    public List<Object> invoices() {
        return List.of();
    }

    @PostMapping("/invoices/{id}/pay")
    public Map<String, String> pay(@PathVariable("id") Long id) {
        return account.pay(id);
    }

    @GetMapping("/payments")
    public List<PaymentView> payments() {
        return account.payments();
    }

    /* ── Support ── */

    @GetMapping("/tickets")
    public List<TicketView> tickets() {
        return support.list();
    }

    @GetMapping("/tickets/{id}")
    public TicketView ticket(@PathVariable("id") Long id) {
        return support.get(id);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView createTicket(@Valid @RequestBody PatientSupportService.CreateBody body) {
        return support.create(body);
    }

    @PostMapping("/tickets/{id}/reply")
    public TicketView reply(@PathVariable("id") Long id, @Valid @RequestBody PatientSupportService.ReplyBody body) {
        return support.reply(id, body.message());
    }

    /* ── Profile ── */

    @GetMapping("/profile")
    public ProfileView profile() {
        return account.profile();
    }

    @PutMapping("/profile")
    public ProfileView updateProfile(@Valid @RequestBody ProfileBody body) {
        return account.update(body);
    }
}
