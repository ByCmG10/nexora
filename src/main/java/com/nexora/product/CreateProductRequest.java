package com.nexora.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank String name,
        // Surrounding whitespace is allowed here because it is stripped before persisting; the SKU itself
        // (1-255 chars, the column length) may only contain ASCII letters, digits, '-' and '_'.
        @NotBlank @Pattern(regexp = "\\s*[A-Za-z0-9_-]{1,255}\\s*",
                message = "must be 1-255 characters: letters, digits, '-' and '_' only") String sku,
        // Compatible with the products.price column: numeric(12,2).
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Min(0) Integer stock,
        @NotNull @Min(0) Integer minimumStock) {
}
