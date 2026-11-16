package com.inventory.preview;

import com.inventory.security.*;
import com.inventory.security.auth.*;
import com.inventory.repository.memory.InMemoryInventoryRepository;
import com.inventory.service.InventoryService;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedServerTest {
    private static final String PASSWORD = "Only synthetic test credentials!";
    private AccessService access;
    private LocalPreviewServer server;
    private Browser browser;
    private User admin;
    @BeforeEach void start() throws Exception {
        access = new AccessService(new JdbcAccountRepository("jdbc:h2:mem:server-auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"), Clock.systemUTC());
        admin = access.setup("owner", PASSWORD.toCharArray());
        var inventory = new InventoryService(new InMemoryInventoryRepository(), new AuthorizationService(), Clock.systemUTC());
        DemoData.seed(inventory);
        server = new LocalPreviewServer(inventory, access, 0); server.start();
        browser = new Browser();
    }
    @AfterEach void close() { browser.client.close(); server.close(); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + server.port() + path); }
    private final class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> post(String path, Map<String,String> fields) throws Exception {
            String body = fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
            return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> login(String username, String password, String role) throws Exception {
            String csrf = token(get("/?login=1").body());
            return post("/auth/login", Map.of("csrf",csrf,"username",username,"password",password,"role",role));
        }
    }
    private String token(String html) {
        var matcher = Pattern.compile("name='csrf' value='([^']+)'").matcher(html);
        assertTrue(matcher.find()); return matcher.group(1);
    }
    @Test void protectedPagesApisExportsAndWritesRejectAnonymousCallers() throws Exception {
        assertEquals(303,browser.get("/app").statusCode());
        assertEquals(401,browser.get("/api/inventory").statusCode());
        assertEquals(401,browser.get("/export/inventory.csv").statusCode());
        assertEquals(303,browser.get("/?sku=VE-CHG-001").statusCode());
        assertEquals(303,browser.post("/stock/receive",Map.of()).statusCode());
        var spoof = browser.client.send(HttpRequest.newBuilder(uri("/api/inventory")).header("Cookie","demo_actor=manager").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(401,spoof.statusCode());
    }
    @Test void loginRotatesSessionAndCsrfAndLogoutRevokesAccess() throws Exception {
        String old = token(browser.get("/?login=1").body());
        var login = browser.post("/auth/login",Map.of("csrf",old,"username","owner","password",PASSWORD,"role","ADMIN"));
        assertEquals(303,login.statusCode());
        assertTrue(login.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly; SameSite=Strict"));
        String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        String page = browser.get("/app").body();
        String csrf = token(page);
        assertNotEquals(old,csrf);
        assertTrue(page.contains("People &amp; access"));
        assertTrue(page.contains("action='/auth/logout'"));
        assertFalse(page.contains("name='actor'"));
        assertEquals(403,browser.post("/auth/logout",Map.of("csrf",old)).statusCode());
        assertEquals(303,browser.post("/auth/logout",Map.of("csrf",csrf)).statusCode());
        assertEquals(401,browser.get("/api/inventory").statusCode());
        try (var replayClient = HttpClient.newHttpClient()) {
            var replay = replayClient.send(HttpRequest.newBuilder(uri("/api/inventory")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,replay.statusCode());
        }
    }
    @Test void roleSelectionDoesNotGrantAdminAndPasswordsAreNotEchoed() throws Exception {
        access.create(admin,"seller",PASSWORD.toCharArray(),Role.STAFF);
        var invalid = browser.login("seller",PASSWORD,"ADMIN");
        assertTrue(invalid.body().contains(AccessService.LOGIN_ERROR));
        assertFalse(invalid.body().contains(PASSWORD));
        assertEquals(303,browser.get("/app").statusCode());
        assertEquals(303,browser.login("seller",PASSWORD,"STAFF").statusCode());
        String page=browser.get("/app").body();
        assertFalse(page.contains("data-nav='users'"));
        assertFalse(page.contains("data-nav='analytics'"));
        assertFalse(page.contains("href='#add-product'"));
        assertFalse(page.contains("Review stock alerts"));
        assertFalse(page.contains("action='/stock/receive'"));
        assertTrue(page.contains("action='/stock/sell'"));
        assertEquals(403,browser.post("/users/create",Map.of("csrf",token(page),"username","intruder","password",PASSWORD,"role","ADMIN")).statusCode());
    }
    @Test void sessionCsrfCannotBeUsedByAnotherBrowser() throws Exception {
        browser.login("owner",PASSWORD,"ADMIN");
        String csrf = token(browser.get("/app").body());
        try (var client=HttpClient.newHttpClient()) {
            var response=client.send(HttpRequest.newBuilder(uri("/auth/login")).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("csrf="+csrf+"&username=owner&role=ADMIN&password=test")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,response.statusCode());
        }
    }
    @Test void disabledAccountImmediatelyLosesHttpAccess() throws Exception {
        var staff=access.create(admin,"seller",PASSWORD.toCharArray(),Role.STAFF);
        browser.login("seller",PASSWORD,"STAFF");
        assertEquals(200,browser.get("/api/inventory").statusCode());
        access.update(admin,staff.id(),Role.STAFF,false,new char[0]);
        assertEquals(401,browser.get("/api/inventory").statusCode());
    }
    @Test void adminCanCreateAccountsAndManagerCannotManageThem() throws Exception {
        browser.login("owner",PASSWORD,"ADMIN");
        String csrf=token(browser.get("/app").body());
        assertEquals(303,browser.post("/users/create",Map.of("csrf",csrf,"username","manager","password",PASSWORD,"role","MANAGER")).statusCode());
        browser.post("/auth/logout",Map.of("csrf",csrf));
        browser.login("manager",PASSWORD,"MANAGER");
        String page=browser.get("/app").body();
        assertTrue(page.contains("data-nav='analytics'"));
        assertFalse(page.contains("data-nav='users'"));
    }
    @Test void firstAdminCanBeCreatedThroughThePublicSetupForm() throws Exception {
        server.close();
        access = new AccessService(new JdbcAccountRepository("jdbc:h2:mem:fresh-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"), Clock.systemUTC());
        var inventory = new InventoryService(new InMemoryInventoryRepository(), new AuthorizationService(), Clock.systemUTC());
        server = new LocalPreviewServer(inventory, access, 0); server.start();
        String page = browser.get("/?login=1").body();
        assertTrue(page.contains("Create Admin &amp; log in"));
        assertEquals(303, browser.post("/auth/setup", Map.of("csrf",token(page),"username","newowner","password",PASSWORD,"role","ADMIN")).statusCode());
        assertFalse(access.needsSetup());
        assertTrue(browser.get("/app").body().contains("People &amp; access"));
    }
    @Test void crossOriginLoginIsRejectedEvenWithTheRightToken() throws Exception {
        String csrf = token(browser.get("/?login=1").body());
        var request = HttpRequest.newBuilder(uri("/auth/login")).header("Content-Type","application/x-www-form-urlencoded")
                .header("Origin","https://example.com").POST(HttpRequest.BodyPublishers.ofString("csrf="+csrf+"&username=owner&role=ADMIN&password=test")).build();
        assertEquals(403,browser.client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void demoPersonaPageFillsOrdinaryCredentialsAndStillRequiresLogin() throws Exception {
        server.close();
        access.installDemoPersonas();
        var inventory = new InventoryService(new InMemoryInventoryRepository(), new AuthorizationService(), Clock.systemUTC());
        server = new LocalPreviewServer(inventory, access, 0, true); server.start();
        String page = browser.get("/?login=1").body();
        assertEquals(6, page.split("class='persona-card'", -1).length - 1);
        assertTrue(page.contains("data-persona-username='demo.admin.maya'"));
        assertTrue(page.contains("data-persona-role='MANAGER'"));
        assertEquals(303, browser.get("/app").statusCode());
        for (var persona : DemoPersonas.ALL) {
            assertEquals(303, browser.login(persona.username(), persona.password(), persona.role().name()).statusCode());
            String workspace = browser.get("/app").body();
            assertEquals(persona.role() == Role.ADMIN, workspace.contains("data-nav='users'"));
            assertEquals(persona.role() != Role.STAFF, workspace.contains("data-nav='analytics'"));
            assertEquals(303, browser.post("/auth/logout", Map.of("csrf", token(workspace))).statusCode());
        }
    }
    @Test void publicSetupIsClosedOnceFirstAdminExists() throws Exception {
        String csrf=token(browser.get("/?login=1").body());
        var result=browser.post("/auth/setup",Map.of("csrf",csrf,"username","attacker","password",PASSWORD,"role","ADMIN"));
        assertEquals(200,result.statusCode());
        assertTrue(result.body().contains("Admin setup is already complete"));
        assertEquals(303,browser.get("/app").statusCode());
    }
}
