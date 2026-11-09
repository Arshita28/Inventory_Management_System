package com.inventory.model.procurement;

import java.time.Instant;
import java.util.UUID;

public record OrderEvent(UUID orderId, String action, UUID actorId, String note, Instant occurredAt) { }
