package com.poc.account.application;

import com.poc.account.application.port.TokenStore;
import com.poc.account.application.port.UserRepository;
import com.poc.account.domain.exception.EmailAlreadyRegisteredException;
import com.poc.account.domain.exception.InvalidCredentialsException;
import com.poc.account.domain.model.AuthenticationOutcome;
import com.poc.account.domain.model.RefreshTokenRecord;
import com.poc.account.domain.model.TokenPair;
import com.poc.account.domain.model.UserAccount;
import com.poc.account.domain.model.UserCredentials;
import com.poc.account.infrastructure.security.JwtSupport;
import com.poc.account.infrastructure.security.SecurityProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final TokenStore tokens;
    private final TokenService tokenService;
    private final JwtSupport jwt;
    private final SecurityProperties properties;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, TokenStore tokens, TokenService tokenService,
                       JwtSupport jwt, SecurityProperties properties, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.tokens = tokens;
        this.tokenService = tokenService;
        this.jwt = jwt;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public TokenPair signup(String email, String password) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw new EmailAlreadyRegisteredException(normalized);
        }
        UUID userId = UUID.randomUUID();
        UserAccount account = new UserAccount(userId, normalized, Instant.now());
        try {
            users.save(account, passwordEncoder.encode(password));
        } catch (DuplicateKeyException e) {
            throw new EmailAlreadyRegisteredException(normalized);
        }
        return issueTokens(userId, normalized);
    }

    @Transactional(readOnly = true)
    public TokenPair login(String email, String password) {
        String normalized = normalize(email);
        UserCredentials credentials = users.findCredentialsByEmail(normalized)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(password, credentials.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(credentials.id(), credentials.email());
    }

    @Transactional
    public AuthenticationOutcome refresh(String rawRefreshToken) {
        String hash = tokenService.hash(rawRefreshToken);
        RefreshTokenRecord record = tokens.findActiveByHash(hash).orElse(null);
        if (record == null || record.expiresAt().isBefore(Instant.now())) {
            return new AuthenticationOutcome.Invalid();
        }
        UserCredentials credentials = users.findCredentialsById(record.userId())
                .orElseThrow(InvalidCredentialsException::new);
        tokens.revoke(hash);
        return new AuthenticationOutcome.Authenticated(
                issueTokens(record.userId(), credentials.email()), Instant.now());
    }

    public long accessTtlSeconds() {
        return properties.accessTtl().toSeconds();
    }

    private TokenPair issueTokens(UUID userId, String email) {
        String access = jwt.issueAccessToken(userId, email);
        TokenService.RefreshMaterial material = tokenService.newRefreshMaterial();
        tokens.save(material.tokenHash(), userId, material.expiresAt());
        return new TokenPair(access, material.rawToken());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
