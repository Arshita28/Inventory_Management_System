package com.inventory.preview;

import com.inventory.repository.jdbc.JdbcInventoryRepository;
import com.inventory.security.AuthorizationService;
import com.inventory.security.auth.*;
import com.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;

class HostedServerTest {
    @TempDir Path temporary;
    @Test void httpsOriginUsesSecureCookiesAndRejectsUnrecognizedHosts()throws Exception {
        String url="jdbc:h2:file:"+temporary.resolve("inventory");
        var inventory=new InventoryService(new JdbcInventoryRepository(url),new AuthorizationService(),Clock.systemUTC());
        var access=new AccessService(new JdbcAccountRepository(url),Clock.systemUTC());
        try(var server=new LocalPreviewServer(inventory,access,0,new ServerSettings("127.0.0.1","https://inventory.example.com"))) {
            server.start();
            String allowed=request(server.port(),"inventory.example.com","/?login=1");
            assertTrue(allowed.startsWith("HTTP/1.1 200"));
            assertTrue(allowed.toLowerCase().contains("set-cookie:"));assertTrue(allowed.contains("; Secure"));
            assertTrue(request(server.port(),"attacker.example.com","/app").startsWith("HTTP/1.1 403"));
            assertTrue(request(server.port(),"inventory.example.com","/ready").startsWith("HTTP/1.1 200"));
        }
    }
    private String request(int port,String host,String path)throws Exception {
        try(var socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
