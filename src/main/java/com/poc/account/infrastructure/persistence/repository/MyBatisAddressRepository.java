package com.poc.account.infrastructure.persistence.repository;

import com.poc.account.application.port.AddressRepository;
import com.poc.account.domain.model.Address;
import com.poc.account.infrastructure.persistence.mapper.AddressMapper;
import com.poc.account.infrastructure.persistence.row.AddressRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisAddressRepository implements AddressRepository {

    private final AddressMapper mapper;

    public MyBatisAddressRepository(AddressMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Address save(Address address) {
        mapper.insert(toRow(address));
        return address;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Address> findByIdAndUserId(UUID addressId, UUID userId) {
        return mapper.findByIdAndUserId(addressId, userId).map(MyBatisAddressRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Address> findAllByUserId(UUID userId) {
        return mapper.findAllByUserId(userId).stream().map(MyBatisAddressRepository::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean update(Address address) {
        return mapper.update(toRow(address)) == 1;
    }

    @Override
    @Transactional
    public boolean deleteByIdAndUserId(UUID addressId, UUID userId) {
        return mapper.deleteByIdAndUserId(addressId, userId) == 1;
    }

    private static AddressRow toRow(Address address) {
        return new AddressRow(address.id(), address.userId(), address.fullName(), address.line1(),
                address.line2(), address.city(), address.state(), address.postalCode(),
                address.country(), address.phoneNumber(), address.createdAt(), address.updatedAt());
    }

    private static Address toDomain(AddressRow row) {
        return new Address(row.id(), row.userId(), row.fullName(), row.line1(), row.line2(),
                row.city(), row.state(), row.postalCode(), row.country(), row.phoneNumber(),
                row.createdAt(), row.updatedAt());
    }
}
