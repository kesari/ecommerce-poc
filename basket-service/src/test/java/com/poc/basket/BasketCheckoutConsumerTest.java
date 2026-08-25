package com.poc.basket;

import com.poc.basket.application.BasketService;
import com.poc.basket.application.port.BasketRepository;
import com.poc.basket.application.port.CatalogPort;
import com.poc.basket.application.port.InboxRepository;
import com.poc.basket.domain.model.Basket;
import com.poc.basket.domain.model.BasketItem;
import com.poc.basket.domain.model.BasketStatus;
import com.poc.basket.domain.model.Coupon;
import com.poc.basket.domain.model.SaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BasketCheckoutConsumerTest {

    private static final UUID USER = UUID.randomUUID();

    private RecordingBasketRepository repository;
    private FakeInbox inbox;
    private BasketService baskets;

    @BeforeEach
    void setUp() {
        repository = new RecordingBasketRepository();
        inbox = new FakeInbox();
        baskets = new BasketService(repository, unusedCatalog(), inbox);
    }

    @Test
    void confirmedOrderCompletesTheBasket() {
        baskets.completeCheckout(UUID.randomUUID(), USER);

        assertThat(repository.checkedOut).containsExactly(USER);
    }

    @Test
    void replayedEventIdIsIgnored() {
        UUID eventId = UUID.randomUUID();

        baskets.completeCheckout(eventId, USER);
        baskets.completeCheckout(eventId, USER);
        baskets.completeCheckout(eventId, USER);

        assertThat(repository.checkedOut).containsExactly(USER);
    }

    @Test
    void distinctEventsForDifferentUsersEachComplete() {
        UUID other = UUID.randomUUID();

        baskets.completeCheckout(UUID.randomUUID(), USER);
        baskets.completeCheckout(UUID.randomUUID(), other);

        assertThat(repository.checkedOut).containsExactly(USER, other);
    }

    private static CatalogPort unusedCatalog() {
        return productId -> {
            throw new AssertionError("checkout completion must not call Catalog");
        };
    }

    static final class FakeInbox implements InboxRepository {

        private final Set<UUID> claimed = new HashSet<>();

        @Override
        public boolean claim(UUID eventId) {
            return claimed.add(eventId);
        }
    }

    static final class RecordingBasketRepository implements BasketRepository {

        final List<UUID> checkedOut = new java.util.ArrayList<>();

        @Override
        public Basket getOrCreateForUser(UUID userId) {
            return new Basket(UUID.randomUUID(), userId, null, 0, BasketStatus.ACTIVE,
                    List.of(), Instant.EPOCH, Instant.EPOCH);
        }

        @Override
        public Optional<Basket> findActiveByUserId(UUID userId) {
            return Optional.empty();
        }

        @Override
        public SaveResult saveWithVersionGuard(UUID userId, long expectedVersion,
                                               String couponCode, List<BasketItem> items) {
            throw new AssertionError("not used");
        }

        @Override
        public Optional<Coupon> findCoupon(String code) {
            return Optional.empty();
        }

        @Override
        public void markCheckedOut(UUID userId) {
            checkedOut.add(userId);
        }
    }
}
