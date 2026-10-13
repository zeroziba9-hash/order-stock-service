package com.zeroziba.orderstock.inventory;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class InventoryController {
    private final InventoryService inventory;
    public InventoryController(InventoryService inventory) { this.inventory = inventory; }

    @PostMapping("/products/{productId}/inventory-adjustments")
    public ResponseEntity<InventoryAdjustment> adjust(@AuthenticationPrincipal AuthenticatedUser admin,
            @PathVariable long productId, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdjustInventoryRequest request) {
        var result = inventory.adjust(admin.getId(), productId, key, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.adjustment());
    }

    @GetMapping("/inventory-adjustments")
    public InventoryService.History history(@AuthenticationPrincipal AuthenticatedUser admin,
            @RequestParam(required = false) Long productId, @RequestParam(required = false) Long beforeId) {
        return inventory.history(admin.getId(), productId, beforeId);
    }
}
