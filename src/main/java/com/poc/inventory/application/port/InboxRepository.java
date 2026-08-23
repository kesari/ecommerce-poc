package com.poc.inventory.application.port;

import java.util.UUID;

public interface InboxRepository {

    boolean claim(UUID eventId);
}
