package com.poc.account.infrastructure.mapping;

import com.poc.account.api.dto.AddressResponse;
import com.poc.account.domain.model.Address;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AddressDtoMapper {

    AddressResponse toResponse(Address address);
}
