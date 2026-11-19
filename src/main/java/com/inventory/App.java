package com.inventory;

import com.inventory.exception.AccessDeniedException;
import com.inventory.exception.InsufficientStockException;
import com.inventory.model.Product;
import com.inventory.model.details.ChargingDetails;
import com.inventory.repository.memory.InMemoryInventoryRepository;
import com.inventory.repository.jdbc.JdbcInventoryRepository;
import com.inventory.preview.DemoData;
import com.inventory.preview.LocalPreviewServer;
import com.inventory.security.AuthorizationService;
import com.inventory.security.Role;
import com.inventory.security.User;
import com.inventory.security.auth.AccessService;
import com.inventory.security.auth.JdbcAccountRepository;
import com.inventory.service.InventoryService;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/** Executable foundation demo; actors are fixtures, not an authentication system. */
public final class App {
    private App() { }

    public static void main(String[] args) throws Exception {
        if(Arrays.stream(args).anyMatch(a->a.startsWith("--migrate-h2="))) {
            String source=Arrays.stream(args).filter(a->a.startsWith("--migrate-h2=")).findFirst().orElseThrow().substring(13);
            try(var db=com.inventory.database.Database.fromEnvironment(System.getenv(),"data/inventory")) {
                var counts=com.inventory.database.H2Importer.transfer(Path.of(source),db);
                counts.forEach((table,count)->System.out.println(table+": "+count+" rows imported"));
                System.out.println("Migration committed. Source H2 file was not changed.");
            }
            return;
        }
        if (Arrays.stream(args).anyMatch(a->a.startsWith("--restore="))) {
            String script=Arrays.stream(args).filter(a->a.startsWith("--restore=")).findFirst().orElseThrow().substring(10);
            String destination=Arrays.stream(args).filter(a->a.startsWith("--database=")).findFirst().orElseThrow(()->new IllegalArgumentException("Choose a new database using --database=data/restored")).substring(11);
            Path db=Path.of(destination).toAbsolutePath().normalize();
            if(destination.contains(";") || Files.exists(Path.of(db+".mv.db"))) throw new IllegalArgumentException("Restore requires a new database path; existing data will not be overwritten");
            Files.createDirectories(db.getParent());
            // Backups are SQL programs. Only restore files downloaded from this installation.
            try(var connection=java.sql.DriverManager.getConnection("jdbc:h2:file:"+db,"sa","");var reader=Files.newBufferedReader(Path.of(script))) {
                org.h2.tools.RunScript.execute(connection,reader);
            }
            System.out.println("Backup restored to "+db+". Start with --serve --database="+destination);
            return;
        }
        if (Arrays.asList(args).contains("--serve")) {
            serve(args);
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("Usage: java -jar inventory.jar [--serve [--seed-demo] [--demo-personas] [--port=8080]]");
        }
        InventoryService inventory = new InventoryService(new InMemoryInventoryRepository(),
                new AuthorizationService(), Clock.systemUTC());
        User manager = new User(UUID.randomUUID(), "demo-manager", Role.MANAGER, true);
        User cashier = new User(UUID.randomUUID(), "demo-cashier", Role.STAFF, true);

        System.out.println("Smart Inventory — Java foundation demo (in-memory, no login)");
        System.out.println("Company: VoltEdge Electronics");
        inventory.addProduct(manager, new Product("VE-CHG-DEMO", "65W USB-C fast charger",
                new BigDecimal("1499.00"), 5, true, new ChargingDetails("USB-C", 65, "USB Power Delivery devices")));
        inventory.receiveStock(manager, UUID.randomUUID(), "VE-CHG-DEMO", 10, "Opening stock");
        UUID saleRequest = UUID.randomUUID();
        inventory.sellStock(cashier, saleRequest, "VE-CHG-DEMO", 6, "Demo sale");
        inventory.sellStock(cashier, saleRequest, "VE-CHG-DEMO", 6, "Demo sale");
        System.out.println("Received 10, sold 6, retried same sale: "
                + inventory.getStock(manager, "VE-CHG-DEMO").quantity() + " units remain.");
        System.out.println("Low-stock products: " + inventory.lowStock(manager).size());

        try {
            inventory.receiveStock(cashier, UUID.randomUUID(), "VE-CHG-DEMO", 5, "Unauthorized receipt");
        } catch (AccessDeniedException exception) {
            System.out.println("Cashier receipt blocked: " + exception.getMessage());
        }
        try {
            inventory.sellStock(cashier, UUID.randomUUID(), "VE-CHG-DEMO", 5, "Oversale attempt");
        } catch (InsufficientStockException exception) {
            System.out.println("Overselling blocked: " + exception.getMessage());
        }
        inventory.history(manager, "VE-CHG-DEMO").forEach(movement ->
                System.out.printf("%s %+d -> balance %d (%s)%n", movement.type(),
                        movement.quantity(), movement.balanceAfter(), movement.reason()));
        System.out.println("Data is temporary and resets when the process exits.");
    }

    private static void serve(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT","8080"));
        String database="data/inventory";
        for (String argument : args) {
            if(argument.startsWith("--database=")) {
                database=argument.substring(11);
                if(database.isBlank() || database.contains(";")) throw new IllegalArgumentException("Choose a valid database path");
            } else if (argument.startsWith("--port=")) {
                port = Integer.parseInt(argument.substring("--port=".length()));
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("Port must be between 1 and 65535");
                }
            } else if (!argument.equals("--serve") && !argument.equals("--seed-demo") && !argument.equals("--demo-personas")) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            }
        }
        if(port<1 || port>65535)throw new IllegalArgumentException("PORT must be between 1 and 65535");
        var settings=com.inventory.preview.ServerSettings.fromEnvironment(System.getenv());
        var db=com.inventory.database.Database.fromEnvironment(System.getenv(),database);
        boolean production="production".equals(System.getenv("INVENTORY_ENV"));
        /*if(production && (Arrays.asList(args).contains("--seed-demo") || Arrays.asList(args).contains("--demo-personas"))) {
            db.close();throw new IllegalArgumentException("Demo accounts and sample stock are disabled in production");
        }*/
        if(!db.postgres())Files.createDirectories(Path.of(database).toAbsolutePath().getParent());
        InventoryService inventory = new InventoryService(
                new JdbcInventoryRepository(db),
                new AuthorizationService(), Clock.systemUTC());
        if (Arrays.asList(args).contains("--seed-demo")) {
            DemoData.seed(inventory);
        }
        AccessService access = new AccessService(new JdbcAccountRepository(db), Clock.systemUTC());
        boolean demoPersonas = Arrays.asList(args).contains("--demo-personas");
        if (demoPersonas || production && access.needsSetup()) access.installDemoPersonas();
        if(production && access.needsSetup()) {db.close();throw new IllegalArgumentException("Create the first Admin using a local PostgreSQL-connected session before starting production");}
        LocalPreviewServer preview = settings.secureCookies()?new LocalPreviewServer(inventory,access,port,settings):new LocalPreviewServer(inventory, access, port, demoPersonas);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{preview.close();db.close();}));
        preview.start();
        System.out.println("Inventory preview: http://localhost:" + preview.port());
        System.out.println("Authenticated workspace; storage: "+db.label());
        new CountDownLatch(1).await();
    }
}
