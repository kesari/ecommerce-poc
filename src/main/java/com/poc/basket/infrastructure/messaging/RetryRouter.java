package com.poc.basket.infrastructure.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

@Component
public class RetryRouter {

    public static final String HEADER_ORIGINAL_TOPIC = "poc-original-topic";
    public static final String HEADER_ATTEMPT = "poc-attempt";
    public static final String HEADER_ERROR_CODE = "poc-error-code";
    public static final String HEADER_ERROR_MESSAGE = "poc-error-message";
    public static final String HEADER_FAILED_AT = "poc-failed-at";
    public static final String CODE_INVALID_MESSAGE = "INVALID_MESSAGE";
    public static final String CODE_PROCESSING_ERROR = "PROCESSING_ERROR";
    public static final int MAX_ATTEMPTS = 3;
    private static final int MESSAGE_LIMIT = 512;

    private static final Logger log = LoggerFactory.getLogger(RetryRouter.class);

    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    public RetryRouter(KafkaTemplate<String, String> kafka, Clock clock) {
        this.kafka = kafka;
        this.clock = clock;
    }

    public void route(String currentTopic, String key, String value, int attempt, Exception failure) {
        String baseTopic = baseTopicOf(currentTopic);
        String destination = attempt >= MAX_ATTEMPTS
                ? baseTopic + ".dlq"
                : baseTopic + ".retry." + attempt;
        String code = failure instanceof InvalidMessageException
                ? CODE_INVALID_MESSAGE
                : CODE_PROCESSING_ERROR;

        ProducerRecord<String, String> record = new ProducerRecord<>(destination, key, value);
        for (Header header : headers(baseTopic, attempt + 1, code, failure)) {
            record.headers().add(header);
        }
        kafka.send(record);
        log.warn("routed {} attempt {} to {} ({})", baseTopic, attempt, destination, code);
    }

    public static String baseTopicOf(String topic) {
        int marker = topic.indexOf(".retry.");
        if (marker > 0) {
            return topic.substring(0, marker);
        }
        return topic.endsWith(".dlq") ? topic.substring(0, topic.length() - 4) : topic;
    }

    public static int attemptOf(String topic) {
        int marker = topic.indexOf(".retry.");
        return marker < 0 ? 1 : Integer.parseInt(topic.substring(marker + 7)) + 1;
    }

    private List<Header> headers(String baseTopic, int nextAttempt, String code, Exception failure) {
        return List.of(
                header(HEADER_ORIGINAL_TOPIC, baseTopic),
                header(HEADER_ATTEMPT, String.valueOf(nextAttempt)),
                header(HEADER_ERROR_CODE, code),
                header(HEADER_ERROR_MESSAGE, sanitize(failure)),
                header(HEADER_FAILED_AT, clock.instant().toString()));
    }

    private static Header header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitize(Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        String flattened = message.replaceAll("\\s+", " ").trim();
        return flattened.length() <= MESSAGE_LIMIT ? flattened : flattened.substring(0, MESSAGE_LIMIT);
    }
}
