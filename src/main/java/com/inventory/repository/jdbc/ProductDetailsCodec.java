package com.inventory.repository.jdbc;

import com.inventory.exception.PersistenceException;
import com.inventory.model.ProductCategory;
import com.inventory.model.details.LegacyDetails;
import com.inventory.model.details.ProductDetails;
import com.inventory.model.details.ProductDetailsFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Properties;

/** JDK properties encoding; no Java serialization or runtime class-name loading. */
final class ProductDetailsCodec {
    private ProductDetailsCodec() { }

    static String encode(ProductDetails details) {
        Properties properties = new Properties();
        properties.putAll(details.attributes());
        try {
            StringWriter output = new StringWriter();
            properties.store(output, "Electronics specifications");
            return output.toString();
        } catch (IOException exception) {
            throw new PersistenceException("Unable to encode product specifications", exception);
        }
    }

    static ProductDetails decode(String type, String data, String categoryLabel) {
        try {
            ProductCategory category = ProductCategory.valueOf(type);
            if (category == ProductCategory.LEGACY) {
                return new LegacyDetails(categoryLabel);
            }
            Properties properties = new Properties();
            properties.load(new StringReader(data));
            var fields = new HashMap<String, String>();
            properties.stringPropertyNames().forEach(name -> fields.put(name, properties.getProperty(name)));
            return ProductDetailsFactory.create(category, fields);
        } catch (IOException | IllegalArgumentException exception) {
            throw new PersistenceException("Stored product specifications are invalid", exception);
        }
    }
}
