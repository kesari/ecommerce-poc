package com.poc.account.infrastructure.persistence.mapper;

import com.poc.account.infrastructure.persistence.row.UserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface UserMapper {

    void insert(UserRow row);

    boolean existsByEmail(@Param("email") String email);

    Optional<UserRow> findByEmail(@Param("email") String email);

    Optional<UserRow> findById(@Param("id") java.util.UUID id);
}
