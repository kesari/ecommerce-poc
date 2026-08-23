package com.poc.account.domain.exception;

import java.util.UUID;

public class AddressNotFoundException extends RuntimeException {

    public AddressNotFoundException(UUID addressId) {
        super("address not found: " + addressId);
    }
}
