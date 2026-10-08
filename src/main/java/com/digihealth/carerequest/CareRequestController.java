package com.digihealth.carerequest;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.carerequest.CareRequestDtos.CreateCareRequest;
import com.digihealth.carerequest.CareRequestDtos.CreatedCareRequest;
import com.digihealth.common.CurrentUser;
import com.digihealth.common.RateLimiter;

/** POST /care-requests - public Book Care page (no login), also used by Customer Care. */
@RestController
public class CareRequestController {

    private final CareRequestService service;
    private final RateLimiter rateLimiter;

    public CareRequestController(CareRequestService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/care-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedCareRequest create(@Valid @RequestBody CreateCareRequest body, HttpServletRequest request) {
        if (CurrentUser.idOrNull() == null) {
            rateLimiter.check("care-request:" + request.getRemoteAddr(), 5, Duration.ofMinutes(15));
        }
        return service.create(body);
    }
}
