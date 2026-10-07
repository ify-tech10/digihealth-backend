package com.digihealth.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.digihealth.config.AppProperties;
import com.digihealth.config.JwtConfig;
import com.digihealth.user.User;

/** Issues the short-lived access token: sub = user id, role, exp. */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.ttl = props.tokens().accessTtl() == null ? Duration.ofMinutes(15) : props.tokens().accessTtl();
    }

    public String issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(JwtConfig.ISSUER)
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .subject(String.valueOf(user.getId()))
            .claim("role", user.getRole().name())
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
