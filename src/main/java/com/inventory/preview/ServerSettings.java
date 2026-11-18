package com.inventory.preview;

import java.net.URI;
import java.util.Map;

/** Explicit HTTPS origin, without trusting client-supplied proxy headers. */
public record ServerSettings(String bindAddress,String publicOrigin) {
    public static ServerSettings local() {return new ServerSettings("127.0.0.1","");}
    public ServerSettings {
        if(!bindAddress.equals("127.0.0.1") && !bindAddress.equals("0.0.0.0"))throw new IllegalArgumentException("Bind address must be 127.0.0.1 or 0.0.0.0");
        if(!publicOrigin.isEmpty()) {
            URI uri=URI.create(publicOrigin);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !uri.getRawPath().isEmpty())throw new IllegalArgumentException("INVENTORY_PUBLIC_ORIGIN must be an HTTPS origin without a path");
        }else if(bindAddress.equals("0.0.0.0"))throw new IllegalArgumentException("Network binding requires INVENTORY_PUBLIC_ORIGIN");
    }
    public static ServerSettings fromEnvironment(Map<String,String> env) {
        String origin=env.getOrDefault("INVENTORY_PUBLIC_ORIGIN","").strip();
        if(env.getOrDefault("INVENTORY_ENV","").equals("production") && origin.isEmpty())throw new IllegalArgumentException("Production requires INVENTORY_PUBLIC_ORIGIN");
        return new ServerSettings(env.getOrDefault("INVENTORY_BIND_ADDRESS","127.0.0.1"),origin);
    }
    public boolean secureCookies() {return !publicOrigin.isEmpty();}
    public boolean acceptsHost(String host,int port) {return secureCookies()?URI.create(publicOrigin).getRawAuthority().equalsIgnoreCase(host):("localhost:"+port).equals(host)||("127.0.0.1:"+port).equals(host);}
    public boolean acceptsOrigin(String origin,int port) {return origin==null || (secureCookies()?publicOrigin.equals(origin):origin.equals("http://localhost:"+port)||origin.equals("http://127.0.0.1:"+port));}
    public String cookieFlags() {return secureCookies()?"; Secure":"";}
}
