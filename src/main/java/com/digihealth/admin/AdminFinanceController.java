package com.digihealth.admin;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.admin.AdminFinanceService.FinancialRequestView;
import com.digihealth.admin.AdminFinanceService.InvoiceView;
import com.digihealth.admin.AdminFinanceService.PaymentView;
import com.digihealth.admin.AdminHmoPlanService.PlanBody;
import com.digihealth.admin.AdminHmoPlanService.PlanView;
import com.digihealth.admin.AdminHmoPlanService.RenewBody;
import com.digihealth.admin.AdminHmoPlanService.SubscriptionBody;
import com.digihealth.admin.AdminHmoPlanService.SubscriptionView;
import com.digihealth.common.ReasonBody;

/** Admin portal, part D: HMO plans and subscriptions, payments, financial requests, facility invoices. */
@RestController
@RequestMapping("/admin")
public class AdminFinanceController {

    private final AdminHmoPlanService hmo;
    private final AdminFinanceService finance;

    public AdminFinanceController(AdminHmoPlanService hmo, AdminFinanceService finance) {
        this.hmo = hmo;
        this.finance = finance;
    }

    /* ── HMO plans ── */

    @GetMapping("/hmo/plans")
    public List<PlanView> plans() {
        return hmo.plans();
    }

    @PostMapping("/hmo/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView createPlan(@Valid @RequestBody PlanBody body) {
        return hmo.createPlan(body);
    }

    @PutMapping("/hmo/plans/{id}")
    public PlanView updatePlan(@PathVariable("id") Long id, @Valid @RequestBody PlanBody body) {
        return hmo.updatePlan(id, body);
    }

    /* ── HMO subscriptions ── */

    @GetMapping("/hmo/subscriptions")
    public List<SubscriptionView> subscriptions(@RequestParam(name = "status", required = false) String status) {
        return hmo.subscriptions(status);
    }

    @PostMapping("/hmo/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionView createSubscription(@Valid @RequestBody SubscriptionBody body) {
        return hmo.create(body);
    }

    @PutMapping("/hmo/subscriptions/{id}/renew")
    public SubscriptionView renew(@PathVariable("id") Long id, @Valid @RequestBody RenewBody body) {
        return hmo.renew(id, body);
    }

    /* ── Payments ── */

    @GetMapping("/payments")
    public List<PaymentView> payments() {
        return finance.payments();
    }

    /* ── Financial requests (CNO / MD / Finance) ── */

    @GetMapping("/financial-requests")
    public List<FinancialRequestView> financialRequests(@RequestParam(name = "status", required = false) String status) {
        return finance.financialRequests(status);
    }

    @PutMapping("/financial-requests/{id}/approve")
    public ResponseEntity<Void> approveRequest(@PathVariable("id") Long id,
                                               @Valid @RequestBody(required = false) ReasonBody.Note body) {
        finance.approveRequest(id, body == null ? null : body.note());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/financial-requests/{id}/reject")
    public ResponseEntity<Void> rejectRequest(@PathVariable("id") Long id,
                                              @Valid @RequestBody(required = false) ReasonBody.Reason body) {
        finance.rejectRequest(id, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    /* ── Facility invoices ── */

    @GetMapping("/invoices")
    public List<InvoiceView> invoices(@RequestParam(name = "status", required = false) String status) {
        return finance.invoices(status);
    }

    @PutMapping("/invoices/{id}/approve")
    public ResponseEntity<Void> approveInvoice(@PathVariable("id") Long id,
                                               @Valid @RequestBody(required = false) ReasonBody.Note body) {
        finance.approveInvoice(id, body == null ? null : body.note());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/invoices/{id}/reject")
    public ResponseEntity<Void> rejectInvoice(@PathVariable("id") Long id,
                                              @Valid @RequestBody(required = false) ReasonBody.Reason body) {
        finance.rejectInvoice(id, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }
}
