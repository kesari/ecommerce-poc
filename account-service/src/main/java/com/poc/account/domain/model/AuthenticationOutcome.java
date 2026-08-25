package com.poc.account.domain.model;

import java.time.Instant;

public sealed interface AuthenticationOutcome {

    record Authenticated(TokenPair tokens, Instant issuedAt) implements AuthenticationOutcome {
    }

    record Invalid() implements AuthenticationOutcome {
    }
}
