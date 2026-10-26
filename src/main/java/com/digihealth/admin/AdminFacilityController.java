package com.digihealth.admin;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.admin.AdminFacilityService.FacilityBody;
import com.digihealth.admin.AdminFacilityService.FacilityView;

/** Admin portal, part C: /admin/hospitals, /admin/pharmacies, /admin/laboratories */
@RestController
@RequestMapping("/admin")
public class AdminFacilityController {

    private static final String KIND = "{kind:hospitals|pharmacies|laboratories}";

    private final AdminFacilityService service;

    public AdminFacilityController(AdminFacilityService service) {
        this.service = service;
    }

    @GetMapping("/" + KIND)
    public List<FacilityView> list(@PathVariable("kind") String kind) {
        return service.list(kind);
    }

    @PostMapping("/" + KIND)
    @ResponseStatus(HttpStatus.CREATED)
    public FacilityView create(@PathVariable("kind") String kind, @Valid @RequestBody FacilityBody body) {
        return service.create(kind, body);
    }

    @PutMapping("/" + KIND + "/{id}")
    public FacilityView update(@PathVariable("kind") String kind, @PathVariable("id") Long id,
                               @Valid @RequestBody FacilityBody body) {
        return service.update(kind, id, body);
    }

    @DeleteMapping("/" + KIND + "/{id}")
    public ResponseEntity<Void> delete(@PathVariable("kind") String kind, @PathVariable("id") Long id) {
        service.delete(kind, id);
        return ResponseEntity.noContent().build();
    }
}
