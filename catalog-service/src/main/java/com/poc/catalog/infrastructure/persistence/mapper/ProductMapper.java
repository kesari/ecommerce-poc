package com.poc.catalog.infrastructure.persistence.mapper;

import com.poc.catalog.infrastructure.persistence.row.ProductRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ProductMapper {

    Optional<ProductRow> findById(@Param("id") UUID id);

    List<ProductRow> findActivePage(@Param("limit") int limit, @Param("offset") int offset);

    List<ProductRow> findAllByIds(@Param("ids") List<UUID> ids);

    int updatePrice(@Param("id") UUID id, @Param("priceMinor") long priceMinor);
}
