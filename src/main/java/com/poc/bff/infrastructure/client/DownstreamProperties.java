package com.poc.bff.infrastructure.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "downstream")
public record DownstreamProperties(String account, String catalog, String basket, String order) {}
