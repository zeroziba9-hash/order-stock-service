package com.zeroziba.orderstock.refund;

import jakarta.validation.constraints.NotNull;

// 외부 결제사에 연결하지 않는 전액 환불 시뮬레이션.
public record MockRefundRequest(@NotNull Result result) {
    public enum Result { SUCCESS, FAILURE }
}
