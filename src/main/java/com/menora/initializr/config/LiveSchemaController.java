package com.menora.initializr.config;

import com.menora.initializr.config.ExtensionMetadataController.EntityWire;
import com.menora.initializr.config.ExtensionMetadataController.FieldWire;
import com.menora.initializr.config.ExtensionMetadataController.ImportDdlResponse;
import com.menora.initializr.fullstack.FieldType;
import com.menora.initializr.fullstack.SqlToEntityDefinitionConverter;
import com.menora.initializr.liveschema.DialectSupport;
import com.menora.initializr.liveschema.LiveDdlWriter;
import com.menora.initializr.liveschema.LiveSchemaException;
import com.menora.initializr.liveschema.LiveSchemaModel;
import com.menora.initializr.liveschema.LiveSchemaModel.Kind;
import com.menora.initializr.liveschema.LiveSchemaReader;
import com.menora.initializr.liveschema.LiveSchemaService;
import com.menora.initializr.liveschema.LiveSchemaService.ConnectionBody;
import com.menora.initializr.sql.SqlEntityGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Reading a live database's schema for the SQL wizard (as a script for its SQL box) and the
 * fullstack editor (as entities). Three steps, each its own call over a fresh connection:
 * the schemas, a schema's tables and views, then the chosen ones read. The connection is named in
 * every body ({@link ConnectionBody}): an admin's source, or a database typed in by hand. A
 * password typed in is used for that call and kept nowhere.
 */
@RestController
public class LiveSchemaController {

    private static final Logger log = LoggerFactory.getLogger(LiveSchemaController.class);

    private final LiveSchemaService service;
    private final SqlToEntityDefinitionConverter converter;
    private final String userHeader;

    public LiveSchemaController(LiveSchemaService service, SqlToEntityDefinitionConverter converter,
                                @Value("${menora.audit.user-header:userinfo}") String userHeader) {
        this.service = service;
        this.converter = converter;
        this.userHeader = userHeader;
    }

    public record LiveRequest(ConnectionBody connection, String schema, List<String> tables, Boolean qualify) {}

    public record SchemasResponse(List<String> schemas, String defaultSchema) {}

    public record DdlResponse(String dialect, String sql, int tables, int views, List<String> warnings) {}

    @GetMapping("/metadata/live-schema/options")
    public LiveSchemaService.Options options() {
        return service.options();
    }

    @PostMapping("/metadata/live-schema/schemas")
    public SchemasResponse schemas(@RequestBody LiveRequest body) {
        ConnectionBody connection = body == null ? null : body.connection();
        LiveSchemaReader.Schemas found = service.schemas(connection);
        String preferred = connection != null && connection.source() != null
                ? service.options().sources().stream()
                        .filter(s -> s.name().equalsIgnoreCase(connection.source().trim()))
                        .map(LiveSchemaService.SourceSummary::defaultSchema)
                        .filter(s -> s != null && !s.isBlank()).findFirst().orElse(null)
                : null;
        return new SchemasResponse(found.schemas(), pick(found.schemas(), preferred != null ? preferred : found.current()));
    }

    @PostMapping("/metadata/live-schema/tables")
    public LiveSchemaReader.Tables tables(@RequestBody LiveRequest body) {
        if (body == null) throw LiveSchemaException.badRequest("Say which database to read");
        return service.tables(body.connection(), body.schema());
    }

    /** The chosen tables and views as a script for the SQL wizard, in the database's dialect. */
    @PostMapping("/metadata/live-schema/ddl")
    public DdlResponse ddl(@RequestBody LiveRequest body, HttpServletRequest request) {
        if (body == null) throw LiveSchemaException.badRequest("Say which database to read");
        LiveSchemaService.Read read = service.read(body.connection(), body.schema(), body.tables());
        logRead("ddl", read, request);
        String sql = LiveDdlWriter.write(read.support(), read.schema(), read.source(), !Boolean.FALSE.equals(body.qualify()));
        return new DdlResponse(read.support().dialect().name(), sql, count(read, Kind.TABLE), count(read, Kind.VIEW),
                read.schema().warnings());
    }

