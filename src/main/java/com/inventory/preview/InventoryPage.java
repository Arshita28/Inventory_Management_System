package com.inventory.preview;

import com.inventory.model.Product;
import com.inventory.model.ProductCategory;
import com.inventory.model.StockMovement;
import com.inventory.model.StockSummary;
import com.inventory.security.Permission;
import com.inventory.security.User;
import com.inventory.security.auth.AccessService;
import com.inventory.service.InventoryService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server-rendered view. All stock decisions remain in InventoryService. */
final class InventoryPage {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private final AccessService access;
    private final InventoryService inventory;
    private final String template;
    private final OperationsPage operations;

    InventoryPage(InventoryService inventory) throws IOException {
        this(inventory, null);
    }

    InventoryPage(InventoryService inventory, AccessService access) throws IOException {
        this.access = access;
        this.inventory = inventory;
        operations = new OperationsPage(inventory.operations(),inventory.business());
        try (var input = InventoryPage.class.getResourceAsStream("/inventory.html")) {
            if (input == null) {
                throw new IOException("Inventory page template is missing");
            }
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    String render(User viewer, Map<String, String> query, Map<String, String> submitted,
                  String action, String error, String csrf, boolean demoSwitch) {
        List<StockSummary> catalogue = inventory.listProducts(viewer);
        StockSummary requested = query.containsKey("sku") ? inventory.getStock(viewer, query.get("sku")) : null;
        String scope = query.getOrDefault("scope", requested != null && requested.product().details().category() == ProductCategory.LEGACY ? "legacy" : "electronics");
        List<StockSummary> all = catalogue.stream().filter(stock -> switch (scope) {
            case "all" -> true;
            case "legacy" -> stock.product().details().category() == ProductCategory.LEGACY;
            default -> stock.product().details().category() != ProductCategory.LEGACY;
        }).toList();
        String search = query.getOrDefault("q", "").strip();
        String category = query.getOrDefault("category", "");
        String status = query.getOrDefault("status", "all");
        List<StockSummary> products = all.stream()
                .filter(stock -> (stock.product().sku() + " " + stock.product().name() + " " + stock.product().details().summary())
                        .toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))
                .filter(stock -> category.isEmpty() || stock.product().category().equals(category))
                .filter(stock -> switch (status) {
                    case "low" -> stock.isLowStock();
                    case "active" -> stock.product().active();
                    case "inactive" -> !stock.product().active();
                    default -> true;
                }).toList();
        StockSummary selected = requested != null ? requested : products.stream().findFirst().orElse(null);
        Map<String, String> values = new HashMap<>();
        values.put("productCount", String.valueOf(all.size()));
        values.put("unitCount", String.valueOf(all.stream().mapToLong(StockSummary::quantity).sum()));
        values.put("lowCount", String.valueOf(all.stream().filter(StockSummary::isLowStock).count()));
        values.put("catalogueActions", viewer.role().allows(Permission.MANAGE_PRODUCTS)
                ? "<a class='lime-link' href='#add-product'>+ Add product</a>" : "");
        values.put("stockAlerts", viewer.role().allows(Permission.VIEW_ANALYTICS)
                ? "<a class='header-icon' href='#replenishment' aria-label='Review stock alerts' title='Stock alerts'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='M6 8a6 6 0 0 1 12 0c0 7 3 7 3 9H3c0-2 3-2 3-9M9 21h6'/></svg><i></i></a>" : "");
        values.put("role", html(viewer.role().label()));
        values.put("accountStatus", access == null ? "LOCAL DEMO" : "SIGNED IN");
        values.put("identityHelp", access == null ? "Demonstration identity; login is disabled in this test fixture." : "Signed in as " + html(viewer.username()));
        values.put("logoutControl", access == null ? "" : "<form method='post' action='/auth/logout'><input type='hidden' name='csrf' value='" + html(csrf) + "'><button class='logout-button' type='submit' title='Logout'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='M9 4H4v16h5M14 8l4 4-4 4M8 12h10'/></svg><span>Logout</span></button></form>");
        values.put("accountPanel", access != null && viewer.role().allows(Permission.MANAGE_USERS) ? AccountPage.render(access.users(viewer), csrf) : "");
        values.put("actorControls", demoSwitch ? OperationsPage.demoActors(viewer, csrf) : "");
        var operationsView = operations.render(viewer, query, csrf, submitted, action);
        values.put("operations", operationsView.content());
        values.put("business", new BusinessPage(inventory).render(viewer, csrf, query, submitted, action));
        if(!viewer.role().allows(Permission.VIEW_ANALYTICS)) values.put("operations","<section class='workspace-view panel staff-dashboard' id='dashboard' data-view='dashboard'><div class='panel-title'><h1>Dashboard</h1><p>Manage stock availability and record customer sales.</p></div><div class='staff-actions'><a class='button' href='#management'>View inventory</a>"+(inventory.business()!=null?"<a class='button' href='#sales'>New sale</a>":"")+"</div></section>");
        String box="M3 7l9-4 9 4-9 4zM3 7v10l9 4 9-4V7M12 11v10";
        String order="M7 3h10v4H7zM7 5H4v16h16V5h-3M8 12h8M8 16h5";
        StringBuilder navigation=new StringBuilder(navItem("dashboard","Dashboard","M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z"));
        String inventoryLinks=navItem("management","Product catalogue",box);
        if(viewer.role().allows(Permission.MANAGE_PRODUCTS)) inventoryLinks+=navItem("add-product","Add product",box)+(inventory.business()!=null?navItem("stocktaking","Stocktaking",order):"");
        navigation.append(navGroup("Inventory",box,inventoryLinks));
        if(inventory.business()!=null) navigation.append(navGroup("Sales","M3 4h3l3 12h10l2-8H7M10 21h.01M18 21h.01",navItem("sales","Invoices & returns",order)+navItem("new-sale","New sale",order)));
        if(viewer.role().allows(Permission.VIEW_ANALYTICS)) {
            String purchases=(inventory.business()!=null?navItem("purchase-batches","Purchase orders",order):"")+navItem("purchase-orders","Approvals & deliveries",order)+navItem("replenishment","Replenishment",box);
            navigation.append(navGroup("Purchasing",order,purchases));
            navigation.append(navGroup("Suppliers","M3 21V7l9-4 9 4v14M7 10h10M7 15h10",(inventory.business()!=null?navItem("supplier-directory","Supplier directory",box):"")+navItem("supplier-quotes","Supplier quotes",order)));
            navigation.append(navGroup("Reports","M4 3v18h17M8 16v-4M13 16V8M18 16V5",navItem("analytics","Sales & inventory",order)));
        }
        if(access!=null && viewer.role().allows(Permission.MANAGE_USERS)) navigation.append(navItem("users","Staff & access","M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8"));
        if(inventory.business()!=null && viewer.role().allows(Permission.MANAGE_USERS)) navigation.append(navGroup("Settings","M4 4h16v16H4zM8 4v6h8V4M8 20v-6h8v6",navItem("backup","Backup & recovery",order)));
        navigation.append(navItem("help","Help & guide","M9 9a3 3 0 1 1 6 0c0 2-3 2-3 4M12 17h.01M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20"));
        values.put("accountName",html(viewer.username()));
        values.put("accountInitials",html(viewer.username().substring(viewer.username().lastIndexOf('.')+1).substring(0,Math.min(2,viewer.username().length()-viewer.username().lastIndexOf('.')-1)).toUpperCase(Locale.ROOT)));
        values.put("navigation", navigation.toString());
        values.put("initialView", initialView(query, action));
        values.put("rows", productRows(products, query));
        values.put("selectedName", selected == null ? "Select a product" : html(selected.product().name()));
        values.put("selectedSku", selected == null ? "" : html(selected.product().sku()));
        values.put("history", history(viewer, selected));
        values.put("selectedDetails", selected == null ? "" : html(selected.product().details().summary()));
        values.put("filters", filters(all, search, category, status, scope));
        values.put("resultCount", "Showing " + products.size() + " of " + all.size() + " products");
        values.put("stockForms", stockForms(viewer, selected, csrf, submitted, action));
        values.put("productForms", productForms(viewer, selected, csrf, submitted, action, query));
        String notice = switch (query.getOrDefault("notice", "")) {
            case "saved" -> "Record saved.";
            case "invoice" -> "Invoice saved.";
            case "return" -> "Return recorded.";
            case "created" -> "Product created. Receive stock to set its opening quantity.";
            case "updated" -> "Product details updated. Stock and movement history are unchanged.";
            case "received" -> "Stock receipt recorded.";
            case "sold" -> "Stock sale recorded.";
            case "deactivated" -> "Product deactivated. Its movement history is preserved.";
            case "purchase" -> "Purchase order saved. Physical stock changes only when an approved delivery is received.";
            case "users" -> "Account saved. Changed accounts must log in again.";
            case "quote" -> "Supplier quote saved.";
            default -> "";
        };
        values.put("message", !error.isEmpty() ? "<div role='alert' class='message error'>" + html(error) + "</div>"
                : notice.isEmpty() ? "" : "<div role='status' class='message success'>" + html(notice) + "</div>");
        Matcher matcher = PLACEHOLDER.matcher(template);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(values.getOrDefault(match.group(1), "")));
    }

