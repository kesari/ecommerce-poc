package com.poc.account.infrastructure.persistence.repository;

import com.poc.account.application.port.UserRepository;
import com.poc.account.domain.model.UserAccount;
import com.poc.account.domain.model.UserCredentials;
import com.poc.account.infrastructure.persistence.mapper.UserMapper;
import com.poc.account.infrastructure.persistence.row.UserRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class MyBatisUserRepository implements UserRepository {

    private final UserMapper mapper;

    public MyBatisUserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(UserAccount account, String passwordHash) {
        mapper.insert(new UserRow(account.id(), account.email(), passwordHash, account.createdAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByEmail(String normalizedEmail) {
        return mapper.existsByEmail(normalizedEmail);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserCredentials> findCredentialsByEmail(String normalizedEmail) {
        return mapper.findByEmail(normalizedEmail).map(this::toCredentials);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserCredentials> findCredentialsById(java.util.UUID userId) {
        return mapper.findById(userId).map(this::toCredentials);
    }

    private UserCredentials toCredentials(UserRow row) {
        return new UserCredentials(row.id(), row.email(), row.passwordHash());
    }
}
