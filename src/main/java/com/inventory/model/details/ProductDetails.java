package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;
import java.util.stream.Collectors;

/** Polymorphic specification contract composed into Product. */
public interface ProductDetails {
    ProductCategory category();

    Map<String, String> attributes();

    default String categoryLabel() {
        return category().label();
    }

    default String summary() {
        return attributes().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ": " + entry.getValue()).collect(Collectors.joining(" · "));
    }
}
