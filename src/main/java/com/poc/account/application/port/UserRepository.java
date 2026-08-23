package com.poc.account.application.port;

import com.poc.account.domain.model.UserAccount;
import com.poc.account.domain.model.UserCredentials;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    void save(UserAccount account, String passwordHash);

    boolean existsByEmail(String normalizedEmail);

    Optional<UserCredentials> findCredentialsByEmail(String normalizedEmail);

    Optional<UserCredentials> findCredentialsById(UUID userId);
}
