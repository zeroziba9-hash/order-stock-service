package com.zeroziba.orderstock.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
        @NotNull(message = "상품 ID는 필수입니다.")
        @Positive(message = "상품 ID는 1 이상이어야 합니다.") Long productId,
        @NotNull(message = "주문 수량은 필수입니다.")
        @Positive(message = "주문 수량은 1 이상이어야 합니다.") Integer quantity) {
}
