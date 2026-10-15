package com.zeroziba.orderstock.inventory;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class InventoryMovementController {
    private final InventoryLedger ledger;
    public InventoryMovementController(InventoryLedger ledger) { this.ledger=ledger; }
    @GetMapping("/admin/inventory-movements")
    public InventoryLedger.History history(@AuthenticationPrincipal AuthenticatedUser admin,
            @RequestParam long productId,@RequestParam(required=false) Long beforeId,
            @RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to) {
        return ledger.history(admin.getId(),productId,beforeId,from,to);
    }
}
