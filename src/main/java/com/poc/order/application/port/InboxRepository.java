package com.poc.order.application.port;

import java.util.UUID;

public interface InboxRepository {

    boolean claim(UUID eventId);
}
