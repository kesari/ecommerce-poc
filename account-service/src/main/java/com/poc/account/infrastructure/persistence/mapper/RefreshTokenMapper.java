package com.poc.account.infrastructure.persistence.mapper;

import com.poc.account.infrastructure.persistence.row.RefreshTokenRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;
import java.util.UUID;

@Mapper
public interface RefreshTokenMapper {

    void insert(RefreshTokenRow row);

    Optional<RefreshTokenRow> findActiveByHash(@Param("tokenHash") String tokenHash);

    int revokeByHash(@Param("tokenHash") String tokenHash);
}
