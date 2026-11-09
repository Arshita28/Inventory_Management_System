package com.inventory.preview;

import com.inventory.exception.AccessDeniedException;
import com.inventory.exception.InventoryException;
import com.inventory.model.Product;
import com.inventory.model.ProductCategory;
import com.inventory.model.details.ProductDetails;
import com.inventory.model.details.ProductDetailsFactory;
import com.inventory.model.procurement.OrderAction;
import com.inventory.model.procurement.SupplierOffer;
import com.inventory.service.OperationsService;
import com.inventory.model.StockSummary;
import com.inventory.reporting.CsvInventoryExporter;
import com.inventory.security.User;
import com.inventory.service.InventoryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Local demo adapter. Trusted actors are supplied at startup; login comes later. */
public final class LocalPreviewServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LocalPreviewServer.class.getName());
    private static final int MAX_FORM_BYTES = 16_384;
    private static final Set<String> WRITE_ROUTES = Set.of("/products/create", "/products/update",
            "/products/deactivate", "/stock/receive", "/stock/sell", "/purchase/create", "/purchase/action",
            "/purchase/receive", "/suppliers/offers/create", "/demo/actor");
    private final InventoryService inventory;
    private final User viewer;
    private final OperationsService operations;
    private final boolean demoSwitch;
    private final InventoryPage page;
    private final HttpServer server;
    private final String csrf = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LocalPreviewServer(InventoryService inventory, User viewer, int port) throws IOException {
        this(inventory, viewer, port, false);
    }

    public LocalPreviewServer(InventoryService inventory, User viewer, int port, boolean demoSwitch) throws IOException {
        this.inventory = inventory;
        this.viewer = viewer;
        this.operations = inventory.operations();
        this.demoSwitch = demoSwitch;
        page = new InventoryPage(inventory);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            // Restrict this unauthenticated demo to the explicit localhost origin.
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("localhost:" + port()).equals(host) && !("127.0.0.1:" + port()).equals(host)) {
                respond(exchange, 403, "text/plain", "Use the localhost preview address.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            User actor = currentActor(exchange);
            if (exchange.getRequestMethod().equals("POST") && WRITE_ROUTES.contains(path)) {
                handlePost(exchange, path, actor);
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, 405, "text/plain", "Method not allowed");
                return;
            }
            switch (path) {
                case "/" -> respond(exchange, 200, "text/html", page.render(actor,
                        parseFields(exchange.getRequestURI().getRawQuery()), Map.of(), "", "", csrf, demoSwitch));
                case "/health" -> respond(exchange, 200, "text/plain", "ok");
                case "/assets/dashboard.css" -> respondResource(exchange, "/assets/dashboard.css", "text/css; charset=utf-8");
                case "/assets/conveyor-hero.png" -> respondResource(exchange, "/assets/conveyor-hero.png", "image/png");
                case "/api/inventory" -> respond(exchange, 200, "application/json", inventoryJson(actor));
                case "/export/inventory.csv" -> {
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=inventory.csv");
                    respond(exchange, 200, "text/csv", new CsvInventoryExporter().export(inventory.listProducts(actor)));
                }
                case "/favicon.ico" -> respond(exchange, 204, "text/plain", "");
                default -> respond(exchange, 404, "text/plain", "Page not found");
            }
        } catch (AccessDeniedException exception) {
            respond(exchange, 403, "text/plain", exception.getMessage());
        } catch (InventoryException exception) {
            respond(exchange, 404, "text/plain", exception.getMessage());
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, "text/plain", "Invalid request parameters");
        } catch (java.time.DateTimeException exception) {
            respond(exchange, 400, "text/plain", "Choose valid analytics dates");
        } catch (RuntimeException exception) {
            LOG.log(Level.SEVERE, "Preview request failed", exception);
            respond(exchange, 500, "text/plain", "Inventory operation failed. Check the application log.");
        } finally {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange, String path, User actor) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/x-www-form-urlencoded")) {
            respond(exchange, 415, "text/plain", "Submit a URL-encoded form.");
            return;
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_FORM_BYTES + 1);
        if (bytes.length > MAX_FORM_BYTES) {
            respond(exchange, 413, "text/plain", "Form is too large.");
            return;
        }
        Map<String, String> fields = parseFields(new String(bytes, StandardCharsets.UTF_8));
        String suppliedToken = fields.getOrDefault("csrf", "");
        if (!MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            respond(exchange, 403, "text/plain", "Form expired or invalid. Reload the page and try again.");
            return;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals("http://localhost:" + port())
                && !origin.equals("http://127.0.0.1:" + port())) {
            respond(exchange, 403, "text/plain", "Cross-origin forms are not accepted.");
            return;
        }
        try {
            if (path.equals("/demo/actor")) {
                if (!demoSwitch) throw new AccessDeniedException("Demo identity switching is disabled");
                String identity = required(fields, "actor");
                if (!Set.of("manager", "approver", "cashier").contains(identity)) throw new IllegalArgumentException("Unknown demo identity");
                exchange.getResponseHeaders().set("Set-Cookie", "demo_actor=" + identity + "; Path=/; SameSite=Strict; HttpOnly");
                redirect(exchange, "/#purchase-orders");
                return;
            }
            if (path.startsWith("/purchase/") || path.equals("/suppliers/offers/create")) {
                redirect(exchange, operationalPost(path, fields, actor));
                return;
            }
            String sku = required(fields, "sku");
            String notice;
            switch (path) {
                case "/products/create" -> {
                    inventory.addProduct(actor, new Product(sku, required(fields, "name"),
                            new BigDecimal(required(fields, "price")), integer(fields, "reorderLevel"), true, details(fields, false, sku, actor)));
                    notice = "created";
                }
                case "/products/update" -> {
                    inventory.updateProduct(actor, sku, required(fields, "name"),
                            new BigDecimal(required(fields, "price")), integer(fields, "reorderLevel"), details(fields, true, sku, actor));
                    notice = "updated";
                }
                case "/products/deactivate" -> {
                    inventory.deactivateProduct(actor, sku);
                    notice = "deactivated";
                }
                case "/stock/receive", "/stock/sell" -> {
                    UUID requestId = UUID.fromString(required(fields, "requestId"));
                    if (path.equals("/stock/receive")) {
                        inventory.receiveStock(actor, requestId, sku, integer(fields, "quantity"), required(fields, "reason"));
                        notice = "received";
                    } else {
                        inventory.sellStock(actor, requestId, sku, integer(fields, "quantity"), required(fields, "reason"));
                        notice = "sold";
                    }
                }
                default -> throw new IllegalArgumentException("Unknown operation");
            }
            exchange.getResponseHeaders().set("Location", "/?sku=" + InventoryPage.url(Product.normalizeSku(sku)) + "&notice=" + notice);
            respond(exchange, 303, "text/plain", "Saved");
        } catch (AccessDeniedException exception) {
            formError(exchange, 403, fields, path, exception.getMessage(), actor);
        } catch (InventoryException exception) {
            formError(exchange, 409, fields, path, exception.getMessage(), actor);
        } catch (NumberFormatException | ArithmeticException exception) {
            formError(exchange, 422, fields, path, "Enter valid numbers. Quantities must be whole numbers and prices must have at most two decimal places.", actor);
        } catch (IllegalArgumentException exception) {
            formError(exchange, 422, fields, path, exception.getMessage(), actor);
        }
    }

    private void formError(HttpExchange exchange, int status, Map<String, String> fields, String action, String message, User actor) throws IOException {
        Map<String, String> query = Map.of();
        if (!action.equals("/products/create") && fields.containsKey("sku")) {
            boolean exists = inventory.listProducts(actor).stream()
                    .anyMatch(stock -> stock.product().sku().equalsIgnoreCase(fields.get("sku").strip()));
            if (exists) {
                query = Map.of("sku", fields.get("sku"));
            }
        }
        if (fields.containsKey("orderId")) query = Map.of("order", fields.get("orderId"));
        respond(exchange, status, "text/html", page.render(actor, query, fields, action, message, csrf, demoSwitch));
    }

    private static Map<String, String> parseFields(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Map.of();
        }
        if (encoded.length() > MAX_FORM_BYTES) {
            throw new IllegalArgumentException("Parameters too long");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (fields.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate form parameter");
            }
        }
        return Map.copyOf(fields);
    }

    private User currentActor(HttpExchange exchange) {
        if (demoSwitch) {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) {
                for (String part : cookie.split(";")) {
                    String[] entry = part.strip().split("=", 2);
                    if (entry.length == 2 && entry[0].equals("demo_actor")) return DemoData.actor(entry[1]);
                }
            }
        }
        return viewer;
    }

    private String operationalPost(String path, Map<String, String> fields, User actor) {
        if (path.equals("/suppliers/offers/create")) {
            operations.addOffer(actor, new SupplierOffer(UUID.fromString(required(fields, "requestId")), required(fields, "sku"),
                    required(fields, "supplierName"), new BigDecimal(required(fields, "unitCost")), integer(fields, "leadDays"), integer(fields, "minimumQuantity")));
            return "/?notice=quote#supplier-quotes";
        }
        UUID orderId;
        if (path.equals("/purchase/create")) {
            orderId = operations.createOrder(actor, UUID.fromString(required(fields, "requestId")), UUID.fromString(required(fields, "offerId")),
                    integer(fields, "quantity"), required(fields, "note")).id();
        } else {
            orderId = UUID.fromString(required(fields, "orderId"));
            if (path.equals("/purchase/action")) operations.transition(actor, orderId, OrderAction.valueOf(required(fields, "action")), required(fields, "note"));
            else operations.receive(actor, orderId, UUID.fromString(required(fields, "requestId")), integer(fields, "quantity"), required(fields, "note"));
        }
        return "/?order=" + orderId + "&notice=purchase#purchase-orders";
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        respond(exchange, 303, "text/plain", "Saved");
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return value.strip();
    }

    private static int integer(Map<String, String> fields, String key) {
        return Integer.parseInt(required(fields, key));
    }

    private ProductDetails details(Map<String, String> fields, boolean editing, String sku, User actor) {
        ProductCategory category;
        try {
            category = ProductCategory.valueOf(required(fields, "category"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Choose a supported electronics category");
        }
        if (category == ProductCategory.LEGACY && editing) {
            Product current = inventory.getStock(actor, sku).product();
            if (current.details().category() == ProductCategory.LEGACY) {
                return current.details();
            }
        }
        return ProductDetailsFactory.create(category, fields);
    }

    private String inventoryJson(User actor) {
        StringBuilder json = new StringBuilder("{\"company\":\"VoltEdge Electronics\",\"storage\":\"H2 JDBC\",\"readOnly\":false,\"products\":[");
        boolean first = true;
        for (StockSummary stock : inventory.listProducts(actor)) {
            if (!first) {
                json.append(',');
            }
            first = false;
            var product = stock.product();
            json.append("{\"sku\":").append(quote(product.sku())).append(",\"name\":").append(quote(product.name()))
                    .append(",\"quantity\":").append(stock.quantity()).append(",\"reorderLevel\":").append(product.reorderLevel())
                    .append(",\"active\":").append(product.active()).append(",\"lowStock\":").append(stock.isLowStock())
                    .append(",\"category\":").append(quote(product.category()))
                    .append(",\"categoryCode\":").append(quote(product.details().category().name()))
                    .append(",\"specifications\":{");
            json.append(product.details().attributes().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> quote(entry.getKey()) + ":" + quote(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(","))).append("}}");
        }
        return json.append("]}").toString();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                default -> {
                    if (character < 32) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self' 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private void respondResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        try (var input = LocalPreviewServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                respond(exchange, 404, "text/plain", "Asset not found");
                return;
            }
            byte[] data = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        }
    }
}
