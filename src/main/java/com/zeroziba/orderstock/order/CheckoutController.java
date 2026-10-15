package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class CheckoutController {
    private final CheckoutService checkout;
    private final CartService carts;
    public CheckoutController(CheckoutService checkout,CartService carts){this.checkout=checkout;this.carts=carts;}

    @GetMapping("/cart")
    public CartService.Cart cart(@AuthenticationPrincipal AuthenticatedUser user){return carts.get(user.getId());}
    @PutMapping("/cart")
    public CartService.Cart update(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody CartService.Update body){return carts.update(user.getId(),body);}
    @PostMapping("/orders/cart/quote")
    public CheckoutService.Quote quote(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody CartOrderRequest body){return checkout.quote(user.getId(),body);}
    @PostMapping("/orders/cart/checkout")
    public ResponseEntity<OrderResponse> checkout(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody CheckoutService.Request body,
            @RequestHeader(name="Idempotency-Key",required=false) String key) {
        var result=checkout.checkout(user.getId(),key,body);
        return ResponseEntity.status(result.replayed()?200:201).location(URI.create("/orders/"+result.order().orderId()))
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.order());
    }
}
