package com.poc.order.domain.model;

import java.time.LocalDate;

public record DeliveryWindow(LocalDate fromDate, LocalDate toDate) {}
