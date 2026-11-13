package com.digihealth.facilityportal;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.facilityportal.FacilityAccountService.InvoiceForm;
import com.digihealth.facilityportal.FacilityAccountService.InvoiceView;
import com.digihealth.facilityportal.FacilityAccountService.ProfileBody;
import com.digihealth.facilityportal.FacilityAccountService.TestBody;
import com.digihealth.facilityportal.FacilityAccountService.TestView;
import com.digihealth.facilityportal.FacilityRequestService.AcceptBody;
import com.digihealth.facilityportal.FacilityRequestService.AdmitBody;
import com.digihealth.facilityportal.FacilityRequestService.CollectBody;
import com.digihealth.facilityportal.FacilityRequestService.DeclineBody;
import com.digihealth.facilityportal.FacilityRequestService.DeliverForm;
import com.digihealth.facilityportal.FacilityRequestService.DischargeForm;
import com.digihealth.facilityportal.FacilityRequestService.DispatchBody;
import com.digihealth.facilityportal.FacilityRequestService.ResultsForm;

/*
 * Partner facility portals: /hospital, /pharmacy, /laboratory (src/Api/facilityApi.js).
 * HOSPITAL_ADMIN / PHARMACY_ADMIN / LAB_ADMIN only (SecurityConfig). 19 endpoints.
 * The facility always comes from the signed-in user.
 */
@RestController
@RequestMapping("/facility/me")
public class FacilityPortalController {

    private final FacilityRequestService requests;
    private final FacilityAccountService account;

    public FacilityPortalController(FacilityRequestService requests, FacilityAccountService account) {
        this.requests = requests;
        this.account = account;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return requests.summary();
    }

    /* ── referrals / drug orders / test requests ── */

    @GetMapping("/requests")
    public List<Map<String, Object>> requests(@RequestParam(name = "status", required = false) String status) {
        return requests.list(status);
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> request(@PathVariable("id") Long id) {
        return requests.get(id);
    }

    @PutMapping("/requests/{id}/accept")
    public Map<String, Object> accept(@PathVariable("id") Long id, @Valid @RequestBody AcceptBody body) {
        return requests.accept(id, body);
    }

    @PutMapping("/requests/{id}/decline")
    public Map<String, Object> decline(@PathVariable("id") Long id, @Valid @RequestBody DeclineBody body) {
        return requests.decline(id, body);
    }

    @PutMapping("/requests/{id}/admit")
    public Map<String, Object> admit(@PathVariable("id") Long id, @Valid @RequestBody(required = false) AdmitBody body) {
        return requests.admit(id, body);
    }

    @PutMapping(value = "/requests/{id}/discharge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> discharge(@PathVariable("id") Long id, @ModelAttribute DischargeForm form) {
        return requests.discharge(id, form);
    }

    @PutMapping("/requests/{id}/dispatch")
    public Map<String, Object> dispatch(@PathVariable("id") Long id, @Valid @RequestBody(required = false) DispatchBody body) {
        return requests.dispatch(id, body);
    }

    @PutMapping(value = "/requests/{id}/deliver", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> deliver(@PathVariable("id") Long id, @ModelAttribute DeliverForm form) {
        return requests.deliver(id, form);
    }

    @PutMapping("/requests/{id}/collect")
    public Map<String, Object> collect(@PathVariable("id") Long id, @Valid @RequestBody CollectBody body) {
        return requests.collect(id, body);
    }

    @PutMapping(value = "/requests/{id}/results", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> results(@PathVariable("id") Long id, @ModelAttribute ResultsForm form) {
        return requests.submitResults(id, form);
    }

    /* ── lab test menu ── */

    @GetMapping("/tests")
    public List<TestView> tests() {
        return account.tests();
    }

    @PostMapping("/tests")
    @ResponseStatus(HttpStatus.CREATED)
    public TestView createTest(@Valid @RequestBody TestBody body) {
        return account.createTest(body);
    }

    @PutMapping("/tests/{id}")
    public TestView updateTest(@PathVariable("id") Long id, @Valid @RequestBody TestBody body) {
        return account.updateTest(id, body);
    }

    @DeleteMapping("/tests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTest(@PathVariable("id") Long id) {
        account.deleteTest(id);
    }

    /* ── invoices to DiGi ── */

    @GetMapping("/invoices")
    public List<InvoiceView> invoices() {
        return account.invoices();
    }

    @PostMapping(value = "/invoices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceView createInvoice(@ModelAttribute InvoiceForm form) {
        return account.createInvoice(form);
    }

    /* ── profile ── */

    @GetMapping("/profile")
    public Map<String, Object> profile() {
        return account.profile();
    }

    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@Valid @RequestBody ProfileBody body) {
        return account.updateProfile(body);
    }
}
