package com.menora.initializr.openapi;

import com.menora.initializr.config.WizardTemplates;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parses an OpenAPI 3.x spec via swagger-parser and emits Spring MVC
 * controllers + DTO {@code record}s. One instance handles one spec at
 * generation time; it is stateless and {@code @Service}-scoped.
 */
@Service
public class OpenApiCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiCodeGenerator.class);


    /** Raised to the controller layer; converted to HTTP 400. */
    public static class OpenApiParseException extends RuntimeException {
        private final List<String> messages;
        public OpenApiParseException(List<String> messages) {
            super(String.join("; ", messages == null ? List.of() : messages));
            this.messages = messages == null ? List.of() : List.copyOf(messages);
        }
        public List<String> messages() { return messages; }
    }

    public List<GeneratedOpenApiFile> generate(String spec, String packageName, OpenApiWizardOptions options) {
        // Default: do not emit Bean Validation. The 3-arg path is used by the discard-only
        // spec parse-check, which adds no validation starter.
        return generate(spec, packageName, options, false);
    }

    /**
     * @param hasValidation whether the {@code validation} starter is on the generated project's
     *                      build — gates {@code @Valid} / {@code jakarta.validation.Valid} emission,
     *                      which otherwise wouldn't resolve at compile time.
     */
    public List<GeneratedOpenApiFile> generate(String spec, String packageName,
                                               OpenApiWizardOptions options, boolean hasValidation) {
        if (spec == null || spec.isBlank()) return List.of();
        OpenAPI api = parseOrThrow(spec);
        OpenApiWizardOptions opts = options != null ? options
                : new OpenApiWizardOptions(null, null, null, null, null);

        Map<String, SchemaModel> schemas = SchemaRecordEmitter.schemas(api);
        List<OperationModel> operations = buildOperations(api, schemas.keySet());

        List<GeneratedOpenApiFile> out = new ArrayList<>();
        for (SchemaModel s : schemas.values()) {
            out.add(renderRecord(s, packageName, opts));
        }
        Map<String, List<OperationModel>> byTag = groupByTag(operations);
        if (opts.emitControllers()) {
            for (var group : byTag.entrySet()) {
                out.add(renderController(group.getKey(), group.getValue(), packageName, opts, hasValidation));
            }
        }
        if (opts.emitClient() && !byTag.isEmpty()) {
            for (var group : byTag.entrySet()) {
                out.add(renderRestTemplateClient(group.getKey(), group.getValue(), packageName, opts));
            }
            out.add(renderClientConfig(packageName, opts));
            out.add(renderApplicationYmlStub(opts));
        }
        return out;
    }

    /**
     * For the wizard's live preview — returns {@code "GET /pets"} style strings. A document
     * that does not parse throws {@link OpenApiParseException} (a 400 from the wizard
     * controller), so the drawer can say the spec is broken instead of "nothing detected yet".
     */
    public List<String> detectPaths(String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        OpenAPI api = parseOrThrow(spec);
        if (api.getPaths() == null) return List.of();
        List<String> out = new ArrayList<>();
        for (var e : api.getPaths().entrySet()) {
            PathItem item = e.getValue();
            if (item == null) continue;
            addIfPresent(out, "GET", e.getKey(), item.getGet());
            addIfPresent(out, "POST", e.getKey(), item.getPost());
            addIfPresent(out, "PUT", e.getKey(), item.getPut());
            addIfPresent(out, "DELETE", e.getKey(), item.getDelete());
            addIfPresent(out, "PATCH", e.getKey(), item.getPatch());
            addIfPresent(out, "HEAD", e.getKey(), item.getHead());
            addIfPresent(out, "OPTIONS", e.getKey(), item.getOptions());
        }
        return out;
    }

    private static void addIfPresent(List<String> out, String method, String path, Operation op) {
        if (op != null) out.add(method + " " + path);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private OpenAPI parseOrThrow(String spec) {
        ParseOptions po = new ParseOptions();
        po.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(spec, null, po);
        if (result.getOpenAPI() == null) {
            List<String> msgs = result.getMessages() == null || result.getMessages().isEmpty()
                    ? List.of("Unrecognised OpenAPI document") : result.getMessages();
            throw new OpenApiParseException(msgs);
        }
        return result.getOpenAPI();
    }

    // ── Schema → record (SchemaRecordEmitter) ─────────────────────────────────

    private GeneratedOpenApiFile renderRecord(SchemaModel s, String packageName, OpenApiWizardOptions opts) {
        String subPkg = opts.dtoPackageOrDefault();
        String path = "src/main/java/{{packagePath}}/" + subPkg + "/" + s.name() + ".java";
        return new GeneratedOpenApiFile(path, SchemaRecordEmitter.renderRecord(s, packageName + "." + subPkg));
    }

    // ── Operations → controllers ──────────────────────────────────────────────

    private List<OperationModel> buildOperations(OpenAPI api, Set<String> knownSchemaNames) {
        List<OperationModel> out = new ArrayList<>();
        if (api.getPaths() == null) return out;
        Set<String> usedOpIds = new LinkedHashSet<>();
        for (var e : api.getPaths().entrySet()) {
            PathItem item = e.getValue();
            if (item == null) continue;
            String path = e.getKey();
            addOp(out, usedOpIds, knownSchemaNames, "GET", path, item.getGet());
            addOp(out, usedOpIds, knownSchemaNames, "POST", path, item.getPost());
            addOp(out, usedOpIds, knownSchemaNames, "PUT", path, item.getPut());
            addOp(out, usedOpIds, knownSchemaNames, "DELETE", path, item.getDelete());
            addOp(out, usedOpIds, knownSchemaNames, "PATCH", path, item.getPatch());
        }
        return out;
    }

    private void addOp(List<OperationModel> out, Set<String> usedOpIds, Set<String> knownSchemas,
                       String method, String path, Operation op) {
        if (op == null) return;
        String opIdRaw = op.getOperationId();
        String opId = (opIdRaw == null || opIdRaw.isBlank())
                ? fallbackOpId(method, path) : toCamelCase(opIdRaw);
        opId = uniqueName(opId, usedOpIds);
        String tag = (op.getTags() == null || op.getTags().isEmpty())
                ? "Default" : toPascalCase(op.getTags().get(0));

        List<ParamModel> params = new ArrayList<>();
        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                ParamModel.ParamLocation loc = switch (String.valueOf(p.getIn()).toLowerCase(Locale.ROOT)) {
                    case "path" -> ParamModel.ParamLocation.PATH;
                    case "query" -> ParamModel.ParamLocation.QUERY;
                    case "header" -> ParamModel.ParamLocation.HEADER;
                    case "cookie" -> ParamModel.ParamLocation.COOKIE;
                    default -> ParamModel.ParamLocation.QUERY;
                };
                SchemaRecordEmitter.JavaTypeRef tr = p.getSchema() == null ? new SchemaRecordEmitter.JavaTypeRef("String", Set.of())
                        : mapType(p.getSchema());
                params.add(new ParamModel(
                        toCamelCase(p.getName()),
                        loc,
                        tr.typeName(),
                        tr.imports(),
                        Boolean.TRUE.equals(p.getRequired())));
            }
        }

        String reqType = null;
        Set<String> reqImports = Set.of();
        RequestBody rb = op.getRequestBody();
        if (rb != null && rb.getContent() != null) {
            MediaType mt = pickJsonOrFirst(rb.getContent());
            if (mt != null && mt.getSchema() != null) {
                SchemaRecordEmitter.JavaTypeRef tr = mapType(mt.getSchema());
                reqType = tr.typeName();
                reqImports = tr.imports();
            }
        }

        String respType = null;
        Set<String> respImports = Set.of();
        ApiResponses responses = op.getResponses();
        if (responses != null) {
            ApiResponse ok = responses.get("200");
            if (ok == null) ok = responses.get("201");
            if (ok == null) ok = responses.get("default");
            if (ok != null && ok.getContent() != null) {
                MediaType mt = pickJsonOrFirst(ok.getContent());
                if (mt != null && mt.getSchema() != null) {
                    SchemaRecordEmitter.JavaTypeRef tr = mapType(mt.getSchema());
                    respType = tr.typeName();
                    respImports = tr.imports();
                }
            }
        }

        out.add(new OperationModel(method, path, opId, tag, op.getSummary(),
                params, reqType, reqImports, respType, respImports));
    }

    private Map<String, List<OperationModel>> groupByTag(List<OperationModel> ops) {
        Map<String, List<OperationModel>> map = new LinkedHashMap<>();
        for (OperationModel op : ops) {
            map.computeIfAbsent(op.tag(), k -> new ArrayList<>()).add(op);
        }
        return map;
    }

    private GeneratedOpenApiFile renderController(String tag, List<OperationModel> ops,
                                                  String packageName, OpenApiWizardOptions opts,
                                                  boolean hasValidation) {
        String apiPkg = opts.apiPackageOrDefault();
        String dtoPkg = opts.dtoPackageOrDefault();
        String fullPkg = packageName + "." + apiPkg;
        String dtoFullPkg = packageName + "." + dtoPkg;
        String className = tag + "Controller";

        Set<String> imports = new TreeSet<>();
        imports.add("org.springframework.web.bind.annotation.RestController");
        imports.add("org.springframework.web.bind.annotation.RequestMapping");
        imports.add("org.springframework.validation.annotation.Validated");

        // Collect unique mappings / annotation imports
        boolean hasBody = false, hasPath = false, hasQuery = false, hasHeader = false;
        Set<String> mappings = new LinkedHashSet<>();
        for (OperationModel op : ops) {
            mappings.add("org.springframework.web.bind.annotation." + mappingAnnotation(op.httpMethod()));
            if (op.requestBodyType() != null) hasBody = true;
            for (ParamModel p : op.params()) {
                switch (p.in()) {
                    case PATH -> hasPath = true;
                    case QUERY, COOKIE -> hasQuery = true;
                    case HEADER -> hasHeader = true;
                }
                imports.addAll(qualifyDto(p.imports(), dtoFullPkg));
            }
            imports.addAll(qualifyDto(op.requestBodyImports(), dtoFullPkg));
            imports.addAll(qualifyDto(op.responseImports(), dtoFullPkg));
            // Resolve local DTO references by name (types produced by our own schema renderer)
            addDtoImportIfLocal(imports, op.requestBodyType(), dtoFullPkg);
            addDtoImportIfLocal(imports, op.responseType(), dtoFullPkg);
        }
        imports.addAll(mappings);
        if (hasBody) {
            imports.add("org.springframework.web.bind.annotation.RequestBody");
            if (hasValidation) imports.add("jakarta.validation.Valid");
        }
        if (hasPath) imports.add("org.springframework.web.bind.annotation.PathVariable");
        if (hasQuery) imports.add("org.springframework.web.bind.annotation.RequestParam");
        if (hasHeader) imports.add("org.springframework.web.bind.annotation.RequestHeader");

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("imports", imports);
        view.put("className", className);
        List<Map<String, Object>> methods = new ArrayList<>();
        for (OperationModel op : ops) methods.add(controllerMethod(op, hasValidation));
        view.put("operations", methods);

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + apiPkg + "/" + className + ".java",
                WizardTemplates.render("openapi/Controller.java", view));
    }

    /** One operation as the controller template reads it. */
    private Map<String, Object> controllerMethod(OperationModel op, boolean hasValidation) {
        Map<String, Object> m = operationView(op);
        m.put("mapping", mappingAnnotation(op.httpMethod()));

        List<String> args = new ArrayList<>();
        for (ParamModel p : op.params()) {
            StringBuilder arg = new StringBuilder();
            switch (p.in()) {
                case PATH -> arg.append("@PathVariable(\"").append(p.name()).append("\") ");
                case QUERY, COOKIE -> arg.append("@RequestParam(name = \"").append(p.name())
                        .append("\", required = ").append(p.required()).append(") ");
                case HEADER -> arg.append("@RequestHeader(name = \"").append(p.name())
                        .append("\", required = ").append(p.required()).append(") ");
            }
            arg.append(p.javaType()).append(' ').append(p.name());
            args.add(arg.toString());
        }
        if (op.requestBodyType() != null) {
            args.add((hasValidation ? "@Valid " : "") + "@RequestBody " + op.requestBodyType() + " body");
        }
        m.put("arguments", String.join(", ", args));
        return m;
    }

    /** What both the controller and the client template read of an operation. */
    private static Map<String, Object> operationView(OperationModel op) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean summarized = op.summary() != null && !op.summary().isBlank();
        m.put("hasSummary", summarized);
        m.put("summaryJavadoc", summarized ? escapeJavadoc(op.summary()) : null);
        m.put("path", op.path());
        m.put("returnType", op.responseType() == null ? "void" : op.responseType());
        m.put("operationId", op.operationId());
        return m;
    }

    private static String mappingAnnotation(String httpMethod) {
        return switch (httpMethod.toUpperCase(Locale.ROOT)) {
            case "GET" -> "GetMapping";
            case "POST" -> "PostMapping";
            case "PUT" -> "PutMapping";
            case "DELETE" -> "DeleteMapping";
            case "PATCH" -> "PatchMapping";
            default -> "RequestMapping";
        };
    }

    // ── Operations → RestTemplate client ──────────────────────────────────────

    private GeneratedOpenApiFile renderRestTemplateClient(String tag, List<OperationModel> ops,
                                                          String packageName, OpenApiWizardOptions opts) {
        String clientPkg = opts.clientPackageOrDefault();
        String dtoPkg = opts.dtoPackageOrDefault();
        String fullPkg = packageName + "." + clientPkg;
        String dtoFullPkg = packageName + "." + dtoPkg;
        String baseUrlProp = opts.baseUrlPropertyOrDefault();
        String className = tag + "Client";

        Set<String> imports = new TreeSet<>();
        imports.add("org.springframework.beans.factory.annotation.Value");
        imports.add("org.springframework.http.HttpEntity");
        imports.add("org.springframework.http.HttpHeaders");
        imports.add("org.springframework.http.HttpMethod");
        imports.add("org.springframework.http.ResponseEntity");
        imports.add("org.springframework.stereotype.Component");
        imports.add("org.springframework.web.client.RestTemplate");
        imports.add("org.springframework.web.util.UriComponentsBuilder");

        boolean anyQuery = false, anyHeader = false, anyListResponse = false;
        for (OperationModel op : ops) {
            for (ParamModel p : op.params()) {
                switch (p.in()) {
                    case QUERY, COOKIE -> anyQuery = true;
                    case HEADER -> anyHeader = true;
                    default -> {}
                }
                imports.addAll(p.imports());
                addDtoImportIfLocal(imports, p.javaType(), dtoFullPkg);
            }
            if (op.requestBodyType() != null) {
                imports.addAll(op.requestBodyImports());
                addDtoImportIfLocal(imports, op.requestBodyType(), dtoFullPkg);
            }
            if (op.responseType() != null) {
                imports.addAll(op.responseImports());
                addDtoImportIfLocal(imports, op.responseType(), dtoFullPkg);
                if (op.responseType().startsWith("List<")) anyListResponse = true;
            }
        }
        if (anyListResponse) imports.add("org.springframework.core.ParameterizedTypeReference");
        if (anyHeader) {
            // HttpHeaders already imported
        }
        if (anyQuery) {
            // handled via UriComponentsBuilder, no extra imports
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("imports", imports);
        view.put("className", className);
        // Written whole: "${" beside a Mustache tag would read as a triple brace.
        view.put("baseUrlPlaceholder", "${" + baseUrlProp + "}");
        List<Map<String, Object>> methods = new ArrayList<>();
        for (OperationModel op : ops) methods.add(clientMethod(op));
        view.put("operations", methods);

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/" + className + ".java",
                WizardTemplates.render("openapi/Client.java", view));
    }

    /**
     * One operation as the client template reads it: the URI is the base URL plus the path, with
     * the path variables expanded and the query parameters appended (an optional one only when
     * given); headers likewise; the body and headers travel in one HttpEntity.
     */
    private Map<String, Object> clientMethod(OperationModel op) {
        Map<String, Object> m = operationView(op);

        List<String> args = new ArrayList<>();
        for (ParamModel p : op.params()) {
            args.add(p.javaType() + " " + p.name());
        }
        if (op.requestBodyType() != null) {
            args.add(op.requestBodyType() + " body");
        }
        m.put("arguments", String.join(", ", args));

        List<Map<String, Object>> queryParams = new ArrayList<>();
        List<Map<String, Object>> headerParams = new ArrayList<>();
        List<String> pathNames = new ArrayList<>();
        for (ParamModel p : op.params()) {
            Map<String, Object> pv = new LinkedHashMap<>();
            pv.put("name", p.name());
            pv.put("required", p.required());
            switch (p.in()) {
                case QUERY, COOKIE -> queryParams.add(pv);
                case HEADER -> headerParams.add(pv);
                case PATH -> pathNames.add(p.name());
            }
        }
        m.put("queryParams", queryParams);
        m.put("hasPathParams", !pathNames.isEmpty());
        m.put("pathArguments", String.join(", ", pathNames));
        boolean hasHeader = !headerParams.isEmpty();
        m.put("hasHeaders", hasHeader);
        m.put("headerParams", headerParams);

        String entityExpr;
        if (op.requestBodyType() != null && hasHeader) {
            entityExpr = "new HttpEntity<>(body, headers)";
        } else if (op.requestBodyType() != null) {
            entityExpr = "new HttpEntity<>(body)";
        } else if (hasHeader) {
            entityExpr = "new HttpEntity<>(headers)";
        } else {
            entityExpr = "HttpEntity.EMPTY";
        }
        m.put("entityExpr", entityExpr);

        boolean isList = op.responseType() != null && op.responseType().startsWith("List<");
        String respTypeArg;
        if (op.responseType() == null) {
            respTypeArg = "Void.class";
        } else if (isList) {
            respTypeArg = "new ParameterizedTypeReference<" + op.responseType() + ">() {}";
        } else {
            respTypeArg = op.responseType() + ".class";
        }
        m.put("httpMethod", op.httpMethod().toUpperCase(Locale.ROOT));
        m.put("responseBoxed", op.responseType() == null ? "Void" : op.responseType());
        m.put("responseTypeArg", respTypeArg);
        m.put("returnsBody", op.responseType() != null);
        return m;
    }

    private GeneratedOpenApiFile renderClientConfig(String packageName, OpenApiWizardOptions opts) {
        String clientPkg = opts.clientPackageOrDefault();
        String fullPkg = packageName + "." + clientPkg;
        String baseUrlProp = opts.baseUrlPropertyOrDefault();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("baseUrlProperty", baseUrlProp);

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/OpenApiClientConfig.java",
                WizardTemplates.render("openapi/ClientConfig.java", view));
    }

    private GeneratedOpenApiFile renderApplicationYmlStub(OpenApiWizardOptions opts) {
        String prop = opts.baseUrlPropertyOrDefault();
        String[] parts = prop.split("\\.");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            for (int s = 0; s < i * 2; s++) sb.append(' ');
            sb.append(parts[i]);
            if (i == parts.length - 1) {
                sb.append(": http://localhost:8080\n");
            } else {
                sb.append(":\n");
            }
        }

        return new GeneratedOpenApiFile(
                "src/main/resources/application.yaml",
                sb.toString());
    }

    // ── Type mapping (SchemaRecordEmitter) ────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private static SchemaRecordEmitter.JavaTypeRef mapType(Schema schema) {
        return SchemaRecordEmitter.mapType(schema);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Spring MVC annotations accept only FQ classnames or names matching an
     * existing {@code import}. Our DTO record-name references (e.g. {@code Pet},
     * {@code List<Pet>}) need an explicit import resolving to the dto package.
     */
    private Set<String> qualifyDto(Set<String> imports, String dtoFullPkg) {
        return imports == null ? Set.of() : new LinkedHashSet<>(imports);
    }

    /**
     * If the type name looks like a capitalised DTO record we just produced
     * (e.g. {@code Pet}, {@code List<Pet>}), add the dto-package import so the
     * controller compiles.
     */
    private void addDtoImportIfLocal(Set<String> imports, String typeName, String dtoFullPkg) {
        if (typeName == null) return;
        // Extract the inner name from List<Pet> etc.
        String t = typeName;
        int lt = t.indexOf('<');
        if (lt >= 0) {
            int gt = t.lastIndexOf('>');
            if (gt > lt) t = t.substring(lt + 1, gt);
        }
        if (t.isEmpty()) return;
        char first = t.charAt(0);
        if (!Character.isUpperCase(first)) return;
        if (isBuiltIn(t)) return;
        imports.add(dtoFullPkg + "." + t);
    }

    private static boolean isBuiltIn(String name) {
        return switch (name) {
            case "Object", "String", "Integer", "Long", "Float", "Double", "Boolean",
                 "UUID", "LocalDate", "LocalDateTime" -> true;
            default -> false;
        };
    }

    private MediaType pickJsonOrFirst(io.swagger.v3.oas.models.media.Content content) {
        if (content == null || content.isEmpty()) return null;
        MediaType json = content.get("application/json");
        if (json != null) return json;
        return content.values().iterator().next();
    }

    private static String fallbackOpId(String method, String path) {
        String cleaned = path.replaceAll("[{}]", "").replaceAll("[^a-zA-Z0-9]+", "_");
        while (cleaned.startsWith("_")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith("_")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        String name = method.toLowerCase(Locale.ROOT) + "_" + (cleaned.isEmpty() ? "root" : cleaned);
        return toCamelCase(name);
    }

    private static String uniqueName(String base, Set<String> used) {
        if (used.add(base)) return base;
        int i = 2;
        while (!used.add(base + "_" + i)) i++;
        return base + "_" + i;
    }

    private static String toPascalCase(String raw) {
        return SchemaRecordEmitter.toPascalCase(raw);
    }

    private static String toCamelCase(String raw) {
        return SchemaRecordEmitter.toCamelCase(raw);
    }

    private static String escapeJavadoc(String s) {
        return SchemaRecordEmitter.escapeJavadoc(s);
    }
}
