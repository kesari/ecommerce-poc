package com.poc.account.infrastructure.mapping;

import com.poc.account.api.dto.AddressResponse;
import com.poc.account.domain.model.Address;
import org.springframework.stereotype.Component;

@Component
public class AddressDtoMapper {

    public AddressResponse toResponse(Address address) {
        if (address == null) {
            return null;
        }
        return new AddressResponse(address.id(), address.fullName(), address.line1(),
                address.line2(), address.city(), address.state(), address.postalCode(),
                address.country(), address.phoneNumber(), address.createdAt(), address.updatedAt());
    }
}
