package com.poc.payment.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock paymentClock() {
        return Clock.systemUTC();
    }

    @Bean
    NewTopic chargeRequested() {
        return topic("payment.charge.requested.v1");
    }

    @Bean
    NewTopic chargeRetryOne() {
        return topic("payment.charge.requested.v1.retry.1");
    }

    @Bean
    NewTopic chargeRetryTwo() {
        return topic("payment.charge.requested.v1.retry.2");
    }

    @Bean
    NewTopic chargeDlq() {
        return topic("payment.charge.requested.v1.dlq");
    }

    @Bean
    NewTopic refundRequested() {
        return topic("payment.refund.requested.v1");
    }

    @Bean
    NewTopic refundRetryOne() {
        return topic("payment.refund.requested.v1.retry.1");
    }

    @Bean
    NewTopic refundRetryTwo() {
        return topic("payment.refund.requested.v1.retry.2");
    }

    @Bean
    NewTopic refundDlq() {
        return topic("payment.refund.requested.v1.dlq");
    }

    @Bean
    NewTopic paymentCharged() {
        return topic("payment.charged.v1");
    }

    @Bean
    NewTopic paymentDeclined() {
        return topic("payment.declined.v1");
    }

    @Bean
    NewTopic paymentRefunded() {
        return topic("payment.refunded.v1");
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}
