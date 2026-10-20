package com.poc.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.poc.payment.application.port.PaymentStore;
import com.poc.payment.domain.Payment;
import com.poc.payment.domain.PaymentStatus;
import com.poc.payment.domain.Refund;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentCommandService {
    public static final String CHARGED_TOPIC = "payment.charged.v1";
    public static final String DECLINED_TOPIC = "payment.declined.v1";
    public static final String REFUNDED_TOPIC = "payment.refunded.v1";

    private final PaymentStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public PaymentCommandService(PaymentStore store, ObjectMapper objectMapper, Clock clock,
                                 MeterRegistry meterRegistry) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void charge(EventEnvelope commandEnvelope, ChargeRequested command) {
        if (!store.claimEvent(commandEnvelope.eventId())) {
            return;
        }
        if (store.findByOrderId(command.orderId()).isPresent()) {
            return;
        }
        if ("tok_error".equals(command.token())) {
            throw new PaymentProcessingException("mock payment provider failed");
        }

        Instant now = clock.instant();
        UUID paymentId = UUID.randomUUID();
        PaymentStatus status = "tok_success".equals(command.token())
                ? PaymentStatus.CHARGED : PaymentStatus.DECLINED;
        String providerReference = status == PaymentStatus.CHARGED
                ? "charge:" + command.orderId() : null;
        Payment payment = new Payment(paymentId, command.orderId(), command.amountMinor(), command.currency(),
                status, providerReference, command.token(), now);
        store.insertPayment(payment);

        if (status == PaymentStatus.CHARGED) {
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("orderId", command.orderId().toString())
                    .put("paymentId", paymentId.toString())
                    .put("providerReference", providerReference);
            addResultEvent(commandEnvelope, command.orderId(), CHARGED_TOPIC, payload, now);
            meterRegistry.counter("payments_total", "result", "charged").increment();
        } else {
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("orderId", command.orderId().toString())
                    .put("paymentId", paymentId.toString())
                    .put("reason", "PAYMENT_DECLINED");
            addResultEvent(commandEnvelope, command.orderId(), DECLINED_TOPIC, payload, now);
            meterRegistry.counter("payments_total", "result", "declined").increment();
        }
    }

    @Transactional
    public void refund(EventEnvelope commandEnvelope, RefundRequested command) {
        if (!store.claimEvent(commandEnvelope.eventId())) {
            return;
        }
        Payment payment = store.findByPaymentIdForUpdate(command.paymentId()).orElse(null);
        if (payment == null) {
            return;
        }
        if (!payment.orderId().equals(command.orderId())) {
            throw new InvalidMessageException("paymentId does not belong to orderId");
        }
        if (command.amountMinor() != payment.amountMinor()) {
            throw new InvalidMessageException("only the original full payment amount can be refunded");
        }
        if (payment.status() == PaymentStatus.REFUNDED || payment.status() == PaymentStatus.REFUND_PENDING) {
            return;
        }
        if (payment.status() != PaymentStatus.CHARGED) {
            throw new InvalidMessageException("only a charged payment can be refunded");
        }

        Instant now = clock.instant();
        UUID refundId = UUID.randomUUID();
        store.updateStatus(payment.paymentId(), PaymentStatus.REFUND_PENDING);
        store.insertRefund(new Refund(refundId, payment.paymentId(), command.amountMinor(), now));
        store.updateStatus(payment.paymentId(), PaymentStatus.REFUNDED);

        ObjectNode payload = objectMapper.createObjectNode()
                .put("orderId", command.orderId().toString())
                .put("refundId", refundId.toString());
        addResultEvent(commandEnvelope, command.orderId(), REFUNDED_TOPIC, payload, now);
        meterRegistry.counter("payments_total", "result", "refunded").increment();
    }

    private void addResultEvent(EventEnvelope cause, UUID orderId, String topic, ObjectNode payload, Instant now) {
        UUID eventId = UUID.randomUUID();
        EventEnvelope result = new EventEnvelope(eventId, topic.substring(0, topic.length() - 3), 1, now,
                "payment-service", orderId, cause.eventId(), orderId, payload);
        try {
            store.addOutboxEvent(eventId, orderId, topic, objectMapper.writeValueAsString(result), now);
        } catch (JsonProcessingException exception) {
            throw new PaymentProcessingException("could not serialize payment result event");
        }
    }
}
