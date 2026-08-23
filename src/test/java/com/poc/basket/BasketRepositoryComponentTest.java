package com.poc.basket;

import com.poc.basket.application.port.BasketRepository;
import com.poc.basket.domain.model.Basket;
import com.poc.basket.domain.model.BasketItem;
import com.poc.basket.domain.model.BasketStatus;
import com.poc.basket.domain.model.SaveResult;
import com.poc.basket.infrastructure.persistence.repository.MyBatisBasketRepository;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = BasketRepositoryComponentTest.PersistenceTestConfiguration.class)
@TestPropertySource(properties = "mybatis.mapper-locations=classpath:mybatis/mapper/*.xml")
class BasketRepositoryComponentTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.40"));
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    BasketRepository repository;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void getOrCreateIsIdempotentPerUser() {
        UUID userId = UUID.randomUUID();

        Basket first = repository.getOrCreateForUser(userId);
        Basket second = repository.getOrCreateForUser(userId);

        assertThat(second).isEqualTo(first);
        assertThat(first.userId()).isEqualTo(userId);
        assertThat(first.status()).isEqualTo(BasketStatus.ACTIVE);
        assertThat(first.basketVersion()).isZero();
        assertThat(first.items()).isEmpty();
    }

    @Test
    void concurrentSavesWithTheSameVersionLoseExactlyOnce() throws Exception {
        UUID userId = UUID.randomUUID();
        repository.getOrCreateForUser(userId);
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SaveResult> first = executor.submit(() -> {
                start.await();
                return repository.saveWithVersionGuard(userId, 0, null,
                        List.of(item("First product", 1000)));
            });
            Future<SaveResult> second = executor.submit(() -> {
                start.await();
                return repository.saveWithVersionGuard(userId, 0, null,
                        List.of(item("Second product", 2000)));
            });

            List<SaveResult> results = List.of(first.get(), second.get());
            assertThat(results).filteredOn(SaveResult.Saved.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(SaveResult.VersionConflict.class::isInstance).hasSize(1);

            SaveResult.VersionConflict conflict = results.stream()
                    .filter(SaveResult.VersionConflict.class::isInstance)
                    .map(SaveResult.VersionConflict.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(conflict.currentVersion()).isEqualTo(1);
        }
    }

    @Test
    void saveCompletelyReplacesItems() {
        UUID userId = UUID.randomUUID();
        repository.getOrCreateForUser(userId);
        BasketItem first = item("First product", 1000);
        BasketItem second = item("Second product", 2000);

        SaveResult initial = repository.saveWithVersionGuard(userId, 0, "SAVE10",
                List.of(first, second));
        assertThat(initial).isInstanceOf(SaveResult.Saved.class);

        BasketItem replacement = item("Replacement product", 3500);
        SaveResult replaced = repository.saveWithVersionGuard(userId, 1, null,
                List.of(replacement));

        assertThat(replaced).isInstanceOfSatisfying(SaveResult.Saved.class, saved -> {
            assertThat(saved.basket().basketVersion()).isEqualTo(2);
            assertThat(saved.basket().couponCode()).isNull();
            assertThat(saved.basket().items()).containsExactly(replacement);
        });
        assertThat(repository.findActiveByUserId(userId))
                .hasValueSatisfying(basket -> assertThat(basket.items()).containsExactly(replacement));
    }

    @Test
    void couponLookupReturnsInactiveCouponAndEmptyForUnknownCode() {
        assertThat(repository.findCoupon("EXPIRED20"))
                .hasValueSatisfying(coupon -> {
                    assertThat(coupon.discountPercent()).isEqualTo(20);
                    assertThat(coupon.active()).isFalse();
                });
        assertThat(repository.findCoupon("UNKNOWN")).isEmpty();
    }

    private static BasketItem item(String name, long unitPriceMinor) {
        return new BasketItem(UUID.randomUUID(), name, unitPriceMinor, "INR", 1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan("com.poc.basket.infrastructure.persistence.mapper")
    @Import(MyBatisBasketRepository.class)
    static class PersistenceTestConfiguration {
    }
}
