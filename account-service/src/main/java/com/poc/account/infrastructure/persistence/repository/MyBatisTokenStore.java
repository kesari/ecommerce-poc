package com.poc.account.infrastructure.persistence.repository;

import com.poc.account.application.port.TokenStore;
import com.poc.account.domain.model.RefreshTokenRecord;
import com.poc.account.infrastructure.persistence.mapper.RefreshTokenMapper;
import com.poc.account.infrastructure.persistence.row.RefreshTokenRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisTokenStore implements TokenStore {

    private final RefreshTokenMapper mapper;

    public MyBatisTokenStore(RefreshTokenMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(String tokenHash, UUID userId, Instant expiresAt) {
        mapper.insert(new RefreshTokenRow(UUID.randomUUID(), userId, tokenHash, expiresAt));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshTokenRecord> findActiveByHash(String tokenHash) {
        return mapper.findActiveByHash(tokenHash)
                .map(row -> new RefreshTokenRecord(row.id(), row.userId(), row.expiresAt()));
    }

    @Override
    @Transactional
    public void revoke(String tokenHash) {
        mapper.revokeByHash(tokenHash);
    }
}