    private static String navGroup(String title,String icon,String links) {
        return "<details class='nav-group'><summary><svg viewBox='0 0 24 24' aria-hidden='true'><path d='"+icon+"'/></svg><span>"+title+"</span><svg class='nav-chevron' viewBox='0 0 16 16' aria-hidden='true'><path d='m5 6 3 3 3-3'/></svg></summary><div class='nav-submenu'>"+links+"</div></details>";
    }

    private static String navItem(String target, String label, String icon) {
        return "<a href='#" + target + "' data-nav='" + target + "' title='" + label
                + "'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='" + icon + "'/></svg><span>" + label + "</span></a>";
    }

    private static String initialView(Map<String, String> query, String action) {
        if(action.startsWith("/business/purchase")) return "purchase-batches";
        if(action.startsWith("/business/count")) return "stocktaking";
        if(action.equals("/business/checkout") || action.equals("/business/return") || query.containsKey("invoice")) return "sales";
        if(action.equals("/business/supplier") || action.equals("/business/quote")) return "supplier-directory";
        if (action.startsWith("/users/") || "users".equals(query.get("notice"))) return "users";
        if (action.equals("/purchase/create")) return "replenishment";
        if (action.startsWith("/purchase/") || query.containsKey("order") || "purchase".equals(query.get("notice"))) return "purchase-orders";
        if (action.startsWith("/suppliers/") || "quote".equals(query.get("notice"))) return "supplier-quotes";
        if (action.startsWith("/products/") || action.startsWith("/stock/") || query.containsKey("notice")
                || java.util.stream.Stream.of("sku", "q", "scope", "category", "status", "newCategory").anyMatch(query::containsKey)) return "management";
        if (query.containsKey("from") || query.containsKey("to")) return "analytics";
        return "dashboard";
    }

