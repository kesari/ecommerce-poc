package com.poc.account.api;

import com.poc.account.application.AddressBookService;
import com.poc.account.api.dto.AddressRequest;
import com.poc.account.api.dto.AddressResponse;
import com.poc.account.domain.model.Address;
import com.poc.account.infrastructure.mapping.AddressDtoMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/addresses")
public class AddressController {

    private final AddressBookService addresses;
    private final AddressDtoMapper mapper;

    public AddressController(AddressBookService addresses, AddressDtoMapper mapper) {
        this.addresses = addresses;
        this.mapper = mapper;
    }

    @GetMapping
    List<AddressResponse> list(@AuthenticationPrincipal Jwt principal) {
        return addresses.list(userId(principal)).stream().map(mapper::toResponse).toList();
    }

    @GetMapping("/{addressId}")
    AddressResponse get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID addressId) {
        return mapper.toResponse(addresses.get(userId(principal), addressId));
    }

    @PostMapping
    ResponseEntity<AddressResponse> create(@AuthenticationPrincipal Jwt principal,
                                           @Valid @RequestBody AddressRequest request) {
        UUID userId = userId(principal);
        Address created = addresses.create(userId, request.fullName(), request.line1(),
                request.line2(), request.city(), request.state(), request.postalCode(),
                request.country(), request.phoneNumber());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(created));
    }

    @PutMapping("/{addressId}")
    AddressResponse update(@AuthenticationPrincipal Jwt principal, @PathVariable UUID addressId,
                           @Valid @RequestBody AddressRequest request) {
        UUID userId = userId(principal);
        return mapper.toResponse(addresses.update(userId, addressId, request.fullName(),
                request.line1(), request.line2(), request.city(), request.state(),
                request.postalCode(), request.country(), request.phoneNumber()));
    }

    @DeleteMapping("/{addressId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt principal, @PathVariable UUID addressId) {
        addresses.delete(userId(principal), addressId);
        return ResponseEntity.noContent().build();
    }

    static UUID userId(Jwt principal) {
        return UUID.fromString(principal.getSubject());
    }
}
