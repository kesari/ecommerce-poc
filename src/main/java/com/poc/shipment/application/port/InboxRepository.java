package com.poc.shipment.application.port;

public interface InboxRepository {

    boolean alreadyProcessed(String eventId);

    void record(String eventId, String eventType);
}
