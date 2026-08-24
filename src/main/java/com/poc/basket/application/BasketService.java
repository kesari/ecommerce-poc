package com.poc.basket.application;

import com.poc.basket.application.port.BasketRepository;
import com.poc.basket.application.port.InboxRepository;
import com.poc.basket.application.port.CatalogPort;
import com.poc.basket.domain.exception.BasketVersionConflictException;
import com.poc.basket.domain.exception.CouponAlreadyAppliedException;
import com.poc.basket.domain.exception.CouponInvalidException;
import com.poc.basket.domain.exception.ProductInactiveException;
import com.poc.basket.domain.exception.ProductNotFoundException;
import com.poc.basket.domain.model.Basket;
import com.poc.basket.domain.model.BasketItem;
import com.poc.basket.domain.model.Coupon;
import com.poc.basket.domain.model.SaveResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Service
public class BasketService {

    private static final int MAX_ATTEMPTS = 2;

    private final BasketRepository repository;
    private final CatalogPort catalog;
    private final InboxRepository inbox;

    public BasketService(BasketRepository repository, CatalogPort catalog,
                         InboxRepository inbox) {
        this.repository = repository;
        this.catalog = catalog;
        this.inbox = inbox;
    }

    @Transactional
    public void completeCheckout(UUID eventId, UUID userId) {
        if (!inbox.claim(eventId)) {
            return;
        }
        repository.markCheckedOut(userId);
    }

    public record BasketView(Basket basket, BasketPricing.Breakdown breakdown) {}

    private record Change(String couponCode, List<BasketItem> items) {}

    public BasketView current(UUID userId) {
        return view(repository.getOrCreateForUser(userId));
    }

    public BasketView addItem(UUID userId, UUID productId, int quantity) {
        CatalogPort.ProductInfo product = purchasable(productId);
        return mutate(userId, basket ->
                new Change(basket.couponCode(), addOrIncrement(basket.items(), product, quantity)));
    }

    public BasketView setQuantity(UUID userId, UUID productId, int quantity) {
        return mutate(userId, basket ->
                new Change(basket.couponCode(), replaceQuantity(basket.items(), productId, quantity)));
    }

    public BasketView removeItem(UUID userId, UUID productId) {
        return mutate(userId, basket -> new Change(basket.couponCode(),
                basket.items().stream().filter(item -> !item.productId().equals(productId)).toList()));
    }

    public BasketView applyCoupon(UUID userId, String code) {
        return mutate(userId, basket -> {
            if (basket.couponCode() != null) {
                throw new CouponAlreadyAppliedException("coupon " + basket.couponCode() + " already applied");
            }
            Coupon coupon = repository.findCoupon(code)
                    .filter(Coupon::active)
                    .orElseThrow(() -> new CouponInvalidException("coupon " + code + " is unknown or inactive"));
            return new Change(coupon.code(), basket.items());
        });
    }

    public BasketView removeCoupon(UUID userId) {
        return mutate(userId, basket -> new Change(null, basket.items()));
    }

    private BasketView mutate(UUID userId, Function<Basket, Change> mutation) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Basket current = repository.getOrCreateForUser(userId);
            Change change = mutation.apply(current);
            SaveResult result = repository.saveWithVersionGuard(userId, current.basketVersion(),
                    change.couponCode(), change.items());
            if (result instanceof SaveResult.Saved saved) {
                return view(saved.basket());
            }
        }
        throw new BasketVersionConflictException("basket was modified concurrently");
    }

    private BasketView view(Basket basket) {
        Coupon coupon = basket.couponCode() == null
                ? null
                : repository.findCoupon(basket.couponCode()).orElse(null);
        return new BasketView(basket, BasketPricing.price(basket.items(), coupon));
    }

    private CatalogPort.ProductInfo purchasable(UUID productId) {
        CatalogPort.ProductInfo product = catalog.lookup(productId);
        if (product == null) {
            throw new ProductNotFoundException("product " + productId + " does not exist");
        }
        if (!product.active()) {
            throw new ProductInactiveException("product " + productId + " is not purchasable");
        }
        return product;
    }

    private static List<BasketItem> addOrIncrement(List<BasketItem> items,
                                                   CatalogPort.ProductInfo product, int quantity) {
        List<BasketItem> updated = new ArrayList<>(items);
        for (int i = 0; i < updated.size(); i++) {
            BasketItem existing = updated.get(i);
            if (existing.productId().equals(product.id())) {
                updated.set(i, new BasketItem(existing.productId(), existing.name(),
                        existing.unitPriceMinor(), existing.currency(),
                        existing.quantity() + quantity));
                return List.copyOf(updated);
            }
        }
        updated.add(new BasketItem(product.id(), product.name(), product.priceMinor(),
                product.currency(), quantity));
        return List.copyOf(updated);
    }

    private static List<BasketItem> replaceQuantity(List<BasketItem> items, UUID productId, int quantity) {
        List<BasketItem> updated = new ArrayList<>(items);
        for (int i = 0; i < updated.size(); i++) {
            BasketItem existing = updated.get(i);
            if (existing.productId().equals(productId)) {
                updated.set(i, new BasketItem(existing.productId(), existing.name(),
                        existing.unitPriceMinor(), existing.currency(), quantity));
                return List.copyOf(updated);
            }
        }
        throw new ProductNotFoundException("product " + productId + " is not in the basket");
    }
}
