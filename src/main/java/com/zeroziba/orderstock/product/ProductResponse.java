package com.zeroziba.orderstock.product;

/** 상품 API 응답. DB 엔티티와 응답 형식을 분리한다. price는 원 단위 정수 금액이다. */
public record ProductResponse(long id, String name, long price, int stock) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getStock());
    }
}
