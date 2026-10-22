package com.nexora.product;

import java.math.BigDecimal;

public class InvalidPriceRangeException extends RuntimeException {

    public InvalidPriceRangeException(BigDecimal min, BigDecimal max) {
        super("min (" + min.toPlainString() + ") must be less than or equal to max (" + max.toPlainString() + ")");
    }
}
