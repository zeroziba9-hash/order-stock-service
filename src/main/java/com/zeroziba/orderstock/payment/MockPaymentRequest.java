package com.zeroziba.orderstock.payment;

import jakarta.validation.constraints.NotNull;

// 테스트용 결과 선택. 실제 결제사와 통신하지 않는다.
public record MockPaymentRequest(@NotNull Result result) {
    public enum Result { SUCCESS, FAILURE }
}
