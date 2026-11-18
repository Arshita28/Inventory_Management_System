package com.inventory.preview;

import com.inventory.model.InventorySnapshot;
import com.inventory.model.ProductCategory;
import com.inventory.service.analytics.AnalyticsReport;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Locale;

/** Presentation of recorded metrics; the illustration is decorative, never a data source. */
final class DashboardPresentation {
    private static final String[] COLORS = {"#d9ff57", "#f1f2e9", "#a9b66b", "#737b57", "#c7c9bc", "#4e5542"};

    private DashboardPresentation() { }

    static String hero(AnalyticsReport report, InventorySnapshot snapshot, LocalDate today) {
        long active = report.products().stream().filter(value -> value.stock().product().active()).count();
        long available = report.products().stream().filter(value -> value.stock().product().active() && value.stock().quantity() > 0).count();
        long low = report.products().stream().filter(value -> value.stock().isLowStock()).count();
        long inbound = snapshot.orders().stream().filter(value -> value.status() == com.inventory.model.procurement.OrderStatus.APPROVED
                || value.status() == com.inventory.model.procurement.OrderStatus.PARTIALLY_RECEIVED).mapToLong(value -> value.outstanding()).sum();
        long suppliers = snapshot.offers().stream().map(value -> value.supplierName()).distinct().count();
        StringBuilder html = new StringBuilder("<div class='hero-sales glass-card'><div class='card-top'><span>Sales activity</span><a href='#analytics' aria-label='View sales analytics'>" + UiIcons.ARROW_UP_RIGHT + "</a></div><div class='sales-number'>")
                .append(number(report.soldUnits())).append("<span>units sold</span></div><div class='mini-bars' aria-hidden='true'>");
        var daily = report.daily();
        int groups = Math.min(20, daily.size());
        long[] grouped = new long[groups];
        for (int index = 0; index < groups; index++) {
            for (int day = index * daily.size() / groups; day < (index + 1) * daily.size() / groups; day++) grouped[index] += daily.get(day).sold();
        }
        long maximum = java.util.Arrays.stream(grouped).max().orElse(0);
        for (int index = 0; index < groups; index++) {
            long height = maximum == 0 ? 0 : Math.round(grouped[index] * 100.0 / maximum);
            html.append("<i style='height:").append(height).append("%'></i>");
        }
        html.append("</div><small>").append(report.from()).append(" — ").append(report.to()).append("</small></div>")
                .append("<div class='hero-right'><section class='incoming-card glass-card'><div class='card-top'><span>Incoming stock</span><span class='live-indicator'>Recorded</span></div><div class='incoming-values'>")
                .append(value(report.receivedUnits(), "Received units")).append(value(inbound, "Approved inbound"))
                .append(value(suppliers, "Suppliers")).append("</div><div class='incoming-actions'><div class='period-tabs'>")
                .append(period("Today", today, today, report)).append(period("Week", today.minusDays(6), today, report))
                .append(period("Month", today.minusDays(29), today, report)).append("</div><a class='lime-link' href='#purchase-orders'>View orders " + UiIcons.ARROW_UP_RIGHT + "</a></div></section>")
                .append("<div class='hero-small-cards'><section class='glass-card availability-card'><div class='card-top'><span>Availability</span><a href='#catalogue' aria-label='View available inventory'>" + UiIcons.ARROW_UP_RIGHT + "</a></div><small>Active products with stock</small><strong>")
                .append(active == 0 ? 0 : available * 100 / active).append("%</strong><div class='availability-track'><i style='width:")
                .append(active == 0 ? 0 : available * 100 / active).append("%'></i></div><small>").append(available).append(" of ").append(active).append(" products</small></section>")
                .append("<section class='glass-card attention-card'><div class='card-top'><span>Low stock</span><a href='#replenishment' aria-label='Review low-stock products'>" + UiIcons.ARROW_UP_RIGHT + "</a></div><small>At or below reorder level</small><strong>")
                .append(low).append(low == 1 ? "<span>product</span></strong>" : "<span>products</span></strong>").append("<a class='attention-link' href='#replenishment'>Review &amp; reorder " + UiIcons.ARROW_UP_RIGHT + "</a></section></div></div>");
        return html.toString();
    }

