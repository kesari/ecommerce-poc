package com.poc.shipment.api;

import com.poc.shipment.api.dto.DeliveryEstimateRequest;
import com.poc.shipment.api.dto.DeliveryEstimateResponse;
import com.poc.shipment.api.dto.ShipmentResponse;
import com.poc.shipment.application.ShipmentService;
import com.poc.shipment.domain.model.DeliveryAddress;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ShipmentController {

    private final ShipmentService shipments;

    public ShipmentController(ShipmentService shipments) {
        this.shipments = shipments;
    }

    @PostMapping("/delivery-estimates")
    DeliveryEstimateResponse estimate(@Valid @RequestBody DeliveryEstimateRequest request) {
        return DeliveryEstimateResponse.from(shipments.estimate(
                request.postalCode(), request.itemCount(), request.subtotalMinor()));
    }

    @GetMapping("/shipments/by-order/{orderId}")
    ShipmentResponse byOrder(@PathVariable UUID orderId) {
        return ShipmentResponse.from(shipments.byOrderId(orderId));
    }
}
