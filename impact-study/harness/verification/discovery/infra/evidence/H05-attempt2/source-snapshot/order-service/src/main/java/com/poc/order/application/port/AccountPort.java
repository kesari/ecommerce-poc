package com.poc.order.application.port;

import com.poc.order.domain.model.AddressSnapshot;

import java.util.UUID;

public interface AccountPort {

    AddressSnapshot address(String bearerToken, UUID addressId);
}
