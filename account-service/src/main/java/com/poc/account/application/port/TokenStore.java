package com.poc.account.application.port;

import com.poc.account.domain.model.RefreshTokenRecord;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TokenStore {

    void save(String tokenHash, UUID userId, Instant expiresAt);

    Optional<RefreshTokenRecord> findActiveByHash(String tokenHash);

    void revoke(String tokenHash);
}
