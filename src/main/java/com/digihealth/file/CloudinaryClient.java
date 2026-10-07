package com.digihealth.file;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.digihealth.common.ApiException;
import com.digihealth.config.AppProperties;

/*
 * Talks to Cloudinary's REST API directly (no SDK needed): signed uploads and
 * short-lived download links for private files.
 * Signature = SHA-1 of the sorted parameters "a=1&b=2" followed by the API secret.
 */
@Component
public class CloudinaryClient {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryClient.class);
    private static final String API = "https://api.cloudinary.com/v1_1/";

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final RestClient http = RestClient.create();

    public CloudinaryClient(AppProperties props) {
        String url = props.cloudinary() == null ? null : props.cloudinary().url();
        String cloud = null;
        String key = null;
        String secret = null;
        if (url != null && !url.isBlank()) {
            try {
                URI uri = URI.create(url.trim());
                String[] userInfo = uri.getRawUserInfo().split(":", 2);
                cloud = uri.getHost();
                key = URLDecoder.decode(userInfo[0], StandardCharsets.UTF_8);
                secret = URLDecoder.decode(userInfo[1], StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                log.error("CLOUDINARY_URL is not in the form cloudinary://<api_key>:<api_secret>@<cloud_name>");
            }
        }
        this.cloudName = cloud;
        this.apiKey = key;
        this.apiSecret = secret;
        if (cloudName == null) {
            log.warn("CLOUDINARY_URL is not set - file uploads are disabled.");
        }
    }

    public record Uploaded(String publicId, String secureUrl, String resourceType, String type, long bytes) {
    }

    /**
     * @param resourceType "image" or "raw"
     * @param type         "upload" (public URL) or "private" (signed links only)
     */
    public Uploaded upload(byte[] content, String filename, String publicId, String resourceType, String type) {
        requireConfigured();
        Map<String, String> params = new TreeMap<>();
        params.put("public_id", publicId);
        params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        params.put("type", type);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        form.add("api_key", apiKey);
        form.add("signature", sign(params));
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        try {
            Map<?, ?> res = http.post()
                .uri(API + cloudName + "/" + resourceType + "/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Map.class);
            if (res == null || res.get("public_id") == null) {
                throw new RestClientException("empty response");
            }
            return new Uploaded(
                String.valueOf(res.get("public_id")),
                res.get("secure_url") == null ? null : String.valueOf(res.get("secure_url")),
                String.valueOf(res.get("resource_type")),
                String.valueOf(res.get("type")),
                res.get("bytes") instanceof Number n ? n.longValue() : content.length);
        } catch (RestClientException e) {
            log.error("Cloudinary upload failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The file couldn't be uploaded. Please try again.");
        }
    }

    /** A link to a private file that stops working after {@code validSeconds}. */
    public String privateDownloadUrl(String publicId, String resourceType, long validSeconds) {
        requireConfigured();
        long now = Instant.now().getEpochSecond();
        Map<String, String> params = new TreeMap<>();
        params.put("expires_at", String.valueOf(now + validSeconds));
        params.put("public_id", publicId);
        params.put("timestamp", String.valueOf(now));
        params.put("type", "private");

        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(API + cloudName + "/" + resourceType + "/download");
        params.forEach((k, v) -> url.queryParam(k, v));
        url.queryParam("api_key", apiKey);
        url.queryParam("signature", sign(params));
        return url.encode().build().toUriString();
    }

    public boolean isConfigured() {
        return cloudName != null;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "File uploads aren't set up yet.");
        }
    }

    private String sign(Map<String, String> sortedParams) {
        String toSign = sortedParams.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("&")) + apiSecret;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(sha1.digest(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