    private String productRows(List<StockSummary> products, Map<String, String> query) {
        StringBuilder rows = new StringBuilder();
        for (StockSummary stock : products) {
            Product product = stock.product();
            String status = !product.active() ? "Inactive" : stock.quantity() == 0
                    ? "Out of stock" : stock.isLowStock() ? "Low stock" : "In stock";
            String badge = !product.active() ? "neutral" : stock.isLowStock() ? "warning" : "good";
            Map<String, String> link = new HashMap<>(query);
            link.remove("notice");
            link.put("sku", product.sku());
            rows.append("<tr><td><a href='/app").append(html(queryString(link))).append("'>")
                    .append(html(product.name())).append("</a><small>").append(html(product.sku()))
                    .append("</small><small class='spec-summary'>").append(html(product.details().summary()))
                    .append("</small></td><td>").append(html(product.category()))
                    .append("</td><td class='number'>").append(product.sellingPrice().toPlainString())
                    .append("</td><td class='number quantity'>").append(stock.quantity())
                    .append("</td><td class='number'>").append(product.reorderLevel())
                    .append("</td><td><span class='badge ").append(badge).append("'>")
                    .append(status).append("</span></td></tr>");
        }
        return rows.isEmpty() ? "<tr><td colspan='6' class='empty'>No matching products. Clear filters or add a product below.</td></tr>" : rows.toString();
    }

    private String history(User viewer, StockSummary selected) {
        if (!viewer.role().allows(Permission.VIEW_HISTORY)) {
            return "<li class='empty'>Your role cannot view movement history.</li>";
        }
        StringBuilder history = new StringBuilder();
        if (selected != null) {
            for (StockMovement movement : inventory.history(viewer, selected.product().sku()).reversed()) {
                history.append("<li><div class='event-head'><strong>")
                        .append(switch(movement.type()) { case RECEIPT -> "Stock received"; case SALE -> "Stock sold"; case RETURN -> "Customer return"; case ADJUSTMENT -> "Stock adjustment"; })
                        .append("</strong><span class='").append(movement.quantity() > 0 ? "positive" : "negative")
                        .append("'>").append(movement.quantity() > 0 ? "+" : "").append(movement.quantity())
                        .append("</span></div><p>").append(html(movement.reason())).append("</p><small>")
                        .append(movement.occurredAt()).append(" · Balance ").append(movement.balanceAfter())
                        .append("</small><small>Actor ").append(movement.actorId()).append("</small></li>");
            }
        }
        return history.isEmpty() ? "<li class='empty'>No stock movements to show.</li>" : history.toString();
    }

