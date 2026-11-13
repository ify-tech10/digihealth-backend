package com.digihealth.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.digihealth.common.ApiException;
import com.digihealth.config.AppProperties;

import tools.jackson.databind.ObjectMapper;

/*
 * The two Paystack calls we need, plus webhook signature checking:
 *   initialize(...) -> the hosted payment page the patient is sent to
 *   verify(ref)     -> did that payment succeed, and for how much
 * Amounts go to Paystack in kobo. The secret key never leaves the backend.
 */
@Component
public class PaystackClient {

    private static final Logger log = LoggerFactory.getLogger(PaystackClient.class);
    private static final String API = "https://api.paystack.co";

    private final String secretKey;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public PaystackClient(AppProperties props, ObjectMapper json) {
        this.secretKey = props.paystack() == null || props.paystack().secretKey() == null
            ? "" : props.paystack().secretKey().trim();
        this.json = json;
    }

    public boolean isConfigured() {
        return !secretKey.isEmpty();
    }

    /** Result of a verify call: status is "success", "failed", "abandoned", etc. */
    public record Verification(String status, BigDecimal amount, String channel, String paidAt) {
        public boolean succeeded() {
            return "success".equalsIgnoreCase(status);
        }
    }

    /** Starts a payment and returns Paystack's hosted checkout URL. */
    public String initialize(String email, BigDecimal naira, String reference, String callbackUrl,
                             Map<String, Object> metadata) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("amount", naira.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact());
        body.put("currency", "NGN");
        body.put("reference", reference);
        body.put("callback_url", callbackUrl);
        body.put("metadata", metadata);
        Map<String, Object> res = call(HttpRequest.newBuilder(URI.create(API + "/transaction/initialize"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
        Object url = data(res).get("authorization_url");
        if (url == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The payment page couldn't be opened. Please try again.");
        }
        return url.toString();
    }

    public Verification verify(String reference) {
        requireConfigured();
        Map<String, Object> res = call(HttpRequest.newBuilder(URI.create(API + "/transaction/verify/"
            + URLEncoder.encode(reference, StandardCharsets.UTF_8))).GET());
        Map<String, Object> d = data(res);
        Object kobo = d.get("amount");
        BigDecimal amount = kobo == null ? BigDecimal.ZERO
            : new BigDecimal(kobo.toString()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Verification(str(d.get("status")), amount, str(d.get("channel")), str(d.get("paid_at")));
    }

    /** Paystack signs each webhook body with HMAC-SHA512 of our secret key. */
    public boolean validSignature(String rawBody, String signature) {
        if (!isConfigured() || signature == null || rawBody == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String body) {
        return json.readValue(body, Map.class);
    }

    /* ── helpers ── */

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Online payment isn't available yet. Please pay by bank transfer and send the receipt through Contact Support.");
        }
    }

    private Map<String, Object> call(HttpRequest.Builder req) {
        HttpResponse<String> res;
        try {
            res = http.send(req.header("Authorization", "Bearer " + secretKey)
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            log.warn("Paystack unreachable: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The payment service didn't respond. Please try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The payment service didn't respond. Please try again.");
        }
        Map<String, Object> body;
        try {
            body = parse(res.body());
        } catch (RuntimeException e) {
            body = Map.of();
        }
        if (res.statusCode() >= 400 || !Boolean.TRUE.equals(body.get("status"))) {
            log.warn("Paystack error {}: {}", res.statusCode(), body.get("message"));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The payment service refused the request. Please try again.");
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> res) {
        Object d = res.get("data");
        return d instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
