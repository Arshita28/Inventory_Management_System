package com.inventory.service.analytics;

import com.inventory.model.InventorySnapshot;
import com.inventory.model.MovementType;
import com.inventory.model.ProductCategory;
import com.inventory.model.procurement.OrderStatus;
import com.inventory.model.procurement.SupplierOffer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure calculations over a consistent snapshot; does not create orders or mutate stock. */
public final class AnalyticsCalculator {
    public AnalyticsReport calculate(InventorySnapshot snapshot, LocalDate from, LocalDate to, ZoneId zone) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > 366) throw new IllegalArgumentException("Choose an inclusive date range of 1–366 days");
        var start = from.atStartOfDay(zone).toInstant();
        var end = to.plusDays(1).atStartOfDay(zone).toInstant();
        Map<String, Long> sold = new HashMap<>();
        Map<String, Long> received = new HashMap<>();
        Map<LocalDate, long[]> daily = new java.util.TreeMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) daily.put(date, new long[2]);
        var stock = snapshot.stock().stream().filter(value -> value.product().details().category() != ProductCategory.LEGACY).toList();
        var skus = stock.stream().map(value -> value.product().sku()).collect(java.util.stream.Collectors.toSet());
        snapshot.movements().stream().filter(value -> skus.contains(value.sku()))
                .filter(value -> !value.occurredAt().isBefore(start) && value.occurredAt().isBefore(end)).forEach(value -> {
                    long units = Math.abs((long) value.quantity());
                    boolean sale = value.type() == MovementType.SALE;
                    (sale ? sold : received).merge(value.sku(), units, Math::addExact);
                    long[] bucket = daily.get(value.occurredAt().atZone(zone).toLocalDate());
                    bucket[sale ? 0 : 1] = Math.addExact(bucket[sale ? 0 : 1], units);
                });
        BigDecimal retailValue = stock.stream().map(value -> value.product().sellingPrice().multiply(BigDecimal.valueOf(value.quantity())))
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        List<AnalyticsReport.ProductPerformance> products = new ArrayList<>();
        List<AnalyticsReport.RestockSuggestion> restock = new ArrayList<>();
        for (var item : stock) {
            String sku = item.product().sku();
            long sales = sold.getOrDefault(sku, 0L);
            LocalDate firstKnown = snapshot.movements().stream().filter(value -> value.sku().equals(sku) && value.occurredAt().isBefore(end))
                    .map(value -> value.occurredAt().atZone(zone).toLocalDate()).min(LocalDate::compareTo).orElse(to.plusDays(1));
            long observedDays = Math.max(0, ChronoUnit.DAYS.between(firstKnown.isBefore(from) ? from : firstKnown, to) + 1);
            boolean sufficientHistory = observedDays >= 7;
            BigDecimal cover = sales == 0 || !sufficientHistory ? null : BigDecimal.valueOf(item.quantity()).multiply(BigDecimal.valueOf(observedDays))
                    .divide(BigDecimal.valueOf(sales), 1, RoundingMode.HALF_UP);
            products.add(new AnalyticsReport.ProductPerformance(item, sales, received.getOrDefault(sku, 0L), cover));
            if (!item.product().active()) continue;
            SupplierOffer offer = snapshot.offers().stream().filter(value -> value.sku().equals(sku))
                    .min(Comparator.comparing(SupplierOffer::unitCost).thenComparingInt(SupplierOffer::leadDays).thenComparing(SupplierOffer::id)).orElse(null);
            int leadDays = offer == null ? 0 : offer.leadDays();
            long open = snapshot.orders().stream().filter(value -> value.sku().equals(sku) && value.status().isOpen()).mapToLong(value -> value.outstanding()).sum();
            long approved = snapshot.orders().stream().filter(value -> value.sku().equals(sku)
                    && (value.status() == OrderStatus.APPROVED || value.status() == OrderStatus.PARTIALLY_RECEIVED)).mapToLong(value -> value.outstanding()).sum();
            long threshold = item.product().reorderLevel();
            long trigger = threshold + (sufficientHistory ? demand(sales, leadDays, observedDays) : 0);
            long target = sufficientHistory && sales > 0 ? Math.max(trigger + 1, threshold + demand(sales, leadDays + 14, observedDays))
                    : Math.max(trigger + 1, threshold * 2);
            long position = (long) item.quantity() + open;
            long suggested = Math.max(0, target - position);
            if (suggested > 0 && offer != null) suggested = Math.max(suggested, offer.minimumQuantity());
            suggested = Math.min(suggested, Integer.MAX_VALUE - (long) item.quantity());
            if (offer != null && suggested > 0 && suggested < offer.minimumQuantity()) suggested = 0;
            if (item.quantity() <= trigger) {
                String explanation = sales + " units recorded sold in the selected " + days + " days; " + observedDays + " observed history days; lead time " + leadDays
                        + " days; configured buffer " + threshold + "; review period 14 days. Trigger " + trigger
                        + ", target " + target + ", on hand " + item.quantity() + ", open orders " + open + "."
                        + (offer == null ? " Add a supplier quote to place an order." : " Supplier minimum " + offer.minimumQuantity() + ".")
                        + (sales == 0 || !sufficientHistory ? " Fewer than 7 observed days or no sales: threshold-based fallback, not a forecast." : " Forecast uses observed history, which may be incomplete; review before ordering.");
                restock.add(new AnalyticsReport.RestockSuggestion(item, offer, sales, open, approved, trigger, target, (int) suggested, explanation));
            }
        }
        products.sort(Comparator.comparingLong(AnalyticsReport.ProductPerformance::sold).reversed()
                .thenComparing(value -> value.stock().product().sku()));
        return new AnalyticsReport(from, to, zone, sold.values().stream().mapToLong(Long::longValue).sum(),
                received.values().stream().mapToLong(Long::longValue).sum(), retailValue,
                daily.entrySet().stream().map(entry -> new AnalyticsReport.DailyMovement(entry.getKey(), entry.getValue()[0], entry.getValue()[1])).toList(),
                products, restock);
    }

    private long demand(long sales, int horizon, long days) {
        return BigDecimal.valueOf(sales).multiply(BigDecimal.valueOf(horizon))
                .divide(BigDecimal.valueOf(days), 0, RoundingMode.CEILING).longValueExact();
    }
}
