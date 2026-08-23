package com.poc.basket;

import com.poc.basket.api.BasketController;
import com.poc.basket.api.GlobalExceptionHandler;
import com.poc.basket.application.BasketService;
import com.poc.basket.application.DownstreamUnavailableException;
import com.poc.basket.application.port.BasketRepository;
import com.poc.basket.application.port.CatalogPort;
import com.poc.basket.domain.model.Basket;
import com.poc.basket.domain.model.BasketItem;
import com.poc.basket.domain.model.BasketStatus;
import com.poc.basket.domain.model.Coupon;
import com.poc.basket.domain.model.SaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BasketApiMockedTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID KEYBOARD = UUID.randomUUID();
    private static final UUID MOUSE = UUID.randomUUID();
    private static final UUID RETIRED = UUID.randomUUID();
    private static final UUID UNKNOWN = UUID.randomUUID();

    private FakeBasketRepository repository;
    private FakeCatalogPort catalog;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = new FakeBasketRepository(USER_ID);
        catalog = new FakeCatalogPort();
        catalog.seed(new CatalogPort.ProductInfo(KEYBOARD, "Keyboard", 1299, "GBP", true));
        catalog.seed(new CatalogPort.ProductInfo(MOUSE, "Mouse", 500, "GBP", true));
        catalog.seed(new CatalogPort.ProductInfo(RETIRED, "Retired", 900, "GBP", false));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BasketController(new BasketService(repository, catalog)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new JwtPrincipalResolver(USER_ID))
                .build();
    }

    @Test
    void addItemSnapshotsCatalogNameAndPrice() throws Exception {
        mockMvc.perform(addItem(KEYBOARD, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Keyboard"))
                .andExpect(jsonPath("$.items[0].unitPriceMinor").value(1299))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotalMinor").value(2598))
                .andExpect(jsonPath("$.subtotalMinor").value(2598))
                .andExpect(jsonPath("$.totalMinor").value(2598))
                .andExpect(jsonPath("$.currency").value("GBP"))
                .andExpect(jsonPath("$.basketVersion").value(1));
    }

    @Test
    void addingSameProductIncrementsExistingLine() throws Exception {
        mockMvc.perform(addItem(KEYBOARD, 2)).andExpect(status().isOk());

        mockMvc.perform(addItem(KEYBOARD, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.subtotalMinor").value(6495))
                .andExpect(jsonPath("$.basketVersion").value(2));
    }

    @Test
    void inactiveProductIsRejected() throws Exception {
        mockMvc.perform(addItem(RETIRED, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        mockMvc.perform(addItem(UNKNOWN, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void secondCouponIsRejected() throws Exception {
        repository.seedCoupon(new Coupon("SAVE10", 10, true));
        repository.seedCoupon(new Coupon("WELCOME15", 15, true));
        mockMvc.perform(coupon("SAVE10")).andExpect(status().isOk());

        mockMvc.perform(coupon("WELCOME15"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_APPLIED"));
    }

    @Test
    void unknownOrInactiveCouponIsRejected() throws Exception {
        repository.seedCoupon(new Coupon("EXPIRED", 20, false));

        mockMvc.perform(coupon("NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"));

        mockMvc.perform(coupon("EXPIRED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"));
    }

    @Test
    void singleVersionConflictIsRetriedAndSucceeds() throws Exception {
        repository.forceConflicts(1);

        mockMvc.perform(addItem(KEYBOARD, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(1));

        assertEquals(2, repository.saveAttempts());
    }

    @Test
    void secondVersionConflictReturnsConflict() throws Exception {
        repository.forceConflicts(2);

        mockMvc.perform(addItem(KEYBOARD, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BASKET_VERSION_CONFLICT"));

        assertEquals(2, repository.saveAttempts());
    }

    @Test
    void catalogOutageMapsToServiceUnavailable() throws Exception {
        catalog.down(true);

        mockMvc.perform(addItem(KEYBOARD, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_SERVICE_UNAVAILABLE"));
    }

    @Test
    void multiLineBasketPricesWithoutCoupon() throws Exception {
        mockMvc.perform(addItem(KEYBOARD, 2)).andExpect(status().isOk());
        mockMvc.perform(addItem(MOUSE, 3)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/basket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalMinor").value(4098))
                .andExpect(jsonPath("$.discountMinor").value(0))
                .andExpect(jsonPath("$.totalMinor").value(4098));
    }

    @Test
    void multiLineBasketPricesWithCouponAndRounding() throws Exception {
        repository.seedCoupon(new Coupon("SAVE10", 10, true));
        mockMvc.perform(addItem(KEYBOARD, 2)).andExpect(status().isOk());
        mockMvc.perform(addItem(MOUSE, 3)).andExpect(status().isOk());

        mockMvc.perform(coupon("SAVE10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponCode").value("SAVE10"))
                .andExpect(jsonPath("$.subtotalMinor").value(4098))
                .andExpect(jsonPath("$.discountMinor").value(410))
                .andExpect(jsonPath("$.totalMinor").value(3688));

        mockMvc.perform(delete("/api/v1/basket/coupon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountMinor").value(0))
                .andExpect(jsonPath("$.totalMinor").value(4098));
    }

    @Test
    void quantityUpdateAndRemovalRepriceTheBasket() throws Exception {
        mockMvc.perform(addItem(KEYBOARD, 2)).andExpect(status().isOk());
        mockMvc.perform(addItem(MOUSE, 3)).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/basket/items/" + MOUSE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalMinor").value(3098));

        mockMvc.perform(delete("/api/v1/basket/items/" + KEYBOARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.subtotalMinor").value(500));
    }

    @Test
    void invalidQuantityFailsValidation() throws Exception {
        mockMvc.perform(addItem(KEYBOARD, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder addItem(
            UUID productId, int quantity) {
        return post("/api/v1/basket/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder coupon(String code) {
        return put("/api/v1/basket/coupon")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}");
    }

    static final class FakeBasketRepository implements BasketRepository {

        private final Map<String, Coupon> coupons = new HashMap<>();
        private Basket basket;
        private int forcedConflicts;
        private int saveAttempts;

        FakeBasketRepository(UUID userId) {
            this.basket = new Basket(UUID.randomUUID(), userId, null, 0, BasketStatus.ACTIVE,
                    List.of(), Instant.EPOCH, Instant.EPOCH);
        }

        void seedCoupon(Coupon coupon) {
            coupons.put(coupon.code(), coupon);
        }

        void forceConflicts(int count) {
            this.forcedConflicts = count;
        }

        int saveAttempts() {
            return saveAttempts;
        }

        @Override
        public Basket getOrCreateForUser(UUID userId) {
            return basket;
        }

        @Override
        public Optional<Basket> findActiveByUserId(UUID userId) {
            return Optional.of(basket);
        }

        @Override
        public SaveResult saveWithVersionGuard(UUID userId, long expectedVersion,
                                               String couponCode, List<BasketItem> items) {
            saveAttempts++;
            if (forcedConflicts > 0) {
                forcedConflicts--;
                return new SaveResult.VersionConflict(basket.basketVersion());
            }
            if (expectedVersion != basket.basketVersion()) {
                return new SaveResult.VersionConflict(basket.basketVersion());
            }
            basket = basket.withChanges(couponCode, new ArrayList<>(items));
            return new SaveResult.Saved(basket);
        }

        @Override
        public Optional<Coupon> findCoupon(String code) {
            return Optional.ofNullable(coupons.get(code));
        }

        @Override
        public void markCheckedOut(UUID userId) {
        }
    }

    static final class FakeCatalogPort implements CatalogPort {

        private final Map<UUID, ProductInfo> products = new HashMap<>();
        private boolean down;

        void seed(ProductInfo product) {
            products.put(product.id(), product);
        }

        void down(boolean value) {
            this.down = value;
        }

        @Override
        public ProductInfo lookup(UUID productId) {
            if (down) {
                throw new DownstreamUnavailableException("catalog circuit open");
            }
            return products.get(productId);
        }
    }

    static final class JwtPrincipalResolver implements HandlerMethodArgumentResolver {

        private final UUID userId;

        JwtPrincipalResolver(UUID userId) {
            this.userId = userId;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return Jwt.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                      NativeWebRequest request, WebDataBinderFactory factory) {
            return Jwt.withTokenValue("test-token")
                    .header("alg", "HS256")
                    .subject(userId.toString())
                    .build();
        }
    }
}
