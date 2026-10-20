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

import com.digihealth.activity.ActivityService.ActivityView;
import com.digihealth.common.ReasonBody;

/*
 * Admin portal, part A: users, provider applications / personnel, patients,
 * HMO applications and the activity log. Only ADMIN / SUPER_ADMIN reach
 * /admin/** (SecurityConfig).
 */
@RestController
@RequestMapping("/admin")
public class AdminPeopleController {

    private final AdminUserService userService;
    private final AdminProviderService providerService;
    private final AdminHmoService hmoService;
    private final AdminActivityService activityService;

    public AdminPeopleController(AdminUserService userService, AdminProviderService providerService,
                                 AdminHmoService hmoService, AdminActivityService activityService) {
        this.userService = userService;
        this.providerService = providerService;
        this.hmoService = hmoService;
        this.activityService = activityService;
    }

    /* ── Users ── */

    @GetMapping("/users")
    public List<AdminUserService.UserView> users(@RequestParam(name = "role", required = false) String role) {
        return userService.list(role);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserService.UserView createUser(@Valid @RequestBody AdminUserService.CreateUser body) {
        return userService.create(body);
    }

    @PutMapping("/users/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable("id") Long id) {
        userService.setActive(id, true);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable("id") Long id) {
        userService.setActive(id, false);
        return ResponseEntity.noContent().build();
    }

    /* ── Patients ── */

    @GetMapping("/patients")
    public List<AdminUserService.PatientView> patients() {
        return userService.patients();
    }

    /* ── Provider applications (PENDING) and personnel (APPROVED) ── */

    @GetMapping("/providers")
    public List<AdminProviderService.ProviderView> providers(@RequestParam(name = "status", required = false) String status) {
        return providerService.list(status);
    }

    @PutMapping("/providers/{id}/approve")
    public ResponseEntity<Void> approveProvider(@PathVariable("id") Long id) {
        providerService.approve(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/providers/{id}/reject")
    public ResponseEntity<Void> rejectProvider(@PathVariable("id") Long id,
                                               @Valid @RequestBody(required = false) ReasonBody.Reason body) {
        providerService.reject(id, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    /* ── HMO company applications ── */

    @GetMapping("/hmo")
    public List<AdminHmoService.HmoView> hmoApplications(@RequestParam(name = "status", required = false) String status) {
        return hmoService.list(status);
    }

    @PutMapping("/hmo/{id}/approve")
    public ResponseEntity<Void> approveHmo(@PathVariable("id") Long id) {
        hmoService.approve(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/hmo/{id}/reject")
    public ResponseEntity<Void> rejectHmo(@PathVariable("id") Long id,
                                          @Valid @RequestBody(required = false) ReasonBody.Reason body) {
        hmoService.reject(id, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    /* ── Activity log ── */

    @GetMapping("/activity")
    public List<ActivityView> activity(@RequestParam(name = "role", required = false) String role,
                                       @RequestParam(name = "from", required = false) String from,
                                       @RequestParam(name = "to", required = false) String to) {
        return activityService.search(role, from, to);
    }
}