    private String filters(List<StockSummary> all, String search, String category, String status, String scope) {
        StringBuilder form = new StringBuilder("<form method='get' action='/app' class='filters'>");
        form.append("<input aria-label='Search products' name='q' placeholder='Search name, SKU or specs' maxlength='255' value='")
                .append(html(search)).append("'><select aria-label='Catalogue view' name='scope'>")
                .append(option("electronics", "Electronics catalogue", scope)).append(option("legacy", "Legacy records", scope))
                .append(option("all", "All records", scope))
                .append("</select><select aria-label='Category filter' name='category'><option value=''>All categories</option>");
        all.stream().map(stock -> stock.product().category()).distinct().sorted().forEach(value ->
                form.append(option(value, value, category)));
        form.append("</select><select aria-label='Stock status' name='status'>")
                .append(option("all", "All products", status)).append(option("low", "Low stock", status))
                .append(option("active", "Active", status)).append(option("inactive", "Inactive", status));
        return form.append("</select><button type='submit'>Apply filters</button><a class='button' href='/app'>Clear</a></form>").toString();
    }

    private String stockForms(User viewer, StockSummary selected, String csrf,
                              Map<String, String> submitted, String action) {
        if (selected == null || !selected.product().active()) {
            return "";
        }
        StringBuilder forms = new StringBuilder("<div class='stock-forms'>");
        for (String operation : List.of("receive", "sell")) {
            Permission permission = operation.equals("receive") ? Permission.RECEIVE_STOCK : Permission.SELL_STOCK;
            if (!viewer.role().allows(permission)) {
                continue;
            }
            String path = "/stock/" + operation;
            Map<String, String> fields = path.equals(action) ? submitted : Map.of();
            String label = operation.equals("receive") ? "Receive stock" : "Quick stock sale";
            forms.append("<details ").append(path.equals(action) ? "open" : "").append("><summary>").append(label)
                    .append("</summary><form method='post' action='").append(path).append("'>")
                    .append(hidden("csrf", csrf)).append(hidden("sku", selected.product().sku()))
                    .append(hidden("requestId", fields.getOrDefault("requestId", UUID.randomUUID().toString())))
                    .append(input(label + " quantity", "quantity", "number", fields.getOrDefault("quantity", "1"), "min='1' max='2147483647' step='1'"))
                    .append(input(label + " reason", "reason", "text", fields.getOrDefault("reason", ""), "maxlength='2000'"))
                    .append("<button type='submit'>").append(label).append("</button></form></details>");
        }
        return forms.append("<p class='muted'>Stock changes are recorded immediately. Use Sales &amp; returns for customer invoices.</p></div>").toString();
    }

