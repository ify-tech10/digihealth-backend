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
import com.digihealth.finance.BillingService;
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
    private final BillingService billing;
    private final PatientRecordsService records;

    public PatientPortalController(PatientBookingService bookings, PatientAccountService account,
                                   PatientSupportService support, BillingService billing,
                                   PatientRecordsService records) {
        this.bookings = bookings;
        this.account = account;
        this.support = support;
        this.billing = billing;
        this.records = records;
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

    /* ── Records: results and drugs from partner facilities and DiGi's lab / pharmacy ── */

    @GetMapping("/lab-results")
    public List<Map<String, Object>> labResults() {
        return records.labResults();
    }

    @GetMapping("/medications")
    public List<Map<String, Object>> medications() {
        return records.medications();
    }

    /** Approved visit reports plus lab reports, discharge summaries and drug deliveries, newest first. */
    @GetMapping("/documents")
    public List<DocumentView> documents() {
        List<DocumentView> all = new java.util.ArrayList<>(account.documents());
        all.addAll(records.documents());
        all.sort(java.util.Comparator.comparing(DocumentView::createdAt,
            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return all;
    }

    /* ── Bills: invoices from Finance, paid online through Paystack ── */

    @GetMapping("/invoices")
    public List<BillingService.InvoiceView> invoices() {
        return billing.myInvoices();
    }

    /** { authorizationUrl, reference } - the page sends the patient to authorizationUrl. */
    @PostMapping("/invoices/{id}/pay")
    public Map<String, String> pay(@PathVariable("id") Long id) {
        return billing.startPayment(id);
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
