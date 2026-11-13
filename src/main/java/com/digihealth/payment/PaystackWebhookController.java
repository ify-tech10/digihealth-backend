package com.digihealth.payment;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.finance.BillingService;

/*
 * Paystack calls this when a payment completes. Public (no login), so every call is
 * checked against the x-paystack-signature header before anything is trusted, and the
 * payment is still re-verified with Paystack before it counts.
 * Set in Paystack dashboard: https://<backend>/api/payments/paystack/webhook
 */
@RestController
public class PaystackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookController.class);

    private final PaystackClient paystack;
    private final BillingService billing;

    public PaystackWebhookController(PaystackClient paystack, BillingService billing) {
        this.paystack = paystack;
        this.billing = billing;
    }

    @PostMapping("/payments/paystack/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String raw,
                                        @RequestHeader(name = "x-paystack-signature", required = false) String signature) {
        if (!paystack.validSignature(raw, signature)) {
            log.warn("Rejected a Paystack webhook with a bad signature");
            return ResponseEntity.status(401).build();
        }
        try {
            Map<String, Object> event = paystack.parse(raw);
            billing.handleWebhook(event);
        } catch (RuntimeException e) {
            /* 200 anyway: Paystack retries on errors, and the patient's page re-checks pending payments */
            log.warn("Paystack webhook not processed: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
