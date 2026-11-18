package com.inventory.database;

import com.inventory.exception.*;
import com.inventory.model.*;
import com.inventory.model.details.ChargingDetails;
import com.inventory.model.procurement.*;
import com.inventory.repository.jdbc.JdbcInventoryRepository;
import com.inventory.security.*;
import com.inventory.security.auth.*;
import com.inventory.service.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PostgresIntegrationTest {
    static EmbeddedPostgres postgres;
    Database db;
    InventoryService inventory;
    final Clock clock=Clock.systemUTC();
    final User manager=new User(UUID.randomUUID(),"manager",Role.MANAGER,true);
    final User reviewer=new User(UUID.randomUUID(),"reviewer",Role.MANAGER,true);
    final User staff=new User(UUID.randomUUID(),"staff",Role.STAFF,true);
    @TempDir Path temp;
    @BeforeAll static void start()throws Exception {postgres=EmbeddedPostgres.builder().setPort(0).start();}
    @AfterAll static void stop()throws Exception {if(postgres!=null)postgres.close();}
    @BeforeEach void setup()throws Exception {
        String name="test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=postgres.getPostgresDatabase().getConnection();var s=c.createStatement()){s.execute("CREATE DATABASE "+name);s.execute("ALTER USER postgres PASSWORD 'test-only-password'");}
        db=Database.fromEnvironment(Map.of("INVENTORY_JDBC_URL","jdbc:postgresql://localhost:"+postgres.getPort()+"/"+name+"?sslmode=disable","INVENTORY_DB_USER","postgres","INVENTORY_DB_PASSWORD","test-only-password"),"unused");
        inventory=new InventoryService(new JdbcInventoryRepository(db),new AuthorizationService(),clock);
    }
    @AfterEach void close(){if(db!=null)db.close();}
    void product(String sku,int quantity) {
        inventory.addProduct(manager,new Product(sku,"Charger "+sku,new BigDecimal("100"),2,true,new ChargingDetails("USB-C",20,"USB PD")));
        inventory.receiveStock(manager,UUID.randomUUID(),sku,quantity,"Opening stock");
    }
    @Test void schemaIsRepeatableAndAccountsPersistWithDuplicateProtection()throws Exception {
        PostgresSchema.initialize(db);
        var access=new AccessService(new JdbcAccountRepository(db),clock);
        String password="Postgres-test-password-123!";
        User admin=access.setup("owner",password.toCharArray());
        assertEquals(admin.id(),access.login("owner",password.toCharArray(),Role.ADMIN).id());
        assertThrows(IllegalArgumentException.class,()->access.create(admin,"owner",password.toCharArray(),Role.MANAGER));
        assertThrows(IllegalArgumentException.class,()->access.update(admin,admin.id(),Role.STAFF,true,new char[0]));
        try(var c=db.connect();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM inventory_migrations")){r.next();assertEquals(1,r.getInt(1));}
    }
    @Test void stockInvoicesReturnsCountsAndSupplierUpsertsWork() {
        product("A",10);product("B",10);var business=inventory.business();
        UUID id=UUID.randomUUID();var lines=List.of(new BusinessService.SaleLine("A",2),new BusinessService.SaleLine("B",1));
        business.checkout(staff,id,"Buyer",BigDecimal.TEN,lines);business.checkout(staff,id,"Buyer",BigDecimal.TEN,lines);
        assertEquals(8,inventory.getStock(manager,"A").quantity());assertEquals("290.00",business.invoices(staff).getFirst().get("total"));
        business.returnSale(manager,UUID.randomUUID(),id,1,1,"RESELLABLE","Unopened");assertEquals(9,inventory.getStock(manager,"A").quantity());
        UUID count=UUID.randomUUID();business.count(manager,count,"A",7,"Count");
        assertThrows(InventoryException.class,()->business.reviewCount(manager,count,true));business.reviewCount(reviewer,count,true);
        assertEquals(7,inventory.getStock(manager,"A").quantity());
        business.supplier(manager,"Supplier","First","contact@example.com","12345","Address");
        business.supplier(manager,"Supplier","Updated","other@example.com","12345","Address");
        assertEquals("Updated",business.suppliers(manager).getFirst().get("contact"));
        assertFalse(business.supportsSqlBackup());assertTrue(db.ready());
    }
    @Test void invoiceRollbackAndConcurrentSalesPreserveBalances()throws Exception {
        product("A",10);product("B",1);
        var business=inventory.business();
        assertThrows(InventoryException.class,()->business.checkout(staff,UUID.randomUUID(),"Buyer",BigDecimal.ZERO,List.of(new BusinessService.SaleLine("A",2),new BusinessService.SaleLine("B",2))));
        assertTrue(business.invoices(staff).isEmpty());assertEquals(10,inventory.getStock(manager,"A").quantity());
        try(var c=db.connect();var s=c.createStatement()){s.execute("ALTER TABLE stock_movements ADD CONSTRAINT injected_failure CHECK(quantity>0)");}
        assertThrows(PersistenceException.class,()->business.checkout(staff,UUID.randomUUID(),"Buyer",BigDecimal.ZERO,List.of(new BusinessService.SaleLine("A",2))));
        assertEquals(10,inventory.getStock(manager,"A").quantity());assertTrue(business.invoices(staff).isEmpty());
        try(var c=db.connect();var s=c.createStatement()){s.execute("ALTER TABLE stock_movements DROP CONSTRAINT injected_failure");}
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> sale=()->{try{inventory.sellStock(staff,UUID.randomUUID(),"A",7,"Concurrent sale");return true;}catch(InsufficientStockException e){return false;}};
            int success=0;for(var future:pool.invokeAll(List.of(sale,sale)))if(future.get())success++;
            assertEquals(1,success);assertEquals(3,inventory.getStock(manager,"A").quantity());
        }
    }
    @Test void purchasingApprovalsAndPartialReceiptsAreAtomic() {
        product("A",2);product("B",2);var ops=inventory.operations();UUID a=UUID.randomUUID(),b=UUID.randomUUID(),group=UUID.randomUUID();
        ops.addOffer(manager,new SupplierOffer(a,"A","Supplier",new BigDecimal("50"),2,1));ops.addOffer(manager,new SupplierOffer(b,"B","Supplier",new BigDecimal("60"),2,1));
        ops.createBatch(manager,group,List.of(a,b),List.of(4,3),"Restock");ops.transitionBatch(manager,group,OrderAction.SUBMIT,"Submit");
        assertThrows(InventoryException.class,()->ops.transitionBatch(manager,group,OrderAction.APPROVE,"Self"));ops.transitionBatch(reviewer,group,OrderAction.APPROVE,"Review");
        var order=ops.snapshot(manager).orders().stream().filter(o->o.sku().equals("A")).findFirst().orElseThrow();
        UUID receipt=UUID.randomUUID();ops.receive(manager,order.id(),receipt,2,"Partial delivery");ops.receive(manager,order.id(),receipt,2,"Partial delivery");
        assertEquals(4,inventory.getStock(manager,"A").quantity());assertEquals(OrderStatus.PARTIALLY_RECEIVED,ops.snapshot(manager).orders().stream().filter(o->o.id().equals(order.id())).findFirst().orElseThrow().status());
    }
    @Test void failedImportRollsBackDestinationAndMigrationChecksumsAreEnforced()throws Exception {
        Path path=temp.resolve("invalid-source");String url="jdbc:h2:file:"+path;
        var source=new InventoryService(new JdbcInventoryRepository(url),new AuthorizationService(),clock);
        source.addProduct(manager,new Product("BAD","Count mismatch",new BigDecimal("10"),1,true,new ChargingDetails("USB-C",20,"USB PD")));
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()){q.execute("UPDATE products SET quantity=3 WHERE sku='BAD'");}
        new JdbcAccountRepository(url);
        assertThrows(SQLException.class,()->H2Importer.transfer(path,db));
        assertTrue(inventory.listProducts(manager).isEmpty());assertEquals(3,source.getStock(manager,"BAD").quantity());
        try(var c=db.connect();var s=c.createStatement()){s.execute("UPDATE inventory_migrations SET checksum='changed' WHERE version=1");}
        assertThrows(PersistenceException.class,()->PostgresSchema.initialize(db));
    }
    @Test void h2ImportPreservesAccountsInvoicesIdsAndSequencesAndRefusesNonemptyTarget()throws Exception {
        Path path=temp.resolve("source");String url="jdbc:h2:file:"+path;
        var local=new InventoryService(new JdbcInventoryRepository(url),new AuthorizationService(),clock);
        local.addProduct(manager,new Product("LOCAL","Local charger",new BigDecimal("100"),2,true,new ChargingDetails("USB-C",20,"USB PD")));
        local.receiveStock(manager,UUID.randomUUID(),"LOCAL",10,"Opening");UUID invoice=UUID.randomUUID();
        local.business().checkout(staff,invoice,"Original customer",BigDecimal.ZERO,List.of(new BusinessService.SaleLine("LOCAL",2)));
        var accounts=new AccessService(new JdbcAccountRepository(url),clock);String password="Migration-password-123!";var admin=accounts.setup("owner",password.toCharArray());
        var counts=H2Importer.transfer(path,db);assertEquals(1L,counts.get("invoices"));assertEquals(8,inventory.getStock(manager,"LOCAL").quantity());
        assertEquals(admin.id(),new AccessService(new JdbcAccountRepository(db),clock).login("owner",password.toCharArray(),Role.ADMIN).id());
        inventory.business().checkout(staff,UUID.randomUUID(),"Next customer",BigDecimal.ZERO,List.of(new BusinessService.SaleLine("LOCAL",1)));
        assertEquals("2",inventory.business().invoices(staff).getFirst().get("invoice_number"));
        assertThrows(IllegalArgumentException.class,()->H2Importer.transfer(path,db));assertEquals(8,local.getStock(manager,"LOCAL").quantity());
    }
}
