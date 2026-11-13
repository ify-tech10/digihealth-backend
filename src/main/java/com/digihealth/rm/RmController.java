package com.digihealth.rm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.hmo.HmoApplicationService;

/** Relationship Manager portal (7 endpoints), all under /rm. */
@RestController
@RequestMapping("/rm")
public class RmController {

    private final RmService rm;

    public RmController(RmService rm) {
        this.rm = rm;
    }

    @GetMapping("/infrastructures")
    public Map<String, List<Map<String, Object>>> infrastructures() {
        return rm.infrastructures();
    }

    @PostMapping("/{kind:hospitals|pharmacies|laboratories}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> onboard(@PathVariable("kind") String kind, @RequestBody Map<String, Object> body) {
        return rm.onboard(kind, body);
    }

    @GetMapping("/organisations")
    public List<Map<String, Object>> organisations() {
        return rm.organisations();
    }

    @PostMapping("/organisations")
    @ResponseStatus(HttpStatus.CREATED)
    public HmoApplicationService.Submitted onboardOrganisation(@Valid @RequestBody HmoApplicationService.HmoApplication body) {
        return rm.onboardOrganisation(body);
    }

    @PutMapping("/organisations/{id}/confirm-hmo")
    public ResponseEntity<Void> confirmHmo(@PathVariable("id") Long id) {
        rm.confirmHmo(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(RmService.FieldErrors.class)
    public ResponseEntity<Map<String, Object>> fieldErrors(RmService.FieldErrors ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        body.put("errors", ex.getErrors());
        return ResponseEntity.badRequest().body(body);
    }
}
