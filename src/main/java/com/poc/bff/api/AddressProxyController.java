package com.poc.bff.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.poc.bff.infrastructure.client.DownstreamRelay;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/addresses", produces = MediaType.APPLICATION_JSON_VALUE)
public class AddressProxyController {

    private final DownstreamRelay relay;

    public AddressProxyController(DownstreamRelay relay) {
        this.relay = relay;
    }

    @ApiResponse(responseCode = "200", description = "Saved addresses")
    @GetMapping
    ResponseEntity<JsonNode> list(@RequestHeader HttpHeaders headers) {
        return call(HttpMethod.GET, "", null, headers);
    }

    @ApiResponse(responseCode = "201", description = "Created address")
    @PostMapping
    ResponseEntity<JsonNode> create(@RequestHeader HttpHeaders headers,
                                    @RequestBody JsonNode body) {
        return call(HttpMethod.POST, "", body, headers);
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated address"),
            @ApiResponse(responseCode = "404", description = "ADDRESS_NOT_FOUND")})
    @PutMapping("/{addressId}")
    ResponseEntity<JsonNode> update(@RequestHeader HttpHeaders headers,
                                    @PathVariable UUID addressId,
                                    @RequestBody JsonNode body) {
        return call(HttpMethod.PUT, "/" + addressId, body, headers);
    }

    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "ADDRESS_NOT_FOUND")})
    @DeleteMapping("/{addressId}")
    ResponseEntity<JsonNode> delete(@RequestHeader HttpHeaders headers,
                                    @PathVariable UUID addressId) {
        return call(HttpMethod.DELETE, "/" + addressId, null, headers);
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String suffix, JsonNode body,
                                          HttpHeaders headers) {
        return ProxySupport.json(relay.relay("account", method, "/api/v1/addresses" + suffix,
                body, DownstreamRelay.forward(ProxySupport.bearer(headers),
                        ProxySupport.correlationId())));
    }
}
