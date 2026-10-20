package com.poc.order.domain.model;

public record AddressSnapshot(String fullName, String line1, String line2, String city,
                              String state, String postalCode, String country) {}
