package com.poc.account.application.port;

import com.poc.account.domain.model.Address;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AddressRepository {

    Address save(Address address);

    Optional<Address> findByIdAndUserId(UUID addressId, UUID userId);

    List<Address> findAllByUserId(UUID userId);

    boolean update(Address address);

    boolean deleteByIdAndUserId(UUID addressId, UUID userId);
}
