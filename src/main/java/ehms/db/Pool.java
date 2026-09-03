package ehms.db;

import ehms.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDBC connection pooling with graceful degradation:
 *  - HikariCP when com.zaxxer.hikari.* is on the classpath. It is loaded via
 *    REFLECTION on purpose, so the application code never imports it and the
 *    whole project still compiles and runs with zero dependencies without it.
 *  - Otherwise the built-in fallback pool (connection per transaction,
 *    health-checked, dead connections replaced).
 */
public interface Pool {

    Connection borrow() throws SQLException;

    void giveBack(Connection connection);

    void closeAll();

    /** Chooses the best available implementation for this JDBC URL. */
    static Pool create(String url, boolean mysql) {
        Pool hikari = HikariAdapter.tryCreate(url, mysql);
        if (hikari != null) {
            Log.info("Connection pooling: HikariCP");
            return hikari;
        }
        Log.info("Connection pooling: built-in fallback pool (HikariCP not on the classpath)");
        return new FallbackPool(url, mysql, mysql ? 6 : 1);   // SQLite allows a single writer
    }
}

/** Reflection-based adapter: ~25 lines instead of a compile-time dependency. */
final class HikariAdapter implements Pool {

    private final Object dataSource;
    private final Method getConnection;
    private final Method close;

    private HikariAdapter(Object dataSource, Method getConnection, Method close) {
        this.dataSource = dataSource;
        this.getConnection = getConnection;
        this.close = close;
    }

    static HikariAdapter tryCreate(String url, boolean mysql) {
        try {
            Class<?> cfgClass = Class.forName("com.zaxxer.hikari.HikariConfig");
            Object cfg = cfgClass.getDeclaredConstructor().newInstance();
            set(cfgClass, cfg, "setJdbcUrl", url);
            set(cfgClass, cfg, "setPoolName", "ehms-pool");
            set(cfgClass, cfg, "setMaximumPoolSize", mysql ? 6 : 1);
            set(cfgClass, cfg, "setConnectionTimeout", 15_000L);
            set(cfgClass, cfg, "setIdleTimeout", 60_000L);
            if (mysql) set(cfgClass, cfg, "setConnectionInitSql", "SET NAMES utf8mb4");

            Class<?> dsClass = Class.forName("com.zaxxer.hikari.HikariDataSource");
            Object ds = dsClass.getConstructor(cfgClass).newInstance(cfg);
            return new HikariAdapter(ds, dsClass.getMethod("getConnection"), dsClass.getMethod("close"));
        } catch (Throwable unavailable) {
            return null;   // HikariCP not on the classpath - caller uses the fallback
        }
    }

    private static void set(Class<?> type, Object target, String setter, Object value) throws Exception {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(setter) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(value)) {
                m.invoke(target, value);
                return;
            }
        }
        throw new NoSuchMethodException(setter);
    }

    @Override
    public Connection borrow() throws SQLException {
        try {
            return (Connection) getConnection.invoke(dataSource);
        } catch (Exception e) {
            throw asSql(e, "Could not borrow a pooled connection (is the JDBC driver on the classpath?)");
        }
    }

    /** Closing a pooled connection HANDS IT BACK to Hikari - that's the whole trick. */
    @Override
    public void giveBack(Connection connection) {
        try { if (connection != null) connection.close(); } catch (SQLException ignored) { }
    }

    @Override
    public void closeAll() {
        try { close.invoke(dataSource); } catch (Exception ignored) { }
    }

    private static SQLException asSql(Exception e, String message) {
        Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
        return cause instanceof SQLException sql ? sql : new SQLException(message + ": " + cause, cause);
    }
}

/** The hand-rolled pool (connection per transaction, health-checked). */
final class FallbackPool implements Pool {

    private final String url;
    private final boolean mysql;
    private final int maxSize;
    private final ArrayBlockingQueue<Connection> idle;
    private final AtomicInteger created = new AtomicInteger();

    FallbackPool(String url, boolean mysql, int maxSize) {
        this.url = url;
        this.mysql = mysql;
        this.maxSize = Math.max(1, maxSize);
        this.idle = new ArrayBlockingQueue<>(this.maxSize);
    }

    @Override
    public Connection borrow() throws SQLException {
        Connection c = idle.poll();
        while (c != null) {
            if (valid(c)) return c;
            closeQuietly(c);
            created.decrementAndGet();
            c = idle.poll();
        }
        if (created.get() < maxSize) {
            created.incrementAndGet();
            return open();
        }
        Connection pooled;
        try {
            pooled = idle.poll(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a pooled connection", e);
        }
        if (pooled == null)
            throw new SQLException("Connection pool exhausted after 15s");
        if (!valid(pooled)) {
            closeQuietly(pooled);
            created.decrementAndGet();
            return borrow();
        }
        return pooled;
    }

    @Override
    public void giveBack(Connection connection) {
        if (connection == null) return;
        if (valid(connection)) {
            if (!idle.offer(connection)) { closeQuietly(connection); created.decrementAndGet(); }
        } else {
            closeQuietly(connection);
            created.decrementAndGet();
        }
    }

    @Override
    public void closeAll() {
        Connection c;
        while ((c = idle.poll()) != null) { closeQuietly(c); created.decrementAndGet(); }
    }

    private Connection open() throws SQLException {
        Connection c;
        try {
            c = DriverManager.getConnection(url);
        } catch (SQLException e) {
            created.decrementAndGet();
            if (String.valueOf(e.getMessage()).contains("No suitable driver")) {
                throw new SQLException("No JDBC driver found for " + (mysql ? "MySQL" : "SQLite") + ".\n"
                        + "  Put the driver jar on the classpath:\n"
                        + (mysql
                            ? "    java -cp \"out:mysql-connector-j-9.1.0.jar\" ehms.Main --db mysql://localhost:3306/ehms?user=root&password=secret"
                            : "    java -cp \"out:sqlite-jdbc-3.46.1.3.jar\" ehms.Main --db sqlite:ehms.db"), e);
            }
            throw e;
        }
        if (mysql) {
            try (Statement st = c.createStatement()) { st.execute("SET NAMES utf8mb4"); }
            catch (SQLException ignored) { }
        }
        return c;
    }

    private boolean valid(Connection c) {
        try { return c != null && !c.isClosed() && c.isValid(2); }
        catch (SQLException e) { return false; }
    }

    private void closeQuietly(Connection c) {
        try { if (c != null) c.close(); } catch (SQLException ignored) { }
    }
}