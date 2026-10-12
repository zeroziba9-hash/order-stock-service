package com.zeroziba.orderstock.order;

import java.util.List;

public record OrderListResponse(List<OrderResponse> orders, Long nextBeforeId) { }
