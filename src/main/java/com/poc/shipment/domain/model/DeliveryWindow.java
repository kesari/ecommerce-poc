package com.poc.shipment.domain.model;

import java.time.LocalDate;

public record DeliveryWindow(LocalDate from, LocalDate to) {}
