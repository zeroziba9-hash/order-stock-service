package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.net.URI;
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
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final IdempotentOrderService idempotentOrderService;

    public OrderController(OrderService orderService, IdempotentOrderService idempotentOrderService) {
        this.orderService = orderService;
        this.idempotentOrderService = idempotentOrderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key == null) {
            OrderResponse response = orderService.create(user.getId(), request);
            return ResponseEntity.created(URI.create("/orders/" + response.orderId())).body(response);
        }
        IdempotentOrderService.CreationResult result = idempotentOrderService.create(user.getId(), key, request);
        URI location = URI.create("/orders/" + result.order().orderId());
        if (result.replayed()) {
            return ResponseEntity.ok().location(location).header("Idempotency-Replayed", "true").body(result.order());
        }
        return ResponseEntity.created(location).header("Idempotency-Replayed", "false").body(result.order());
    }

    @GetMapping
    public OrderListResponse findRecent(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) PurchaseOrder.Status status,
            @RequestParam(required = false) java.time.LocalDate from,
            @RequestParam(required = false) java.time.LocalDate to) {
        return orderService.findRecent(user.getId(), beforeId,status,from,to);
    }

    @PostMapping("/cart")
    public ResponseEntity<OrderResponse> cart(@Valid @RequestBody CartOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser user,@RequestHeader(name="Idempotency-Key",required=false) String key) {
        var result=idempotentOrderService.createCart(user.getId(),key,request);
        return ResponseEntity.status(result.replayed()?200:201).location(URI.create("/orders/"+result.order().orderId()))
            .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.order());
    }

    @GetMapping("/{orderId}")
    public OrderResponse findById(@PathVariable long orderId, @AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.findById(user.getId(), orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@PathVariable long orderId, @AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.cancel(user.getId(), orderId);
    }
}