    private String productForms(User viewer, StockSummary selected, String csrf,
                                Map<String, String> submitted, String action, Map<String, String> query) {
        if (!viewer.role().allows(Permission.MANAGE_PRODUCTS)) {
            return "";
        }
        StringBuilder forms = new StringBuilder("<section id='add-product' class='panel product-panel'><div class='panel-title'><h2>Add product</h2><p>Choose a category first, enter its specifications, then record opening stock.</p></div>");
        Map<String, String> fields = action.equals("/products/create") ? submitted : Map.of();
        ProductCategory createCategory = chosenCategory(fields.getOrDefault("category", query.getOrDefault("newCategory", "CHARGING")), ProductCategory.CHARGING);
        if (createCategory == ProductCategory.LEGACY) {
            createCategory = ProductCategory.CHARGING;
        }
        forms.append(categorySelector("New product category", "newCategory", createCategory, selected, false, "add-product"));
        forms.append("<form class='product-form' method='post' action='/products/create'>").append(hidden("csrf", csrf))
                .append(hidden("category", createCategory.name()))
                .append("<div class='form-grid'>").append(input("SKU", "sku", "text", fields.getOrDefault("sku", ""), "maxlength='40'"))
                .append(catalogueFields(fields)).append("</div><h3 class='spec-heading'>").append(createCategory.label()).append(" specifications</h3><div class='form-grid'>")
                .append(ElectronicsFields.render(createCategory, fields)).append("</div><div class='form-actions'><button type='submit'>Create product</button></div></form></section>");
        if (selected != null) {
            Product product = selected.product();
            Map<String, String> defaults = new HashMap<>(product.details().attributes());
            defaults.putAll(Map.of("name", product.name(), "category", product.details().category().name(),
                    "price", product.sellingPrice().toPlainString(), "reorderLevel", String.valueOf(product.reorderLevel())));
            ProductCategory editCategory = chosenCategory(query.getOrDefault("editCategory", product.details().category().name()), product.details().category());
            if (action.equals("/products/update")) {
                editCategory = chosenCategory(submitted.get("category"), editCategory);
            }
            if (editCategory == ProductCategory.LEGACY && product.details().category() != ProductCategory.LEGACY) {
                editCategory = product.details().category();
            }
            if (editCategory != product.details().category()) {
                product.details().attributes().keySet().forEach(defaults::remove);
            }
            fields = action.equals("/products/update") ? submitted : defaults;
            forms.append("<section id='edit-product' class='panel product-panel'><details ")
                    .append(query.containsKey("editCategory") || action.equals("/products/update") || action.equals("/products/deactivate") ? "open" : "")
                    .append("><summary>Edit ").append(html(product.name())).append(" · ").append(html(product.sku()))
                    .append("</summary>").append(categorySelector("Edit product category", "editCategory", editCategory, selected,
                            product.details().category() == ProductCategory.LEGACY, "edit-product"))
                    .append("<form method='post' action='/products/update'>").append(hidden("csrf", csrf))
                    .append(hidden("category", editCategory.name()))
                    .append(hidden("sku", product.sku())).append("<div class='form-grid'>").append(catalogueFields(fields))
                    .append("</div><h3 class='spec-heading'>").append(editCategory == ProductCategory.LEGACY ? "Legacy record — select an electronics category to classify" : editCategory.label() + " specifications")
                    .append("</h3><div class='form-grid'>").append(ElectronicsFields.render(editCategory, fields))
                    .append("</div><div class='form-actions'><button type='submit'>Save product details</button></div></form>");
            if (product.active()) {
                forms.append("<form class='deactivate' method='post' action='/products/deactivate'>")
                        .append(hidden("csrf", csrf)).append(hidden("sku", product.sku()))
                        .append("<p class='muted'>Deactivation requires zero stock and keeps all movement history.</p><button class='danger' type='submit'>Deactivate product</button></form>");
            }
            forms.append("</details></section>");
        }
        return forms.toString();
    }

    private String catalogueFields(Map<String, String> fields) {
        return input("Product name", "name", "text", fields.getOrDefault("name", ""), "maxlength='255'")
                + input("Unit price", "price", "number", fields.getOrDefault("price", ""), "min='0' step='0.01'")
                + input("Reorder level", "reorderLevel", "number", fields.getOrDefault("reorderLevel", "0"), "min='0' max='2147483647' step='1'");
    }

    private String categorySelector(String label, String name, ProductCategory selected, StockSummary product, boolean legacy, String anchor) {
        StringBuilder form = new StringBuilder("<form class='category-picker' method='get' action='/app#" + anchor + "'>");
        if (product != null) {
            form.append(hidden("sku", product.product().sku()));
        }
        form.append("<label>").append(label).append("<select name='").append(name).append("'>");
        if (legacy) {
            form.append(option("LEGACY", "Keep legacy category", selected.name()));
        }
        ProductCategory.electronics().forEach(category -> form.append(option(category.name(), category.label(), selected.name())));
        return form.append("</select></label><button class='secondary' type='submit'>Show category fields</button><span class='muted'>Choose before filling the form below.</span></form>").toString();
    }

    private ProductCategory chosenCategory(String value, ProductCategory fallback) {
        try {
            return ProductCategory.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private String queryString(Map<String, String> query) {
        return query.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> url(entry.getKey()) + "=" + url(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&", "?", ""));
    }

    private String input(String label, String name, String type, String value, String attributes) {
        if(type.equals("number")) return NumericFields.render(label,name,value,attributes);
        return "<label>" + label + "<input required name='" + name + "' type='" + type + "' value='" + html(value) + "' " + attributes + "></label>";
    }

    private String option(String value, String label, String selected) {
        return "<option value='" + html(value) + "'" + (value.equals(selected) ? " selected" : "") + ">" + html(label) + "</option>";
    }

    private String hidden(String name, String value) {
        return "<input type='hidden' name='" + name + "' value='" + html(value) + "'>";
    }

    static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
