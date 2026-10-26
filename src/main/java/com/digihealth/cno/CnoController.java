package com.digihealth.cno;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.activity.ActivityService.ActivityView;
import com.digihealth.admin.AdminUserService;
import com.digihealth.cno.CnoService.DailyCount;
import com.digihealth.cno.CnoService.NewProvider;
import com.digihealth.cno.CnoService.PerformanceRow;
import com.digihealth.cno.CnoService.ReportRow;
import com.digihealth.cno.CnoService.Stats;
import com.digihealth.cno.CnoService.TeamMember;
import com.digihealth.cno.CnoService.VisitRow;
import com.digihealth.common.ReasonBody;
import com.digihealth.finance.FinancialRequestService;

/** Clinical leadership portal (12 endpoints). /cno/** and GET /caregivers are limited in SecurityConfig. */
@RestController
public class CnoController {

    private final CnoService cno;
    private final FinancialRequestService financialRequests;

    public CnoController(CnoService cno, FinancialRequestService financialRequests) {
        this.cno = cno;
        this.financialRequests = financialRequests;
    }

    @GetMapping("/cno/dashboard/stats")
    public Stats stats() {
        return cno.stats();
    }

    @GetMapping("/cno/activity")
    public List<ActivityView> activity(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return cno.activity(limit);
    }

    /** The care team (approved providers by default). */
    @GetMapping("/caregivers")
    public List<TeamMember> team(@RequestParam(name = "status", required = false) String status) {
        return cno.team(status);
    }

    @GetMapping("/cno/visits/daily")
    public List<DailyCount> daily(@RequestParam(name = "from", required = false) String from,
                                  @RequestParam(name = "to", required = false) String to) {
        return cno.daily(from, to);
    }

    @PostMapping("/cno/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserService.UserView createProvider(@Valid @RequestBody NewProvider body) {
        return cno.createProvider(body);
    }

    @GetMapping("/cno/visit-reports")
    public List<ReportRow> visitReports(@RequestParam(name = "status", required = false) String status,
                                        @RequestParam(name = "from", required = false) String from,
                                        @RequestParam(name = "to", required = false) String to) {
        return cno.reports(status, from, to);
    }

    @PutMapping("/cno/visit-reports/{id}/approve")
    public ReportRow approve(@PathVariable("id") Long id, @Valid @RequestBody(required = false) ReasonBody.Note body) {
        return cno.approve(id, body == null ? null : body.note());
    }

    @PutMapping("/cno/visit-reports/{id}/return")
    public ReportRow returnReport(@PathVariable("id") Long id, @Valid @RequestBody(required = false) ReasonBody.Reason body) {
        return cno.returnReport(id, body == null ? null : body.reason());
    }

    @GetMapping("/cno/performance")
    public List<PerformanceRow> performance(@RequestParam(name = "month", required = false) String month) {
        return cno.performance(month);
    }

    @GetMapping("/cno/visits")
    public List<VisitRow> visits(@RequestParam(name = "from", required = false) String from,
                                 @RequestParam(name = "to", required = false) String to) {
        return cno.visits(from, to);
    }

    @GetMapping("/cno/financial-requests")
    public List<FinancialRequestService.RequestView> financialRequests() {
        return financialRequests.mine();
    }

    @PostMapping(path = "/cno/financial-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FinancialRequestService.RequestView createFinancialRequest(@Valid @ModelAttribute FinancialRequestService.Form form) {
        return financialRequests.create(form);
    }
}
