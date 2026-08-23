package com.poc.account.domain.model;

import java.util.UUID;

public record UserCredentials(UUID id, String email, String passwordHash) {
}
