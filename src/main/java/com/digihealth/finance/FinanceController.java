package com.digihealth.finance;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import com.digihealth.activity.ActivityService.ActivityView;
import com.digihealth.common.ReasonBody;

/*
 * Finance Manager portal (/finance, src/Api/financeApi.js). FINANCE_MANAGER only. 27 endpoints.
 * Money is plain naira; month = YYYY-MM.
 */
@RestController
@RequestMapping("/finance")
public class FinanceController {

    private final FinanceService finance;
    private final BillingService billing;
    private final PayrollService payroll;
    private final FinancialRequestService financialRequests;

    public FinanceController(FinanceService finance, BillingService billing, PayrollService payroll,
                             FinancialRequestService financialRequests) {
        this.finance = finance;
        this.billing = billing;
        this.payroll = payroll;
        this.financialRequests = financialRequests;
    }

    public record MonthBody(String month) {
    }

    public record ReferenceBody(String reference) {
    }

    /* ── dashboard ── */

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(name = "month", required = false) String month) {
        return finance.summary(month);
    }

    @GetMapping("/monthly")
    public List<Map<String, Object>> monthly(@RequestParam(name = "year", required = false) Integer year) {
        return finance.monthly(year);
    }

    @GetMapping("/revenue-by-service")
    public List<Map<String, Object>> revenueByService(@RequestParam(name = "month", required = false) String month) {
        return finance.revenueByService(month);
    }

    @GetMapping("/activity")
    public List<ActivityView> activity(@RequestParam(name = "limit", defaultValue = "6") int limit) {
        return finance.activity(limit);
    }

    /* ── invoices & payments ── */

    @GetMapping("/invoices")
    public List<BillingService.InvoiceView> invoices(@RequestParam(name = "status", required = false) String status) {
        return billing.invoices(status);
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.InvoiceView createInvoice(@Valid @RequestBody BillingService.InvoiceBody body) {
        return billing.createInvoice(body);
    }

    @PostMapping("/invoices/{id}/remind")
    public BillingService.InvoiceView remind(@PathVariable("id") Long id) {
        return billing.remind(id);
    }

    @PutMapping("/invoices/{id}/cancel")
    public BillingService.InvoiceView cancel(@PathVariable("id") Long id, @Valid @RequestBody ReasonBody.Reason body) {
        return billing.cancel(id, body.reason());
    }

    @GetMapping("/payments")
    public List<BillingService.PaymentView> payments(@RequestParam(name = "month", required = false) String month) {
        return billing.payments(month);
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.PaymentView recordPayment(@Valid @RequestBody BillingService.PaymentBody body) {
        return billing.recordPayment(body);
    }

    /* ── HMO claims ── */

    @GetMapping("/hmo-claims")
    public List<FinanceService.ClaimView> claims(@RequestParam(name = "status", required = false) String status) {
        return finance.claims(status);
    }

    @PostMapping("/hmo-claims")
    @ResponseStatus(HttpStatus.CREATED)
    public FinanceService.ClaimView createClaim(@Valid @RequestBody FinanceService.ClaimBody body) {
        return finance.createClaim(body);
    }

    @PostMapping("/hmo-claims/{id}/follow-up")
    public FinanceService.ClaimView followUp(@PathVariable("id") Long id, @Valid @RequestBody(required = false) ReasonBody.Note body) {
        return finance.followUp(id, body == null ? null : body.note());
    }

    @PutMapping("/hmo-claims/{id}/paid")
    public FinanceService.ClaimView claimPaid(@PathVariable("id") Long id, @Valid @RequestBody FinanceService.ClaimPaidBody body) {
        return finance.claimPaid(id, body);
    }

    /* ── expense claims ── */

    @GetMapping("/expense-claims")
    public List<FinanceService.ExpenseView> expenseClaims(@RequestParam(name = "status", required = false) String status) {
        return finance.expenseClaims(status);
    }

    @PutMapping("/expense-claims/{id}/approve")
    public FinanceService.ExpenseView approveExpense(@PathVariable("id") Long id, @Valid @RequestBody(required = false) ReasonBody.Note body) {
        return finance.approveExpense(id, body == null ? null : body.note());
    }

    @PutMapping("/expense-claims/{id}/reject")
    public FinanceService.ExpenseView rejectExpense(@PathVariable("id") Long id, @Valid @RequestBody ReasonBody.Reason body) {
        return finance.rejectExpense(id, body.reason());
    }

    /* ── facility bills ── */

    @GetMapping("/facility-invoices")
    public List<FinanceService.BillView> bills(@RequestParam(name = "status", required = false) String status) {
        return finance.bills(status);
    }

    @PutMapping("/facility-invoices/{id}/pay")
    public FinanceService.BillView payBill(@PathVariable("id") Long id, @Valid @RequestBody FinanceService.BillPayBody body) {
        return finance.payBill(id, body);
    }

    /* ── payroll ── */

    @GetMapping("/payroll")
    public PayrollService.PayrollView payroll(@RequestParam(name = "month", required = false) String month) {
        return payroll.payroll(month);
    }

    @PostMapping("/payroll/process")
    public PayrollService.PayrollView processPayroll(@RequestBody MonthBody body) {
        return payroll.process(body.month());
    }

    @PutMapping("/payroll/{id}/paid")
    public PayrollService.LineView markPaid(@PathVariable("id") Long id, @RequestBody(required = false) ReferenceBody body) {
        return payroll.markPaid(id, body == null ? null : body.reference());
    }

    /* ── budget & reports ── */

    @GetMapping("/budget")
    public List<Map<String, Object>> budget(@RequestParam(name = "month", required = false) String month) {
        return finance.budget(month);
    }

    @PutMapping("/budget")
    public List<Map<String, Object>> setBudget(@Valid @RequestBody FinanceService.BudgetBody body) {
        return finance.setBudget(body);
    }

    @GetMapping("/reports")
    public Map<String, Object> report(@RequestParam(name = "type") String type,
                                      @RequestParam(name = "from", required = false) String from,
                                      @RequestParam(name = "to", required = false) String to) {
        return finance.report(type, from, to);
    }

    /* ── Finance Manager's own fund requests (Admin approves) ── */

    @GetMapping("/financial-requests")
    public List<FinancialRequestService.RequestView> financialRequests() {
        return financialRequests.mine();
    }

    @PostMapping(value = "/financial-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FinancialRequestService.RequestView createFinancialRequest(@Valid @ModelAttribute FinancialRequestService.Form form) {
        return financialRequests.create(form);
    }
}
