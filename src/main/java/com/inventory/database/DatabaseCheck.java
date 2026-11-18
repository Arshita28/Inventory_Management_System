package com.inventory.database;

/** Credential/TLS probe only: does not create schemas, tables or business records. */
public final class DatabaseCheck {
    private DatabaseCheck() { }
    public static void main(String[] args) {
        try(var database=Database.fromEnvironment(System.getenv(),"data/inventory");var connection=database.connect();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT 1")) {
            if(!rows.next())throw new IllegalStateException("Database did not respond");
            // pg_stat_ssl describes the pooler-to-database leg, not the JDBC TLS connection.
            if(!database.jdbcUrl().contains("sslmode=verify-full"))throw new IllegalStateException("Verified TLS is required");
            System.out.println("PostgreSQL connection verified with TLS. No business data changed.");
        }catch(Exception exception) {
            Throwable cause=exception;
            while(cause.getCause()!=null)cause=cause.getCause();
            String detail=java.util.Objects.toString(cause.getMessage(),cause.getClass().getSimpleName());
            String secret=System.getenv("INVENTORY_DB_PASSWORD");
            if(secret!=null && !secret.isEmpty())detail=detail.replace(secret,"[redacted]");
            detail=detail.replaceAll("(?i)(password|user)=([^ &;]+)","$1=[redacted]");
            System.err.println("Database connection failed: "+detail);
            System.exit(1);
        }
    }
}
