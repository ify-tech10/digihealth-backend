package com.digihealth.hmoportal;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.digihealth.hmoportal.HmoPortalService.DeactivateBody;
import com.digihealth.hmoportal.HmoPortalService.EmployeeBody;
import com.digihealth.hmoportal.HmoPortalService.EmployeeView;
import com.digihealth.hmoportal.HmoPortalService.PlanChangeBody;
import com.digihealth.hmoportal.HmoPortalService.PlanView;
import com.digihealth.hmoportal.HmoPortalService.UploadResult;
import com.digihealth.hmoportal.HmoPortalService.UploadView;

/*
 * HMO organisation admin portal (/hmo-dashboard, src/Api/hmoApi.js). Role HMO only. 11 endpoints.
 * POST /hmo/apply (the public application form) lives in HmoApplicationController.
 */
@RestController
@RequestMapping("/hmo")
public class HmoPortalController {

    private final HmoPortalService service;

    public HmoPortalController(HmoPortalService service) {
        this.service = service;
    }

    @GetMapping("/me/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    /** null (empty 200) when the company has no plan yet. */
    @GetMapping("/me/subscription")
    public Map<String, Object> subscription() {
        return service.subscription();
    }

    @GetMapping("/me/employees")
    public List<EmployeeView> employees(@RequestParam(name = "status", required = false) String status) {
        return service.employees(status);
    }

    @PostMapping("/me/employees")
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeView addEmployee(@Valid @RequestBody EmployeeBody body) {
        return service.addEmployee(body);
    }

    @PostMapping("/me/employees/{id}/invite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendInvite(@PathVariable("id") Long id) {
        service.resendInvite(id);
    }

    @PutMapping("/me/employees/{id}/deactivate")
    public EmployeeView deactivate(@PathVariable("id") Long id, @Valid @RequestBody DeactivateBody body) {
        return service.deactivate(id, body);
    }

    @PostMapping(value = "/me/employees/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult upload(@RequestPart("file") MultipartFile file) {
        return service.upload(file);
    }

    @GetMapping("/me/uploads")
    public List<UploadView> uploads() {
        return service.uploads();
    }

    @GetMapping("/me/utilisation")
    public Map<String, Object> utilisation(@RequestParam(name = "year", required = false) Integer year) {
        return service.utilisation(year);
    }

    @GetMapping("/plans")
    public List<PlanView> plans() {
        return service.plans();
    }

    @PostMapping("/me/plan-change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestPlanChange(@Valid @RequestBody PlanChangeBody body) {
        service.requestPlanChange(body);
    }
}
