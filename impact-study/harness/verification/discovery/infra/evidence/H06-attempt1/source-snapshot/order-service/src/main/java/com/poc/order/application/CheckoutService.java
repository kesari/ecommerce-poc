package com.poc.order.application;

import com.poc.order.application.port.AccountPort;
import com.poc.order.application.port.BasketPort;
import com.poc.order.application.port.QuoteRepository;
import com.poc.order.application.port.ShipmentPort;
import com.poc.order.domain.model.AddressSnapshot;
import com.poc.order.domain.model.DeliveryWindow;
import com.poc.order.domain.model.OrderLine;
import com.poc.order.domain.model.PriceBreakdown;
import com.poc.order.domain.model.Quote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class CheckoutService {

    public static final Duration QUOTE_TTL = Duration.ofMinutes(10);

    private final BasketPort baskets;
    private final AccountPort accounts;
    private final ShipmentPort shipments;
    private final QuoteRepository quotes;
    private final Clock clock;

    public CheckoutService(BasketPort baskets, AccountPort accounts, ShipmentPort shipments,
                           QuoteRepository quotes, Clock clock) {
        this.baskets = baskets;
        this.accounts = accounts;
        this.shipments = shipments;
        this.quotes = quotes;
        this.clock = clock;
    }

    @Transactional
    public Quote createQuote(String bearerToken, UUID userId, UUID addressId) {
        BasketPort.BasketSnapshot basket = baskets.currentBasket(bearerToken);
        AddressSnapshot address = accounts.address(bearerToken, addressId);

        List<OrderLine> lines = basket.lines().stream()
                .map(line -> new OrderLine(line.productId(), line.name(),
                        line.unitPriceMinor(), line.quantity()))
                .toList();
        int itemCount = lines.stream().mapToInt(OrderLine::quantity).sum();

        ShipmentPort.DeliveryEstimate estimate = shipments.estimate(bearerToken,
                address.postalCode(), itemCount, basket.subtotalMinor());

        PriceBreakdown price = OrderPricing.compose(lines, basket.discountMinor(),
                estimate.shippingChargeMinor());
        DeliveryWindow promised = estimate.window();

        Quote quote = new Quote(UUID.randomUUID(), userId, basket.basketVersion(), address,
                lines, price, promised, clock.instant().plus(QUOTE_TTL));
        quotes.save(quote);
        return quote;
    }
}
