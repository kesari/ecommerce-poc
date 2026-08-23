package com.poc.account.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressRequest(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Size(max = 200) String line1,
        @Size(max = 200) String line2,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 100) String state,
        @NotBlank @Size(min = 3, max = 12) String postalCode,
        @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country,
        @Size(max = 20) String phoneNumber
) {
}
