package com.poc.payment.infrastructure.kafka;

import com.poc.payment.application.InvalidMessageException;
import com.poc.payment.application.PaymentCommandService;
import com.poc.payment.application.PaymentMessageParser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "payment.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentCommandListener {
    private static final String ORIGINAL_TOPIC = "poc-original-topic";
    private static final String ATTEMPT = "poc-attempt";
    private static final String ERROR_CODE = "poc-error-code";
    private static final String ERROR_MESSAGE = "poc-error-message";
    private static final String FAILED_AT = "poc-failed-at";

    private final PaymentMessageParser parser;
    private final PaymentCommandService service;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    public PaymentCommandListener(PaymentMessageParser parser, PaymentCommandService service,
                                  KafkaTemplate<String, String> kafkaTemplate, Clock clock) {
        this.parser = parser;
        this.service = service;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
    }

    @KafkaListener(topics = "payment.charge.requested.v1", groupId = "payment-charge")
    public void charge(ConsumerRecord<String, String> record) {
        handleCharge(record, "payment.charge.requested.v1", 1);
    }

    @KafkaListener(topics = "payment.charge.requested.v1.retry.1", groupId = "payment-charge-retry-1")
    public void chargeRetryOne(ConsumerRecord<String, String> record) {
        pause(100);
        handleCharge(record, "payment.charge.requested.v1", 2);
    }

    @KafkaListener(topics = "payment.charge.requested.v1.retry.2", groupId = "payment-charge-retry-2")
    public void chargeRetryTwo(ConsumerRecord<String, String> record) {
        pause(500);
        handleCharge(record, "payment.charge.requested.v1", 3);
    }

    @KafkaListener(topics = "payment.refund.requested.v1", groupId = "payment-refund")
    public void refund(ConsumerRecord<String, String> record) {
        handleRefund(record, "payment.refund.requested.v1", 1);
    }

    @KafkaListener(topics = "payment.refund.requested.v1.retry.1", groupId = "payment-refund-retry-1")
    public void refundRetryOne(ConsumerRecord<String, String> record) {
        pause(100);
        handleRefund(record, "payment.refund.requested.v1", 2);
    }

    @KafkaListener(topics = "payment.refund.requested.v1.retry.2", groupId = "payment-refund-retry-2")
    public void refundRetryTwo(ConsumerRecord<String, String> record) {
        pause(500);
        handleRefund(record, "payment.refund.requested.v1", 3);
    }

    private void handleCharge(ConsumerRecord<String, String> record, String rootTopic, int attempt) {
        try {
            var parsed = parser.parseCharge(record.value());
            requireKafkaKey(record, parsed.command().orderId().toString());
            service.charge(parsed.envelope(), parsed.command());
        } catch (RuntimeException exception) {
            routeFailure(record, rootTopic, attempt, exception);
        }
    }

    private void handleRefund(ConsumerRecord<String, String> record, String rootTopic, int attempt) {
        try {
            var parsed = parser.parseRefund(record.value());
            requireKafkaKey(record, parsed.command().orderId().toString());
            service.refund(parsed.envelope(), parsed.command());
        } catch (RuntimeException exception) {
            routeFailure(record, rootTopic, attempt, exception);
        }
    }

    private static void requireKafkaKey(ConsumerRecord<String, String> record, String orderId) {
        if (!orderId.equals(record.key())) {
            throw new InvalidMessageException("Kafka key must equal payload.orderId");
        }
    }

    private void routeFailure(ConsumerRecord<String, String> record, String rootTopic, int attempt,
                              RuntimeException exception) {
        String destination = attempt == 1 ? rootTopic + ".retry.1"
                : attempt == 2 ? rootTopic + ".retry.2" : rootTopic + ".dlq";
        String errorCode = causedByInvalidMessage(exception) ? "INVALID_MESSAGE" : "PROCESSING_ERROR";
        RecordHeaders headers = new RecordHeaders();
        copyTraceHeaders(record, headers);
        add(headers, ORIGINAL_TOPIC, rootTopic);
        add(headers, ATTEMPT, Integer.toString(attempt < 3 ? attempt + 1 : attempt));
        add(headers, ERROR_CODE, errorCode);
        add(headers, ERROR_MESSAGE, sanitize(exception.getMessage()));
        add(headers, FAILED_AT, DateTimeFormatter.ISO_INSTANT.format(clock.instant()));
        ProducerRecord<String, String> failed = new ProducerRecord<>(destination, null, record.key(), record.value(), headers);
        try {
            kafkaTemplate.send(failed).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while routing failed payment command", interrupted);
        } catch (Exception sendFailure) {
            throw new IllegalStateException("Could not route failed payment command", sendFailure);
        }
    }

    private static boolean causedByInvalidMessage(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof InvalidMessageException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void copyTraceHeaders(ConsumerRecord<String, String> record, RecordHeaders target) {
        for (Header header : record.headers()) {
            if (header.key().equals("traceparent") || header.key().equals("tracestate")) {
                target.add(header.key(), header.value());
            }
        }
    }

    private static void add(RecordHeaders headers, String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitize(String message) {
        String safe = message == null ? "payment command processing failed" : message;
        safe = safe.replaceAll("tok_(success|declined|error)", "[REDACTED]")
                .replace('\n', ' ').replace('\r', ' ');
        return safe.substring(0, Math.min(512, safe.length()));
    }

    private static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry delay interrupted", exception);
        }
    }
}
