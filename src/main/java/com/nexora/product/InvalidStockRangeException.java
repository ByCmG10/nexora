package com.nexora.product;

public class InvalidStockRangeException extends RuntimeException {

    public InvalidStockRangeException(Integer minStock, Integer maxStock) {
        super("minStock (" + minStock + ") must be less than or equal to maxStock (" + maxStock + ")");
    }
}
