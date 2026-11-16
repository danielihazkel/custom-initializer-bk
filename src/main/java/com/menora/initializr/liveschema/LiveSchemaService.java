package com.menora.initializr.liveschema;

import com.menora.initializr.db.entity.SchemaSourceEntity;
import com.menora.initializr.db.repository.SchemaSourceRepository;
import com.menora.initializr.sql.SqlDialect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Opens the connections the live schema endpoints read through — to an admin's source, or to a
 * database typed in by hand while {@code menora.live-schema.typed-connections} allows it — and
 * holds the limits: which hosts a typed connection may name, how many tables one read takes, how
 * many reads run at once.
 */
@Service
public class LiveSchemaService {

    /** An environment variable or a property under {@code menora.live-schema.secrets.}, nothing else. */
    public static final Pattern PASSWORD_REF =
            Pattern.compile("SCHEMA_SOURCE_[A-Z0-9_]{1,200}|menora\\.live-schema\\.secrets\\.[a-z0-9][a-z0-9.-]{0,180}");

    private final SchemaSourceRepository sources;
    private final Environment environment;
    private final boolean typedConnections;
    private final List<Pattern> allowedHosts;
    private final int maxTables;
    private final boolean allowH2;
    private final Semaphore reads;

    public LiveSchemaService(SchemaSourceRepository sources, Environment environment,
                             @Value("${menora.live-schema.typed-connections:true}") boolean typedConnections,
                             @Value("${menora.live-schema.allowed-hosts:}") String allowedHosts,
                             @Value("${menora.live-schema.max-tables:300}") int maxTables,
                             @Value("${menora.live-schema.max-concurrent-reads:4}") int maxConcurrentReads,
                             @Value("${menora.live-schema.allow-h2:false}") boolean allowH2) {
        this.sources = sources;
        this.environment = environment;
        this.typedConnections = typedConnections;
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(LiveSchemaService::glob).toList();
        this.maxTables = maxTables;
        this.allowH2 = allowH2;
        this.reads = new Semaphore(Math.max(1, maxConcurrentReads));
    }

    /**
     * A connection as the caller names it: {@code source} (an admin's source, by name), or the
     * parts of a database typed in by hand.
     */
    public record ConnectionBody(String source, String dialect, String host, Integer port, String database,
                                 String library, String user, String password, Boolean trustServerCertificate) {}

    // ── What the screens may offer ─────────────────────────────────────────

    public record SourceSummary(String name, String dialect, String description, String defaultSchema) {}

    public record Options(List<SourceSummary> sources, boolean typedConnections,
                          Map<String, Boolean> drivers, Map<String, Integer> defaultPorts, int maxTables) {}

    public Options options() {
        List<SourceSummary> list = new ArrayList<>();
        for (SchemaSourceEntity s : sources.findAllByOrderBySortOrderAscNameAsc()) {
            if (s.isEnabled()) list.add(new SourceSummary(s.getName(), s.getDialect(), s.getDescription(), s.getDefaultSchema()));
        }
        Map<String, Boolean> drivers = new LinkedHashMap<>();
        Map<String, Integer> ports = new LinkedHashMap<>();
        for (SqlDialect d : readable()) {
            DialectSupport support = DialectSupport.of(d);
            drivers.put(d.name(), support.driverPresent());
            if (support.defaultPort() != null) ports.put(d.name(), support.defaultPort());
        }
        return new Options(list, typedConnections, drivers, ports, maxTables);
    }

    private List<SqlDialect> readable() {
        List<SqlDialect> out = new ArrayList<>(DialectSupport.READABLE);
        if (allowH2) out.add(SqlDialect.H2);
        return out;
    }

    // ── Connecting ─────────────────────────────────────────────────────────

    /** The connection a body names, checked: a known source, or a typed one that is allowed. */
    public ConnectionSpec resolve(ConnectionBody body) {
        if (body == null) throw LiveSchemaException.badRequest("Say which database to read");
        if (body.source() != null && !body.source().isBlank()) {
            SchemaSourceEntity s = sources.findByNameIgnoreCase(body.source().trim())
                    .filter(SchemaSourceEntity::isEnabled)
                    .orElseThrow(() -> new LiveSchemaException(HttpStatus.NOT_FOUND, "Unknown source",
                            "There is no database source called " + body.source().trim()));
            return specOf(s);
        }
        if (!typedConnections) {
            throw new LiveSchemaException(HttpStatus.FORBIDDEN, "Not allowed",
                    "Connections typed in by hand are turned off on this server. Ask an admin to add the database as a source.");
        }
        SqlDialect dialect = dialectOf(body.dialect());
        ConnectionSpec spec = new ConnectionSpec(dialect, trim(body.host()), body.port(), trim(body.database()),
                trim(body.library()), trim(body.user()), body.password(),
                Boolean.TRUE.equals(body.trustServerCertificate()),
                trim(body.host()) + (body.database() == null || body.database().isBlank() ? "" : "/" + body.database().trim()))
                .validated();
        if (!allowedHosts.isEmpty() && allowedHosts.stream().noneMatch(p -> p.matcher(spec.host()).matches())) {
            throw new LiveSchemaException(HttpStatus.FORBIDDEN, "Host not allowed",
                    spec.host() + " is not among the hosts this server may connect to.");
        }
        return spec;
    }

