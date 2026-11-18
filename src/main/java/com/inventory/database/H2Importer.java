package com.inventory.database;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Explicit one-time transfer into an empty PostgreSQL database. Never modifies H2 data. */
public final class H2Importer {
    private static final List<String> TABLES=List.of("products","stock_movements","supplier_offers","purchase_orders","purchase_receipts","purchase_events","app_users","access_events","invoices","invoice_lines","sales_returns","stock_counts","product_identifiers","serial_units","supplier_contacts","invoice_serials");
    private static final Map<String,String> IDENTITIES=Map.of("stock_movements","movement_sequence","purchase_events","event_sequence","access_events","event_sequence","invoices","invoice_number");
    private H2Importer() { }
    public static Map<String,Long> transfer(Path sourcePath,Database target) throws SQLException {
        if(!target.postgres()) throw new IllegalArgumentException("Migration destination must be PostgreSQL");
        Path path=sourcePath.toAbsolutePath().normalize();
        if(path.toString().contains(";") || !Files.isRegularFile(Path.of(path+".mv.db"))) throw new IllegalArgumentException("Choose an existing H2 database path without the .mv.db suffix");
        PostgresSchema.initialize(target);
        try(var source=DriverManager.getConnection("jdbc:h2:file:"+path+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r","sa","");var destination=target.connect()) {
            source.setAutoCommit(false);source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            destination.setAutoCommit(false);
            try {
                try(var s=destination.createStatement()) {s.execute("SELECT pg_advisory_xact_lock(846210014)");s.execute("SELECT id FROM inventory_write_lock WHERE id=1 FOR UPDATE");s.execute("SELECT id FROM access_write_lock WHERE id=1 FOR UPDATE");}
                for(String table:TABLES) if(count(destination,table)!=0) throw new IllegalArgumentException("Migration destination contains records. Use a new empty PostgreSQL database.");
                Map<String,Long> result=new LinkedHashMap<>();
                for(String table:TABLES) {
                    long copied=0;
                    try(var select=source.createStatement();var rows=select.executeQuery("SELECT * FROM "+table)) {
                        var metadata=rows.getMetaData();int size=metadata.getColumnCount();List<String> columns=new ArrayList<>();
                        for(int i=1;i<=size;i++)columns.add(metadata.getColumnName(i).toLowerCase(Locale.ROOT));
                        String insert="INSERT INTO "+table+" ("+String.join(",",columns)+")"+(IDENTITIES.containsKey(table)?" OVERRIDING SYSTEM VALUE":"")+" VALUES ("+String.join(",",Collections.nCopies(size,"?"))+")";
                        try(var write=destination.prepareStatement(insert)) {
                            while(rows.next()) {
                                for(int i=1;i<=size;i++) {
                                    if(metadata.getColumnType(i)==Types.CLOB)write.setString(i,rows.getString(i));else write.setObject(i,rows.getObject(i));
                                }
                                write.addBatch();copied++;
                                if(copied%200==0)write.executeBatch();
                            }
                            write.executeBatch();
                        }
                    }
                    if(count(destination,table)!=copied)throw new SQLException("Row-count verification failed: "+table);
                    result.put(table,copied);
                }
                try(var q=destination.createStatement();var r=q.executeQuery("SELECT p.sku FROM products p LEFT JOIN stock_movements m ON p.sku=m.sku GROUP BY p.sku,p.quantity HAVING p.quantity<>COALESCE(SUM(m.quantity),0)")) {
                    if(r.next())throw new SQLException("Imported stock does not reconcile with movement history");
                }
                for(var entry:IDENTITIES.entrySet()) {
                    String table=entry.getKey(),column=entry.getValue();
                    try(var q=destination.createStatement()){q.execute("SELECT setval(pg_get_serial_sequence('"+table+"','"+column+"'),COALESCE(MAX("+column+"),1),COUNT(*)>0) FROM "+table);}
                }
                destination.commit();source.rollback();return Collections.unmodifiableMap(result);
            }catch(SQLException|RuntimeException e){destination.rollback();source.rollback();throw e;}
        }
    }
    private static long count(Connection c,String table)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
}
