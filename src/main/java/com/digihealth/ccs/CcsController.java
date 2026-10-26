package com.digihealth.ccs;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.activity.ActivityService.ActivityView;
import com.digihealth.admin.AdminCareRequestService;
import com.digihealth.ccs.CcsService.CreateBody;
import com.digihealth.ccs.CcsService.EscalateBody;
import com.digihealth.ccs.CcsService.NoteBody;
import com.digihealth.ccs.CcsService.PatientCard;
import com.digihealth.ccs.CcsService.Performance;
import com.digihealth.ccs.CcsService.ReplyBody;
import com.digihealth.ccs.CcsService.Stats;
import com.digihealth.ccs.CcsService.StatusBody;
import com.digihealth.ccs.CcsService.TicketView;

/** Customer Care (15 endpoints here + POST /care-requests from Phase 5). */
@RestController
public class CcsController {

    private final CcsService ccs;
    private final AdminCareRequestService careRequests;

    public CcsController(CcsService ccs, AdminCareRequestService careRequests) {
        this.ccs = ccs;
        this.careRequests = careRequests;
    }

    @GetMapping("/customer-care/me/stats")
    public Stats stats() {
        return ccs.stats();
    }

    @GetMapping("/customer-care/me/tickets")
    public List<TicketView> tickets() {
        return ccs.list();
    }

    @GetMapping("/customer-care/me/tickets/{id}")
    public TicketView ticket(@PathVariable("id") Long id) {
        return ccs.get(id);
    }

    @PostMapping("/customer-care/me/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView create(@Valid @RequestBody CreateBody body) {
        return ccs.create(body);
    }

    @PostMapping("/customer-care/me/tickets/{id}/reply")
    public TicketView reply(@PathVariable("id") Long id, @Valid @RequestBody ReplyBody body) {
        return ccs.reply(id, body.message());
    }

    @PatchMapping("/customer-care/me/tickets/{id}/resolve")
    public TicketView resolve(@PathVariable("id") Long id, @Valid @RequestBody(required = false) NoteBody body) {
        return ccs.resolve(id, body == null ? null : body.note());
    }

    @PatchMapping("/customer-care/me/tickets/{id}/escalate")
    public TicketView escalate(@PathVariable("id") Long id, @Valid @RequestBody EscalateBody body) {
        return ccs.escalate(id, body);
    }

    @PatchMapping("/customer-care/me/tickets/{id}/reopen")
    public TicketView reopen(@PathVariable("id") Long id) {
        return ccs.reopen(id);
    }

    @GetMapping("/customer-care/me/activity")
    public List<ActivityView> activity(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ccs.activity(limit);
    }

    @GetMapping("/customer-care/me/status")
    public Map<String, String> status() {
        return ccs.status();
    }

    @PutMapping("/customer-care/me/status")
    public Map<String, String> setStatus(@Valid @RequestBody StatusBody body) {
        return ccs.setStatus(body.status());
    }

    @GetMapping("/customer-care/me/performance")
    public Performance performance(@RequestParam(name = "month", required = false) String month) {
        return ccs.performance(month);
    }

    @GetMapping("/customer-care/patients")
    public List<PatientCard> search(@RequestParam(name = "q", required = false) String q) {
        return ccs.search(q);
    }

    @GetMapping("/patients/{id}")
    public PatientCard patient(@PathVariable("id") Long id) {
        return ccs.patient(id);
    }

    @GetMapping("/customer-care/care-requests")
    public List<AdminCareRequestService.CareRequestView> careRequests(@RequestParam(name = "status", required = false) String status) {
        return careRequests.list(status);
    }
}
