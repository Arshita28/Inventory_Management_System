package com.inventory.reporting;

import com.inventory.model.StockSummary;
import java.util.List;

/** Spreadsheet-friendly CSV with quoting and formula interpretation protection. */
public final class CsvInventoryExporter {
    public String export(List<StockSummary> products) {
        StringBuilder csv = new StringBuilder("SKU,Name,Category,Unit price,Quantity,Reorder level,Active,Category code,Specifications\r\n");
        for (StockSummary stock : products) {
            var product = stock.product();
            csv.append(cell(product.sku())).append(',').append(cell(product.name())).append(',')
                    .append(cell(product.category())).append(',').append(product.sellingPrice().toPlainString())
                    .append(',').append(stock.quantity()).append(',').append(product.reorderLevel())
                    .append(',').append(product.active()).append(',').append(cell(product.details().category().name()))
                    .append(',').append(cell(product.details().summary())).append("\r\n");
        }
        return csv.toString();
    }

    private String cell(String value) {
        if (!value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
