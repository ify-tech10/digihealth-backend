package com.digihealth.labportal;

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
import com.digihealth.labportal.CatalogueService.ItemBody;
import com.digihealth.labportal.CatalogueService.ItemView;
import com.digihealth.labportal.CatalogueService.StockBody;
import com.digihealth.labportal.LabPortalService.AcceptBody;
import com.digihealth.labportal.LabPortalService.DeclineBody;
import com.digihealth.labportal.LabPortalService.DeliverForm;
import com.digihealth.labportal.LabPortalService.ResultsForm;
import com.digihealth.labportal.LabPortalService.StartBody;

/*
 * DiGi's lab scientists (/lab/me) and pharmacists (/pharmacy/me) — src/Api/labApi.js. 13 endpoints.
 * One controller for both: the kind comes from the signed-in role, and SecurityConfig
 * keeps LAB_SCIENTIST on /lab/** and PHARMACIST on /pharmacy/**.
 */
@RestController
@RequestMapping({"/lab/me", "/pharmacy/me"})
public class LabPortalController {

    private final LabPortalService service;
    private final CatalogueService catalogue;

    public LabPortalController(LabPortalService service, CatalogueService catalogue) {
        this.service = service;
        this.catalogue = catalogue;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping("/activity")
    public List<ActivityView> activity(@RequestParam(name = "limit", defaultValue = "6") int limit) {
        return service.activity(limit);
    }

    @GetMapping("/requests")
    public List<Map<String, Object>> requests(@RequestParam(name = "status", required = false) String status) {
        return service.list(status);
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> request(@PathVariable("id") Long id) {
        return service.get(id);
    }

    @PutMapping("/requests/{id}/accept")
    public Map<String, Object> accept(@PathVariable("id") Long id, @Valid @RequestBody AcceptBody body) {
        return service.accept(id, body);
    }

    @PutMapping("/requests/{id}/decline")
    public Map<String, Object> decline(@PathVariable("id") Long id, @Valid @RequestBody DeclineBody body) {
        return service.decline(id, body);
    }

    @PutMapping("/requests/{id}/start")
    public Map<String, Object> start(@PathVariable("id") Long id, @Valid @RequestBody(required = false) StartBody body) {
        return service.start(id, body);
    }

    @PutMapping(value = "/requests/{id}/results", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> results(@PathVariable("id") Long id, @ModelAttribute ResultsForm form) {
        return service.submitResults(id, form);
    }

    @PutMapping(value = "/requests/{id}/deliver", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> deliver(@PathVariable("id") Long id, @ModelAttribute DeliverForm form) {
        return service.deliver(id, form);
    }

    @GetMapping("/catalogue")
    public List<ItemView> catalogue() {
        return catalogue.list();
    }

    @PostMapping("/catalogue")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView createItem(@Valid @RequestBody ItemBody body) {
        return catalogue.create(body);
    }

    @PutMapping("/catalogue/{id}")
    public ItemView updateItem(@PathVariable("id") Long id, @Valid @RequestBody ItemBody body) {
        return catalogue.update(id, body);
    }

    @PutMapping("/catalogue/{id}/stock")
    public ItemView adjustStock(@PathVariable("id") Long id, @Valid @RequestBody StockBody body) {
        return catalogue.adjustStock(id, body);
    }
}
