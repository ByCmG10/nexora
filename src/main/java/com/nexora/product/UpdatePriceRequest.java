package com.nexora.product;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record UpdatePriceRequest(
        // Greater than 0, and compatible with the products.price column: numeric(12,2).
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal price) {
}
