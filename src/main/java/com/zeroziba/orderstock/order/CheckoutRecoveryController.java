package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class CheckoutRecoveryController {
    private final CheckoutRecoveryService recovery;
    public CheckoutRecoveryController(CheckoutRecoveryService recovery){this.recovery=recovery;}
    @GetMapping("/orders/cart/recovery")
    public List<CheckoutRecoveryService.Intent> list(@AuthenticationPrincipal AuthenticatedUser user){return recovery.list(user.getId());}
    @PutMapping("/orders/cart/quote/{id}/intent")
    public CheckoutRecoveryService.Intent register(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id,@Valid @RequestBody CheckoutRecoveryService.Register body){return recovery.register(user.getId(),id,body);}
    @PostMapping("/orders/cart/quote/{id}/acknowledge")
    public ResponseEntity<Void> acknowledge(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id){recovery.acknowledge(user.getId(),id);return ResponseEntity.noContent().build();}
}
