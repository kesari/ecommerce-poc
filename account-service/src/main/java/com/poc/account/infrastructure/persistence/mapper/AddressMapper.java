package com.poc.account.infrastructure.persistence.mapper;

import com.poc.account.infrastructure.persistence.row.AddressRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface AddressMapper {

    void insert(AddressRow row);

    Optional<AddressRow> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    List<AddressRow> findAllByUserId(@Param("userId") UUID userId);

    int update(AddressRow row);

    int deleteByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);
}
