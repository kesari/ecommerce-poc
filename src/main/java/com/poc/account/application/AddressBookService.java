package com.poc.account.application;

import com.poc.account.application.port.AddressRepository;
import com.poc.account.domain.exception.AddressNotFoundException;
import com.poc.account.domain.model.Address;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AddressBookService {

    private final AddressRepository addresses;

    public AddressBookService(AddressRepository addresses) {
        this.addresses = addresses;
    }

    @Transactional(readOnly = true)
    public List<Address> list(UUID userId) {
        return addresses.findAllByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Address get(UUID userId, UUID addressId) {
        return requireOwned(userId, addressId);
    }

    @Transactional
    public Address create(UUID userId, String fullName, String line1, String line2, String city,
                          String state, String postalCode, String country, String phoneNumber) {
        Instant now = Instant.now();
        Address address = new Address(UUID.randomUUID(), userId, fullName, line1, line2,
                city, state, postalCode, country, phoneNumber, now, now);
        return addresses.save(address);
    }

    @Transactional
    public Address update(UUID userId, UUID addressId, String fullName, String line1, String line2,
                          String city, String state, String postalCode, String country, String phoneNumber) {
        Address existing = requireOwned(userId, addressId);
        Address updated = existing.withDetails(fullName, line1, line2, city, state,
                postalCode, country, phoneNumber);
        if (!addresses.update(updated)) {
            throw new AddressNotFoundException(addressId);
        }
        return updated;
    }

    @Transactional
    public void delete(UUID userId, UUID addressId) {
        if (!addresses.deleteByIdAndUserId(addressId, userId)) {
            throw new AddressNotFoundException(addressId);
        }
    }

    private Address requireOwned(UUID userId, UUID addressId) {
        return addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException(addressId));
    }
}