    static String overview(AnalyticsReport report, InventorySnapshot snapshot) {
        long units = report.products().stream().mapToLong(value -> value.stock().quantity()).sum();
        long active = report.products().stream().filter(value -> value.stock().product().active()).count();
        long healthy = report.products().stream().filter(value -> value.stock().product().active() && !value.stock().isLowStock()).count();
        long health = active == 0 ? 0 : healthy * 100 / active;
        StringBuilder out = new StringBuilder("<section id='dashboard' data-view='dashboard' class='overview-report workspace-view'><div class='report-heading'><div><span class='section-kicker'>REPORTS</span><h2>Inventory report</h2></div><span class='report-range'>")
                .append(report.from()).append(" — ").append(report.to()).append("<a href='#analytics' aria-label='Change report period'>" + UiIcons.CHEVRON_DOWN + "</a></span></div><div class='report-grid'>")
                .append("<article class='overview-card'><div class='card-top'><h3>Key figures</h3><a href='/export/inventory.csv' aria-label='Export inventory CSV'>" + UiIcons.DOWNLOAD + "</a></div><small>Live inventory · selected-period movements</small><table><thead><tr><th>Metric</th><th>Current value</th><th>Basis</th></tr></thead><tbody>")
                .append("<tr><td>Retail stock value</td><td>").append(decimal(report.currentRetailValue())).append("</td><td class='accent-text'>Current</td></tr>")
                .append("<tr><td>Units in stock</td><td>").append(number(units)).append("</td><td>On hand</td></tr>")
                .append("<tr><td>Incoming stock</td><td>").append(number(report.receivedUnits())).append("</td><td>Recorded</td></tr></tbody></table></article>")
                .append("<article class='overview-card health-card'><div class='card-top'><h3>Stock health</h3><a href='#replenishment' aria-label='Review stock health'>" + UiIcons.ARROW_UP_RIGHT + "</a></div><small>Active products above reorder level</small><div class='health-value'>")
                .append(health).append("<span>%</span></div><div class='health-segments' aria-hidden='true'>");
        for (int index = 0; index < 40; index++) out.append("<i").append(index * 100 < health * 40 ? " class='filled'" : "").append("></i>");
        out.append("</div><p>").append(healthy).append(" of ").append(active).append(" active products are above their threshold.<br><a href='#replenishment'>Review replenishment " + UiIcons.ARROW_UP_RIGHT + "</a></p></article>")
                .append("<article class='overview-card distribution-card'><div class='card-top'><h3>Inventory distribution</h3><a href='#catalogue' aria-label='View category inventory'>" + UiIcons.ARROW_UP_RIGHT + "</a></div><small>Units on hand by category</small><div class='distribution-body'><ul class='category-legend'>");
        StringBuilder circles = new StringBuilder();
        double offset = 0;
        int index = 0;
        for (ProductCategory category : ProductCategory.electronics()) {
            long count = report.products().stream().filter(value -> value.stock().product().details().category() == category).mapToLong(value -> value.stock().quantity()).sum();
            String color = COLORS[index++];
            out.append("<li><i style='background:").append(color).append("'></i><span>").append(InventoryPage.html(category.label()))
                    .append("</span><b>").append(number(count)).append("</b></li>");
            double percent = units == 0 ? 0 : count * 100.0 / units;
            circles.append(String.format(Locale.ROOT, "<circle cx='60' cy='60' r='51' pathLength='100' fill='none' stroke='%s' stroke-width='8' stroke-dasharray='%.4f %.4f' stroke-dashoffset='%.4f'/>",
                    color, percent, 100 - percent, -offset));
            offset += percent;
        }
        out.append("</ul><div class='donut'><svg viewBox='0 0 120 120' aria-hidden='true'>")
                .append("<circle cx='60' cy='60' r='51' fill='none' stroke='#41423b' stroke-width='8'/><g transform='rotate(-90 60 60)'>")
                .append(circles).append("</g></svg><div><strong>").append(number(units)).append("</strong><small>Units total</small></div></div></div></article></div>");
        out.append("<section class='dashboard-stock-table panel'><div class='panel-title'><h2>Current stock</h2><p>Product quantities and reorder thresholds.</p></div><div class='table-wrap'><table><thead><tr><th>Product</th><th>SKU</th><th>On hand</th><th>Reorder level</th><th>Status</th></tr></thead><tbody>");
        for(var performance:report.products()) {
            var stock=performance.stock();var product=stock.product();
            out.append("<tr><td><a href='/app?sku=").append(InventoryPage.url(product.sku())).append("#management'>").append(InventoryPage.html(product.name())).append("</a></td><td>").append(InventoryPage.html(product.sku())).append("</td><td>").append(stock.quantity()).append("</td><td>").append(product.reorderLevel()).append("</td><td><span class='badge ").append(stock.isLowStock()?"warning":"good").append("'>").append(!product.active()?"Inactive":stock.quantity()==0?"Out of stock":stock.isLowStock()?"Low stock":"In stock").append("</span></td></tr>");
        }
        out.append("</tbody></table></div></section></section>");
        return out.toString();
    }

    private static String period(String label, LocalDate from, LocalDate to, AnalyticsReport report) {
        return "<a class='" + (report.from().equals(from) && report.to().equals(to) ? "selected" : "")
                + "' href='/app?from=" + from + "&amp;to=" + to + "#home'>" + label + "</a>";
    }

    private static String value(long value, String label) { return "<div><strong>" + number(value) + "</strong><small>" + label + "</small></div>"; }
    private static String number(long value) { return NumberFormat.getIntegerInstance(Locale.US).format(value); }
    private static String decimal(BigDecimal value) {
        var format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }
}
