package com.poc.basket.infrastructure.persistence.mapper;

import com.poc.basket.infrastructure.persistence.row.BasketItemRow;
import com.poc.basket.infrastructure.persistence.row.BasketRow;
import com.poc.basket.infrastructure.persistence.row.CouponRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface BasketMapper {

    int insertBasketIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<BasketRow> findByUserId(@Param("userId") UUID userId);

    Optional<BasketRow> findActiveByUserId(@Param("userId") UUID userId);

    List<BasketItemRow> findItemsByBasketId(@Param("basketId") UUID basketId);

    int updateHeaderWithVersion(@Param("userId") UUID userId,
                                @Param("expectedVersion") long expectedVersion,
                                @Param("couponCode") String couponCode);

    void deleteItems(@Param("basketId") UUID basketId);

    void insertItem(BasketItemRow item);

    Optional<Long> findVersionByUserId(@Param("userId") UUID userId);

    Optional<CouponRow> findCoupon(@Param("code") String code);

    int markCheckedOut(@Param("userId") UUID userId);

    int claimInbox(@Param("eventId") UUID eventId);
}
