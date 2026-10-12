package com.zeroziba.orderstock.inventory;

import java.time.Instant;

public record InventoryAdjustment(long adjustmentId, long adminId, long productId, String productName,
        int delta, int beforeStock, int afterStock, String reason, Instant createdAt) { }
