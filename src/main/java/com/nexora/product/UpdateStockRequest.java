package com.nexora.product;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

public record UpdateStockRequest(
        @JsonDeserialize(using = WholeNumberDeserializer.class) @NotNull @Min(0) Integer stock) {

    /**
     * Stock is a whole number of units: a decimal such as 25.7 is rejected (400) instead of being silently
     * truncated to 25. Applies to this field only; every other token keeps Jackson's default Integer handling.
     */
    static class WholeNumberDeserializer extends StdDeserializer<Integer> {

        WholeNumberDeserializer() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return _parseInteger(parser, context, Integer.class);
        }
    }
}
