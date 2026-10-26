package com.digihealth.admin;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.admin.AdminCareRequestService.AssignBody;
import com.digihealth.admin.AdminCareRequestService.AvailableProvider;
import com.digihealth.admin.AdminCareRequestService.CareRequestView;
import com.digihealth.admin.AdminCareRequestService.VisitView;
import com.digihealth.common.ReasonBody;

/** Admin portal, part B: care requests and the daily schedule. */
@RestController
@RequestMapping("/admin")
public class AdminCareRequestController {

    private final AdminCareRequestService service;

    public AdminCareRequestController(AdminCareRequestService service) {
        this.service = service;
    }

    @GetMapping("/care-requests")
    public List<CareRequestView> careRequests(@RequestParam(name = "status", required = false) String status) {
        return service.list(status);
    }

    @PutMapping("/care-requests/{id}/assign")
    public CareRequestView assign(@PathVariable("id") Long id, @Valid @RequestBody AssignBody body) {
        return service.assign(id, body);
    }

    @GetMapping("/providers/available")
    public List<AvailableProvider> available(@RequestParam(name = "careRequestId", required = false) Long careRequestId,
                                             @RequestParam(name = "location", required = false) String location) {
        return service.available(careRequestId, location);
    }

    @PutMapping("/care-requests/{id}/approve-closure")
    public CareRequestView approveClosure(@PathVariable("id") Long id,
                                          @Valid @RequestBody(required = false) ReasonBody.Note body) {
        return service.approveClosure(id, body == null ? null : body.note());
    }

    @PutMapping("/care-requests/{id}/reject-closure")
    public CareRequestView rejectClosure(@PathVariable("id") Long id,
                                         @Valid @RequestBody(required = false) ReasonBody.Reason body) {
        return service.rejectClosure(id, body == null ? null : body.reason());
    }

    /** GET /admin/visits?date=2026-10-20 (defaults to today, Lagos time) */
    @GetMapping("/visits")
    public List<VisitView> visits(@RequestParam(name = "date", required = false) String date) {
        return service.visitsOn(date);
    }
}
