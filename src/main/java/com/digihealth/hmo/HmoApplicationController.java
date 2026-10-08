package com.digihealth.hmo;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.common.RateLimiter;
import com.digihealth.hmo.HmoApplicationService.HmoApplication;
import com.digihealth.hmo.HmoApplicationService.Submitted;

/** POST /hmo/apply - public HMO form. */
@RestController
public class HmoApplicationController {

    private final HmoApplicationService service;
    private final RateLimiter rateLimiter;

    public HmoApplicationController(HmoApplicationService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/hmo/apply")
    @ResponseStatus(HttpStatus.CREATED)
    public Submitted apply(@Valid @RequestBody HmoApplication body, HttpServletRequest request) {
        rateLimiter.check("hmo-apply:" + request.getRemoteAddr(), 3, Duration.ofHours(1));
        return service.apply(body, "PUBLIC");
    }
}
