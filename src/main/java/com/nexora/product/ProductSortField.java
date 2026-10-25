package com.nexora.product;

import java.util.Locale;

/** The only properties /api/products/filter-optional can be sorted by (a whitelist, never arbitrary fields). */
public enum ProductSortField {

    PRICE("price"),
    STOCK("stock");

    private final String attribute;

    ProductSortField(String attribute) {
        this.attribute = attribute;
    }

    /** Name of the {@link Product} attribute to order by. */
    public String attribute() {
        return attribute;
    }

    /** Parses a request value case-insensitively; null means "no sorting requested". */
    public static ProductSortField fromParameter(String value) {
        return value == null ? null : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
