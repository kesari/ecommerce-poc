package com.poc.order.application.port;

import com.poc.order.domain.model.Quote;

import java.util.Optional;
import java.util.UUID;

public interface QuoteRepository {

    void save(Quote quote);

    Optional<Quote> findById(UUID quoteId);
}
