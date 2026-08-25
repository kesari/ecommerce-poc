package com.poc.shipment.api;

import com.poc.shipment.api.dto.DeliveryEstimateRequest;
import com.poc.shipment.api.dto.DeliveryEstimateResponse;
import com.poc.shipment.api.dto.ShipmentResponse;
import com.poc.shipment.application.ShipmentService;
import com.poc.shipment.domain.model.DeliveryAddress;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ShipmentController {

    private final ShipmentService shipments;

    public ShipmentController(ShipmentService shipments) {
        this.shipments = shipments;
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Delivery estimate"),
            @ApiResponse(responseCode = "400", description = "INVALID_REQUEST",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/delivery-estimates")
    DeliveryEstimateResponse estimate(@Valid @RequestBody DeliveryEstimateRequest request) {
        return DeliveryEstimateResponse.from(shipments.estimate(
                request.postalCode(), request.itemCount(), request.subtotalMinor()));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Shipment"),
            @ApiResponse(responseCode = "404", description = "SHIPMENT_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @GetMapping("/shipments/by-order/{orderId}")
    ShipmentResponse byOrder(@PathVariable UUID orderId) {
        return ShipmentResponse.from(shipments.byOrderId(orderId));
    }
}
