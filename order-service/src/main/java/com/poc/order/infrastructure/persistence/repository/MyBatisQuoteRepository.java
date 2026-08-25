package com.poc.order.infrastructure.persistence.repository;

import com.poc.order.application.port.QuoteRepository;
import com.poc.order.domain.model.DeliveryWindow;
import com.poc.order.domain.model.OrderLine;
import com.poc.order.domain.model.PriceBreakdown;
import com.poc.order.domain.model.Quote;
import com.poc.order.infrastructure.persistence.mapper.OrderMapper;
import com.poc.order.infrastructure.persistence.row.LineRow;
import com.poc.order.infrastructure.persistence.row.QuoteRow;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisQuoteRepository implements QuoteRepository {

    private final OrderMapper mapper;
    private final JsonSupport json;

    public MyBatisQuoteRepository(OrderMapper mapper, JsonSupport json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public void save(Quote quote) {
        mapper.insertQuote(new QuoteRow(quote.quoteId(), quote.userId(), quote.basketVersion(),
                json.write(quote.address()), quote.price().subtotalMinor(),
                quote.price().discountMinor(), quote.price().shippingMinor(),
                quote.price().taxMinor(), quote.price().totalMinor(), quote.price().currency(),
                quote.promised().fromDate(), quote.promised().toDate(), quote.expiresAt()));
        mapper.insertQuoteLines(quote.lines().stream()
                .map(line -> new LineRow(quote.quoteId(), line.productId(), line.name(),
                        line.unitPriceMinor(), line.quantity()))
                .toList());
    }

    @Override
    public Optional<Quote> findById(UUID quoteId) {
        return mapper.findQuote(quoteId).map(row -> new Quote(
                row.quoteId(), row.userId(), row.basketVersion(),
                json.readAddress(row.addressSnapshot()),
                mapper.findQuoteLines(quoteId).stream()
                        .map(line -> new OrderLine(line.productId(), line.name(),
                                line.unitPriceMinor(), line.quantity()))
                        .toList(),
                new PriceBreakdown(row.subtotalMinor(), row.discountMinor(), row.shippingMinor(),
                        row.taxMinor(), row.totalMinor(), row.currency()),
                new DeliveryWindow(row.promisedFrom(), row.promisedTo()),
                row.expiresAt()));
    }
}
