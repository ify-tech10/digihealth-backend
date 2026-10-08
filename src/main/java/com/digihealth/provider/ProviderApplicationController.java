package com.digihealth.provider;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.common.RateLimiter;

/** POST /auth/provider/apply - public, multipart. */
@RestController
public class ProviderApplicationController {

    private final ProviderApplicationService service;
    private final RateLimiter rateLimiter;

    public ProviderApplicationController(ProviderApplicationService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(path = "/auth/provider/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderApplicationService.Submitted apply(@Valid @ModelAttribute ProviderApplicationForm form,
                                                      HttpServletRequest request) {
        rateLimiter.check("provider-apply:" + request.getRemoteAddr(), 3, Duration.ofHours(1));
        return service.apply(form);
    }
}
