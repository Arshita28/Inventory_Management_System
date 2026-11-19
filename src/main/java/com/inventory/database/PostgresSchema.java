package com.inventory.database;

import com.inventory.exception.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;

/** Transactional, checksummed migrations serialized across application startups. */
public final class PostgresSchema {
    private PostgresSchema() { }
    public static void initialize(Database database) {
        try(var c=database.connect()) {
            c.setAutoCommit(false);
            try {
                try(var s=c.createStatement()) {
                    s.execute("SELECT pg_advisory_xact_lock(846210013)");
                    s.execute("CREATE SCHEMA IF NOT EXISTS "+database.schema());
                    s.execute("REVOKE ALL ON SCHEMA "+database.schema()+" FROM PUBLIC");
                    s.execute("SET LOCAL search_path TO "+database.schema());
                    s.execute("CREATE TABLE IF NOT EXISTS inventory_migrations(version INTEGER PRIMARY KEY, checksum VARCHAR(64) NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                }
                apply(c,1,"/database/postgresql/V001__baseline.sql");
                apply(c,2,"/database/V006__issues.sql");
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }catch(Exception e){throw new PersistenceException("Unable to migrate PostgreSQL storage",e);}
    }
    private static void apply(Connection c,int version,String resource) throws Exception {
        String script;
        try(var in=PostgresSchema.class.getResourceAsStream(resource)){if(in==null)throw new IllegalStateException("Missing schema resource");script=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8)));
        try(var q=c.prepareStatement("SELECT checksum FROM inventory_migrations WHERE version=?")) {
            q.setInt(1,version);try(var r=q.executeQuery()){if(r.next()){if(!checksum.equals(r.getString(1)))throw new IllegalStateException("Applied migration checksum changed: "+version);return;}}
        }
        try(var statement=c.createStatement()){for(String sql:script.split(";"))if(!sql.isBlank())statement.execute(sql);}
        try(var q=c.prepareStatement("INSERT INTO inventory_migrations(version,checksum) VALUES(?,?)")){q.setInt(1,version);q.setString(2,checksum);q.executeUpdate();}
    }
}
