package com.inventory.service.analytics;

import com.inventory.model.StockSummary;
import com.inventory.model.procurement.SupplierOffer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public record AnalyticsReport(LocalDate from, LocalDate to, ZoneId zone, long soldUnits, long receivedUnits,
                              BigDecimal currentRetailValue, List<DailyMovement> daily,
                              List<ProductPerformance> products, List<RestockSuggestion> restock) {
    public AnalyticsReport {
        daily = List.copyOf(daily);
        products = List.copyOf(products);
        restock = List.copyOf(restock);
    }

    public record DailyMovement(LocalDate date, long sold, long received) { }
    public record ProductPerformance(StockSummary stock, long sold, long received, BigDecimal daysCover) { }
    public record RestockSuggestion(StockSummary stock, SupplierOffer offer, long sold, long openCommitments,
                                   long approvedInbound, long trigger, long target, int suggestedQuantity, String explanation) { }
}