    /**
     * The chosen tables and views as fullstack entities: the tables through the DDL import (so a
     * foreign key between two of them is a relation, a {@code CHECK … IN} an enum), each view as a
     * read-only entity whose fields keep their columns' types.
     */
    @PostMapping("/metadata/fullstack/import-live")
    public ImportDdlResponse importLive(@RequestBody LiveRequest body, HttpServletRequest request) {
        if (body == null) throw LiveSchemaException.badRequest("Say which database to read");
        boolean qualify = !Boolean.FALSE.equals(body.qualify());
        LiveSchemaService.Read read = service.read(body.connection(), body.schema(), body.tables());
        logRead("fullstack", read, request);
        DialectSupport support = read.support();
        LiveSchemaModel.Schema schema = read.schema();

        List<EntityWire> entities = new ArrayList<>();
        List<LiveSchemaModel.Table> tables = schema.tables().stream().filter(t -> t.kind() == Kind.TABLE).toList();
        if (!tables.isEmpty()) {
            String ddl = LiveDdlWriter.write(support, new LiveSchemaModel.Schema(schema.schema(), tables, List.of()),
                    read.source(), qualify);
            entities.addAll(ExtensionMetadataController.toWire(converter.convert(ddl, support.dialect())));
        }
        int views = 0;
        for (LiveSchemaModel.Table view : schema.tables()) {
            if (view.kind() != Kind.VIEW) continue;
            views++;
            String select = LiveDdlWriter.selectOf(support, view, qualify);
            for (EntityWire e : ExtensionMetadataController.toWire(converter.convertSelect(select, support.dialect()).entities())) {
                entities.add(typed(e, view, support));
            }
        }
        List<String> notes = new ArrayList<>(schema.warnings());
        if (views > 0) {
            notes.add("A view has no key: its first column was made the key — check it in the editor.");
        }
        return new ImportDdlResponse(entities, notes.isEmpty() ? null : String.join(" ", notes));
    }

    /** A view's entity with each field typed from its column, as a table's would be. */
    private static EntityWire typed(EntityWire e, LiveSchemaModel.Table view, DialectSupport support) {
        List<FieldWire> fields = new ArrayList<>(e.fields().size());
        for (FieldWire f : e.fields()) {
            LiveSchemaModel.Column col = view.column(f.name());
            if (col == null) {
                fields.add(f);
                continue;
            }
            Matcher m = LiveDdlWriter.TYPE.matcher(support.typeOf(col));
            String raw = m.matches() ? m.group(1).trim() : support.typeOf(col);
            Integer precision = m.matches() && m.group(2) != null && !m.group(2).equals("MAX") ? Integer.valueOf(m.group(2)) : null;
            Integer scale = m.matches() && m.group(3) != null ? Integer.valueOf(m.group(3)) : null;
            FieldType type = SqlToEntityDefinitionConverter.fieldTypeOf(support.dialect(), raw, precision, scale, f.primaryKey());
            fields.add(new FieldWire(f.name(), type.name(), f.primaryKey(), false, !col.nullable() || f.primaryKey(),
                    false, type == FieldType.STRING ? precision : null, List.of(), f.column()));
        }
        return new EntityWire(e.name(), e.tableName(), e.schema(), fields, e.readOnly(), e.viewQuery(), e.sourceSql(),
                e.relations(), e.uniqueKeys());
    }

    private static int count(LiveSchemaService.Read read, Kind kind) {
        return (int) read.schema().tables().stream().filter(t -> t.kind() == kind).count();
    }

    /** {@code wanted} in the list's own spelling, or null when the list does not have it. */
    private static String pick(List<String> schemas, String wanted) {
        if (wanted == null) return null;
        for (String s : schemas) if (s.equalsIgnoreCase(wanted)) return s;
        return null;
    }

    private void logRead(String what, LiveSchemaService.Read read, HttpServletRequest request) {
        log.info("Live schema read ({}): {} {} schema {} — {} tables/views, by {}", what,
                read.support().dialect(), read.source(), read.schema().schema(), read.schema().tables().size(),
                request.getHeader(userHeader) == null ? "anonymous"
                        : request.getHeader(userHeader).replaceAll("[\\r\\n\\t]", " "));
    }

    @ExceptionHandler(LiveSchemaException.class)
    public ResponseEntity<Map<String, Object>> handle(LiveSchemaException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.error());
        body.put("detail", ex.getMessage());
        return ResponseEntity.status(ex.status()).body(body);
    }

    /** The script written from the catalog did not parse — a gap in {@link LiveDdlWriter}, said as such. */
    @ExceptionHandler(SqlEntityGenerator.SqlParseException.class)
    public ResponseEntity<Map<String, Object>> handleParse(SqlEntityGenerator.SqlParseException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Could not import the schema");
        body.put("detail", "The schema was read, but its script did not parse: " + ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
