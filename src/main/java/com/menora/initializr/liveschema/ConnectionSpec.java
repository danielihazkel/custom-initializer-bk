package com.menora.initializr.liveschema;

import com.menora.initializr.sql.SqlDialect;

import java.util.regex.Pattern;

/**
 * Where to connect: a database named by its parts, never by a JDBC URL. The URL is built by
 * {@link DialectSupport} from these parts, so a caller cannot add driver properties (a socket
 * factory, an init script) to it — the patterns below keep {@code ; ? & =} and the like out of
 * every part that lands in the URL. User and password travel as connection properties.
 *
 * @param label how the connection is named in messages and logs: an admin source's name, else
 *              {@code host/database}. Never the password.
 */
public record ConnectionSpec(SqlDialect dialect, String host, Integer port, String database, String library,
                             String user, String password, boolean trustServerCertificate, String label) {

    static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    /** A database, an Oracle service name, a SQL Server database. */
    static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$#][A-Za-z0-9_$#.-]{0,127}");
    /** A schema or a DB2 library. */
    static final Pattern SCHEMA = Pattern.compile("[A-Za-z0-9_$#@][A-Za-z0-9_$#@ .-]{0,127}");

    /** Checks every part that reaches the URL; throws a 400 naming the part. */
    public ConnectionSpec validated() {
        if (dialect == null) throw LiveSchemaException.badRequest("Pick a database type");
        if (host == null || !HOST.matcher(host).matches()) {
            throw LiveSchemaException.badRequest("Host must be a host name or an IP address");
        }
        if (port != null && (port < 1 || port > 65535)) {
            throw LiveSchemaException.badRequest("Port must be between 1 and 65535");
        }
        boolean needsDatabase = dialect != SqlDialect.DB2;
        if (needsDatabase && (database == null || !DATABASE.matcher(database).matches())) {
            throw LiveSchemaException.badRequest(dialect == SqlDialect.ORACLE
                    ? "Service name: letters, digits, _ $ # . - only"
                    : "Database: letters, digits, _ $ # . - only");
        }
        if (!needsDatabase && database != null && !database.isBlank() && !DATABASE.matcher(database).matches()) {
            throw LiveSchemaException.badRequest("Database: letters, digits, _ $ # . - only");
        }
        if (library != null && !library.isBlank() && !SCHEMA.matcher(library).matches()) {
            throw LiveSchemaException.badRequest("Library: letters, digits, _ $ # @ only");
        }
        if (user == null || user.isBlank() || user.length() > 128) {
            throw LiveSchemaException.badRequest("User is required");
        }
        if (password != null && password.length() > 512) {
            throw LiveSchemaException.badRequest("Password is too long");
        }
        return this;
    }

    /** Never prints the password: a spec can end up in a log line or an exception. */
    @Override
    public String toString() {
        return "ConnectionSpec[" + dialect + " " + label + "]";
    }
}
