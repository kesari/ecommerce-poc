package com.poc.basket.infrastructure.persistence.repository;

import com.poc.basket.application.port.BasketRepository;
import com.poc.basket.domain.model.Basket;
import com.poc.basket.domain.model.BasketItem;
import com.poc.basket.domain.model.BasketStatus;
import com.poc.basket.domain.model.Coupon;
import com.poc.basket.domain.model.SaveResult;
import com.poc.basket.infrastructure.persistence.mapper.BasketMapper;
import com.poc.basket.infrastructure.persistence.row.BasketItemRow;
import com.poc.basket.infrastructure.persistence.row.BasketRow;
import com.poc.basket.infrastructure.persistence.row.CouponRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisBasketRepository implements BasketRepository {

    private final BasketMapper mapper;

    public MyBatisBasketRepository(BasketMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Basket getOrCreateForUser(UUID userId) {
        mapper.insertBasketIfAbsent(UUID.randomUUID(), userId);
        return mapper.findByUserId(userId)
                .map(this::toDomain)
                .orElseThrow(() -> new IllegalStateException("Basket was not created"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Basket> findActiveByUserId(UUID userId) {
        return mapper.findActiveByUserId(userId).map(this::toDomain);
    }

    @Override
    @Transactional
    public SaveResult saveWithVersionGuard(UUID userId, long expectedVersion,
                                           String couponCode, List<BasketItem> items) {
        int updated = mapper.updateHeaderWithVersion(userId, expectedVersion, couponCode);
        if (updated == 0) {
            long currentVersion = mapper.findVersionByUserId(userId).orElse(-1L);
            return new SaveResult.VersionConflict(currentVersion);
        }

        BasketRow basket = mapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Updated basket was not found"));
        mapper.deleteItems(basket.id());
        items.stream()
                .map(item -> toRow(basket.id(), item))
                .forEach(mapper::insertItem);

        return new SaveResult.Saved(toDomain(basket));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Coupon> findCoupon(String code) {
        return mapper.findCoupon(code).map(MyBatisBasketRepository::toDomain);
    }

    @Override
    @Transactional
    public void markCheckedOut(UUID userId) {
        mapper.markCheckedOut(userId);
    }

    private Basket toDomain(BasketRow row) {
        List<BasketItem> items = mapper.findItemsByBasketId(row.id()).stream()
                .map(MyBatisBasketRepository::toDomain)
                .toList();
        return new Basket(row.id(), row.userId(), row.couponCode(), row.basketVersion(),
                BasketStatus.valueOf(row.status()), items, row.createdAt(), row.updatedAt());
    }

    private static BasketItemRow toRow(UUID basketId, BasketItem item) {
        return new BasketItemRow(basketId, item.productId(), item.name(), item.unitPriceMinor(),
                item.currency(), item.quantity());
    }

    private static BasketItem toDomain(BasketItemRow row) {
        return new BasketItem(row.productId(), row.name(), row.unitPriceMinor(),
                row.currency(), row.quantity());
    }

    private static Coupon toDomain(CouponRow row) {
        return new Coupon(row.code(), row.discountPercent(), row.active());
    }
}