    /** An admin source as a connection; its password comes from the environment. */
    public ConnectionSpec specOf(SchemaSourceEntity s) {
        SqlDialect dialect = dialectOf(s.getDialect());
        if (dialect == SqlDialect.H2) throw LiveSchemaException.badRequest("H2 cannot be a source");
        return new ConnectionSpec(dialect, trim(s.getHost()), s.getPort(), trim(s.getDatabaseName()),
                trim(s.getLibrary()), trim(s.getUsername()), passwordOf(s), s.isTrustServerCertificate(), s.getName())
                .validated();
    }

    /** True when the source's password reference names a value that is set. */
    public boolean passwordAvailable(SchemaSourceEntity s) {
        String ref = s.getPasswordRef();
        return ref == null || ref.isBlank() || (PASSWORD_REF.matcher(ref).matches() && environment.getProperty(ref) != null);
    }

    private String passwordOf(SchemaSourceEntity s) {
        String ref = s.getPasswordRef();
        if (ref == null || ref.isBlank()) return null;
        if (!PASSWORD_REF.matcher(ref).matches()) {
            throw LiveSchemaException.badRequest("The password reference of source " + s.getName() + " is not allowed");
        }
        String value = environment.getProperty(ref);
        if (value == null) {
            throw new LiveSchemaException(HttpStatus.BAD_REQUEST, "No password",
                    "The password of source " + s.getName() + " is not set on this server (" + ref + ").");
        }
        return value;
    }

    private SqlDialect dialectOf(String raw) {
        SqlDialect dialect;
        try {
            dialect = raw == null ? null : SqlDialect.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            dialect = null;
        }
        if (dialect == null || !readable().contains(dialect)) {
            throw LiveSchemaException.badRequest("Database type must be one of " + readable());
        }
        return dialect;
    }

    /** Something to do on an open connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(LiveSchemaReader reader, Connection connection) throws SQLException;
    }

    /** Opens the connection, runs {@code work}, closes it — at most {@code max-concurrent-reads} at a time. */
    public <T> T withConnection(ConnectionSpec spec, Work<T> work) {
        boolean acquired;
        try {
            acquired = reads.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new LiveSchemaException(HttpStatus.TOO_MANY_REQUESTS, "Busy",
                    "Too many schema reads are running. Try again in a moment.");
        }
        try {
            LiveSchemaReader reader = new LiveSchemaReader(DialectSupport.of(spec.dialect()));
            try (Connection c = reader.connect(spec)) {
                return work.run(reader, c);
            } catch (SQLException e) {
                throw new LiveSchemaException(HttpStatus.BAD_REQUEST, "Could not read the schema",
                        "Reading " + spec.label() + " failed: " + LiveSchemaReader.describe(e, spec));
            }
        } finally {
            reads.release();
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public LiveSchemaReader.Schemas schemas(ConnectionBody body) {
        ConnectionSpec spec = resolve(body);
        return withConnection(spec, LiveSchemaReader::schemas);
    }

    public LiveSchemaReader.Tables tables(ConnectionBody body, String schema) {
        ConnectionSpec spec = resolve(body);
        String s = requireSchema(schema);
        return withConnection(spec, (reader, c) -> reader.tables(c, s));
    }

    /** What one read gives back: the model, and how to name the database in the script. */
    public record Read(DialectSupport support, LiveSchemaModel.Schema schema, String source) {}

    public Read read(ConnectionBody body, String schema, List<String> tables) {
        ConnectionSpec spec = resolve(body);
        String s = requireSchema(schema);
        if (tables == null || tables.isEmpty()) throw LiveSchemaException.badRequest("Pick at least one table or view");
        if (tables.size() > maxTables) {
            throw LiveSchemaException.badRequest("Pick at most " + maxTables + " tables and views at a time");
        }
        LiveSchemaModel.Schema read = withConnection(spec, (reader, c) -> reader.read(c, s, tables));
        return new Read(DialectSupport.of(spec.dialect()), read, spec.label());
    }

    private static String requireSchema(String schema) {
        if (schema == null || !ConnectionSpec.SCHEMA.matcher(schema.trim()).matches()) {
            throw LiveSchemaException.badRequest("Pick a schema");
        }
        return schema.trim();
    }

    /** Admin "Test connection": the source's schemas, or why it cannot connect. */
    public record TestResult(boolean ok, String message, int schemas) {}

    public TestResult test(SchemaSourceEntity source) {
        try {
            ConnectionSpec spec = specOf(source);
            LiveSchemaReader.Schemas found = withConnection(spec, LiveSchemaReader::schemas);
            return new TestResult(true, "Connected to " + spec.label() + ": " + found.schemas().size()
                    + " schema" + (found.schemas().size() == 1 ? "" : "s") + " to read.", found.schemas().size());
        } catch (LiveSchemaException e) {
            return new TestResult(false, e.getMessage(), 0);
        }
    }

    private static Pattern glob(String pattern) {
        StringBuilder re = new StringBuilder();
        for (char ch : pattern.toLowerCase(Locale.ROOT).toCharArray()) {
            re.append(ch == '*' ? ".*" : Pattern.quote(String.valueOf(ch)));
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
