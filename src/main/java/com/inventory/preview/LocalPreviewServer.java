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
import com.inventory.security.Role;
import com.inventory.security.auth.AccessService;
import com.inventory.security.auth.SessionManager;
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

/** Loopback HTTP adapter. The application entry point requires authenticated sessions. */
public final class LocalPreviewServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LocalPreviewServer.class.getName());
    private static final int MAX_FORM_BYTES = 16_384;
    private static final Set<String> WRITE_ROUTES = Set.of("/products/create", "/products/update",
            "/products/deactivate", "/stock/receive", "/stock/sell", "/purchase/create", "/purchase/action",
            "/purchase/receive", "/suppliers/offers/create", "/demo/actor", "/auth/logout", "/users/create", "/users/update");
    private final AccessService access;
    private final LoginPage loginPage;
    private final boolean personasEnabled;
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
        this(inventory, viewer, port, demoSwitch, null, false);
    }

    /** Authenticated runtime; the older actor constructors are test fixtures only. */
    public LocalPreviewServer(InventoryService inventory, AccessService access, int port) throws IOException {
        this(inventory, access, port, false);
    }

    public LocalPreviewServer(InventoryService inventory, AccessService access, int port, boolean personasEnabled) throws IOException {
        this(inventory, null, port, false, java.util.Objects.requireNonNull(access), personasEnabled);
    }

    private LocalPreviewServer(InventoryService inventory, User viewer, int port, boolean demoSwitch, AccessService access, boolean personasEnabled) throws IOException {
        this.personasEnabled = personasEnabled;
        this.access = access;
        this.loginPage = new LoginPage();
        this.inventory = inventory;
        this.viewer = viewer;
        this.operations = inventory.operations();
        this.demoSwitch = demoSwitch;
        page = new InventoryPage(inventory, access);
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
            // This installation serves only the explicit loopback origin.
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("localhost:" + port()).equals(host) && !("127.0.0.1:" + port()).equals(host)) {
                respond(exchange, 403, "text/plain", "Use the localhost preview address.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            User actor = currentActor(exchange);
            if (access != null && exchange.getRequestMethod().equals("POST") && (path.equals("/auth/login") || path.equals("/auth/setup"))) {
                authPost(exchange, path);
                return;
            }
            boolean inventoryQuery = path.equals("/") && parseFields(exchange.getRequestURI().getRawQuery()).keySet().stream()
                    .anyMatch(key -> Set.of("sku", "q", "scope", "category", "status", "from", "to", "order", "newCategory").contains(key));
            if (access != null && actor == null && (path.equals("/app") || path.equals("/api/inventory")
                    || path.equals("/export/inventory.csv") || WRITE_ROUTES.contains(path) || inventoryQuery)) {
                if (path.startsWith("/api/") || path.startsWith("/export/")) respond(exchange, 401, "text/plain", "Login required.");
                else redirect(exchange, "/?login=1#login");
                return;
            }
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
                case "/" -> {
                    if (!inventoryQuery) landing(exchange, parseFields(exchange.getRequestURI().getRawQuery()), "", "", "ADMIN", false);
                    else respond(exchange, 200, "text/html", page.render(actor,
                        parseFields(exchange.getRequestURI().getRawQuery()), Map.of(), "", "", requestCsrf(exchange), demoSwitch));
                }
                case "/app" -> respond(exchange, 200, "text/html", page.render(actor,
                        parseFields(exchange.getRequestURI().getRawQuery()), Map.of(), "", "", requestCsrf(exchange), demoSwitch));
                case "/health" -> respond(exchange, 200, "text/plain", "ok");
                case "/assets/landing.css" -> respondResource(exchange, "/assets/landing.css", "text/css; charset=utf-8");
                case "/assets/landing.js" -> respondResource(exchange, "/assets/landing.js", "text/javascript; charset=utf-8");
                case "/assets/landing-final.jpg" -> respondResource(exchange, "/assets/landing-final.jpg", "image/jpeg");
                case "/assets/landing-intro.mp4" -> VideoResource.respond(exchange);
                case "/assets/workspace.css" -> respondResource(exchange, "/assets/workspace.css", "text/css; charset=utf-8");
                case "/assets/workspace.js" -> respondResource(exchange, "/assets/workspace.js", "text/javascript; charset=utf-8");
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
        if ((access != null && requestCsrf(exchange).isEmpty()) || !MessageDigest.isEqual(requestCsrf(exchange).getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8))) {
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
            if (path.equals("/auth/logout") && access != null) {
                access.sessions().invalidate(sessionCookie(exchange));
                exchange.getResponseHeaders().add("Set-Cookie", "inventory_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                redirect(exchange, "/?login=1&loggedout=1#login");
                return;
            }
            if (path.startsWith("/users/") && access != null) {
                char[] password = fields.getOrDefault("password", "").toCharArray();
                try {
                    Role role = Role.valueOf(required(fields, "role"));
                    if (path.equals("/users/create")) access.create(actor, required(fields, "username"), password, role);
                    else {
                        String active = required(fields, "active");
                        if (!Set.of("true", "false").contains(active)) throw new IllegalArgumentException("Choose an account status.");
                        access.update(actor, UUID.fromString(required(fields, "userId")), role, Boolean.parseBoolean(active), password);
                    }
                } finally { java.util.Arrays.fill(password, '\0'); }
                redirect(exchange, "/app?notice=users#users");
                return;
            }
            if (path.equals("/demo/actor")) {
                if (!demoSwitch) throw new AccessDeniedException("Demo identity switching is disabled");
                String identity = required(fields, "actor");
                if (!Set.of("manager", "approver", "cashier").contains(identity)) throw new IllegalArgumentException("Unknown demo identity");
                exchange.getResponseHeaders().set("Set-Cookie", "demo_actor=" + identity + "; Path=/; SameSite=Strict; HttpOnly");
                redirect(exchange, "/app#purchase-orders");
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
            exchange.getResponseHeaders().set("Location", "/app?sku=" + InventoryPage.url(Product.normalizeSku(sku)) + "&notice=" + notice);
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
        respond(exchange, status, "text/html", page.render(actor, query, fields, action, message, requestCsrf(exchange), demoSwitch));
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
        if (access != null) {
            var session = access.sessions().find(sessionCookie(exchange));
            if (session.isEmpty() || session.get().userId() == null) return null;
            User user = access.find(session.get().userId()).orElse(null);
            if (user == null || !user.active()) {
                access.sessions().invalidate(session.get().token());
                return null;
            }
            return user;
        }
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

    private String sessionCookie(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length == 2 && pair[0].equals("inventory_session")) {
                String value = pair[1];
                // Java HTTP clients can use RFC 2109 quoted cookie values.
                return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                        ? value.substring(1, value.length() - 1) : value;
            }
        }
        return "";
    }

    private String requestCsrf(HttpExchange exchange) {
        if (access == null) return csrf;
        return access.sessions().find(sessionCookie(exchange)).map(SessionManager.Session::csrf).orElse("");
    }

    private void setSession(HttpExchange exchange, SessionManager.Session session) {
        exchange.getResponseHeaders().add("Set-Cookie", "inventory_session=" + session.token() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
    }

    private void landing(HttpExchange exchange, Map<String, String> query, String error, String username, String role, boolean forceOpen) throws IOException {
        String token = csrf;
        if (access != null) {
            var session = access.sessions().find(sessionCookie(exchange)).orElse(null);
            if (session == null) { session = access.sessions().create(null); setSession(exchange, session); }
            token = session.csrf();
        }
        respond(exchange, 200, "text/html", loginPage.render(token, access != null && access.needsSetup(),
                forceOpen || query.containsKey("login"), error, username, role, query.containsKey("loggedout"),
                personasEnabled ? access.demoPersonas() : java.util.List.of()));
    }

    private void authPost(HttpExchange exchange, String path) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals("http://localhost:" + port()) && !origin.equals("http://127.0.0.1:" + port())) {
            respond(exchange, 403, "text/plain", "Cross-origin forms are not accepted."); return;
        }
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/x-www-form-urlencoded")) {
            respond(exchange, 415, "text/plain", "Submit a URL-encoded form."); return;
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_FORM_BYTES + 1);
        if (bytes.length > MAX_FORM_BYTES) { respond(exchange, 413, "text/plain", "Form is too large."); return; }
        Map<String, String> fields = parseFields(new String(bytes, StandardCharsets.UTF_8));
        String expected = requestCsrf(exchange);
        if (expected.isEmpty() || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), fields.getOrDefault("csrf", "").getBytes(StandardCharsets.UTF_8))) {
            respond(exchange, 403, "text/plain", "Form expired. Reload the login page."); return;
        }
        char[] password = fields.getOrDefault("password", "").toCharArray();
        try {
            Role role = Role.valueOf(fields.getOrDefault("role", ""));
            User user;
            if (path.equals("/auth/setup")) {
                if (role != Role.ADMIN || !access.needsSetup()) throw new IllegalArgumentException("Admin setup is already complete. Please log in.");
                user = access.setup(required(fields, "username"), password);
            } else user = access.login(fields.getOrDefault("username", ""), password, role);
            access.sessions().invalidate(sessionCookie(exchange));
            setSession(exchange, access.sessions().create(user.id()));
            redirect(exchange, "/app#home");
        } catch (AccessDeniedException exception) {
            landing(exchange, Map.of(), AccessService.LOGIN_ERROR, fields.getOrDefault("username", ""), fields.getOrDefault("role", "ADMIN"), true);
        } catch (IllegalArgumentException exception) {
            landing(exchange, Map.of(), path.equals("/auth/setup") ? exception.getMessage() : AccessService.LOGIN_ERROR,
                    fields.getOrDefault("username", ""), fields.getOrDefault("role", "ADMIN"), true);
        } finally { java.util.Arrays.fill(password, '\0'); }
    }

    private String operationalPost(String path, Map<String, String> fields, User actor) {
        if (path.equals("/suppliers/offers/create")) {
            operations.addOffer(actor, new SupplierOffer(UUID.fromString(required(fields, "requestId")), required(fields, "sku"),
                    required(fields, "supplierName"), new BigDecimal(required(fields, "unitCost")), integer(fields, "leadDays"), integer(fields, "minimumQuantity")));
            return "/app?notice=quote#supplier-quotes";
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
        return "/app?order=" + orderId + "&notice=purchase#purchase-orders";
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
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; media-src 'self'; img-src 'self'; style-src 'self' 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
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
            if (contentType.startsWith("text/html")) exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'self'; media-src 'self'; img-src 'self'; style-src 'self' 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        }
    }
}
