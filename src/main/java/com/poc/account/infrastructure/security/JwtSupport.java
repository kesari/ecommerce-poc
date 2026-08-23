package com.poc.account.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtSupport {

    private final SecurityProperties properties;
    private final SecretKey key;

    public JwtSupport(SecurityProperties properties) {
        this.properties = properties;
        this.key = new SecretKeySpec(properties.jwtSecret().getBytes(), "HmacSHA256");
    }

    public String issueAccessToken(UUID userId, String email) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTtl());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer(properties.issuer())
                .claim("email", email)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();
        SignedJWT signed = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        try {
            signed.sign(new MACSigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign access token", e);
        }
        return signed.serialize();
    }

    public SecretKey key() {
        return key;
    }

    public String issuer() {
        return properties.issuer();
    }
}
