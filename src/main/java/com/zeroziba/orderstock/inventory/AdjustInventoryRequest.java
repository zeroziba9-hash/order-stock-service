package com.zeroziba.orderstock.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdjustInventoryRequest(@NotNull Integer delta, @NotBlank @Size(max = 200) String reason) { }
