package com.inventory.database;

import com.inventory.preview.ServerSettings;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigurationTest {
    @Test void productionNeverFallsBackToLocalStorage() {
        assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(Map.of("INVENTORY_ENV","production"),"data/inventory"));
        try(var database=Database.fromEnvironment(Map.of(),"data/inventory")){assertFalse(database.postgres());assertEquals("H2",database.label());}
    }
    @Test void cloudConnectionsRequireCredentialsAndVerifiedTls() {
        for(String url:java.util.List.of("jdbc:postgresql://db.example.com/inventory","jdbc:postgresql://db.example.com/inventory?sslmode=require","jdbc:postgresql://db.example.com/inventory?sslmode=disable","jdbc:mysql://localhost/inventory"))
            assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(Map.of("INVENTORY_JDBC_URL",url),"unused"));
        assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(Map.of("INVENTORY_JDBC_URL","jdbc:postgresql://db.example.com/inventory?sslmode=verify-full&password=secret"),"unused"));
        assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(Map.of("INVENTORY_JDBC_URL","jdbc:postgresql://db.example.com/inventory?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory"),"unused"));
    }
    @Test void hostedOriginIsExplicitAndCookiesAreSecure() {
        var settings=new ServerSettings("0.0.0.0","https://inventory.example.com");
        assertTrue(settings.acceptsHost("inventory.example.com",8080));assertFalse(settings.acceptsHost("attacker.example",8080));
        assertTrue(settings.acceptsOrigin("https://inventory.example.com",8080));assertFalse(settings.acceptsOrigin("http://inventory.example.com",8080));
        assertTrue(settings.cookieFlags().contains("Secure"));
        assertThrows(IllegalArgumentException.class,()->new ServerSettings("0.0.0.0",""));
        assertThrows(IllegalArgumentException.class,()->new ServerSettings("0.0.0.0","http://inventory.example.com"));
        assertFalse(ServerSettings.local().secureCookies());
    }
}
