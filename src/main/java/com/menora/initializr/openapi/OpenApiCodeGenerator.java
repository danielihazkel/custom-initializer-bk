package com.menora.initializr.openapi;

import com.menora.initializr.config.WizardTemplates;
import com.menora.initializr.openapi.SchemaRecordEmitter.JavaTypeRef;
import com.menora.initializr.openapi.SchemaRecordEmitter.SchemaSet;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an OpenAPI 3.x spec via swagger-parser and emits Spring MVC
 * controllers, clients and the DTO types they exchange ({@link SchemaRecordEmitter}).
 * One instance handles one spec at generation time; it is stateless and
 * {@code @Service}-scoped.
 */
@Service
public class OpenApiCodeGenerator {

    /** Raised to the controller layer; converted to HTTP 400. */
    public static class OpenApiParseException extends RuntimeException {
        private final List<String> messages;
        public OpenApiParseException(List<String> messages) {
            super(String.join("; ", messages == null ? List.of() : messages));
            this.messages = messages == null ? List.of() : List.copyOf(messages);
        }
        public List<String> messages() { return messages; }
    }

    /** The methods an operation can be declared under, in the order they are written. */
    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /** What a 2xx status is called in {@code HttpStatus}. 200 is what a method answers anyway. */
    private static final Map<Integer, String> STATUS_NAMES = Map.of(
            201, "CREATED", 202, "ACCEPTED", 203, "NON_AUTHORITATIVE_INFORMATION", 204, "NO_CONTENT",
            205, "RESET_CONTENT", 206, "PARTIAL_CONTENT");

    /** Names the generated methods use themselves: a parameter called one of these is renamed. */
    private static final Set<String> RESERVED_PARAMETER_NAMES = Set.of(
            "body", "uri", "builder", "headers", "response", "restTemplate", "restClient", "baseUrl",
            "uriBuilder", "httpHeaders");

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");

    public List<GeneratedOpenApiFile> generate(String spec, String packageName, OpenApiWizardOptions options) {
        // Default: do not emit Bean Validation. The 3-arg path is used by the discard-only
        // spec parse-check, which adds no validation starter.
        return generate(spec, packageName, options, false);
    }

    /**
     * @param hasValidation whether the {@code validation} starter is on the generated project's
     *                      build — gates {@code @Valid} and the constraint annotations, which
     *                      otherwise wouldn't resolve at compile time.
     */
    public List<GeneratedOpenApiFile> generate(String spec, String packageName,
                                               OpenApiWizardOptions options, boolean hasValidation) {
        if (spec == null || spec.isBlank()) return List.of();
        OpenAPI api = parseOrThrow(spec);
        OpenApiWizardOptions opts = options != null ? options
                : new OpenApiWizardOptions(null, null, null, null, null);

        String dtoPackage = packageName + "." + opts.dtoPackageOrDefault();
        SchemaSet schemas = SchemaRecordEmitter.read(api, hasValidation, dtoPackage);
        // Reading the operations names the objects written inside them (a request body, a response).
        List<OperationModel> operations = buildOperations(api, schemas);

        List<GeneratedOpenApiFile> out = new ArrayList<>();
        for (SchemaModel s : schemas.models().values()) {
            out.add(new GeneratedOpenApiFile(
                    "src/main/java/{{packagePath}}/" + opts.dtoPackageOrDefault() + "/" + s.name() + ".java",
                    SchemaRecordEmitter.render(s, dtoPackage)));
        }
        Map<String, List<OperationModel>> byTag = groupByTag(operations);
        if (opts.emitControllers()) {
            for (var group : byTag.entrySet()) {
                out.add(renderController(group.getKey(), group.getValue(), packageName, opts, hasValidation));
            }
        }
        if (opts.emitClient() && !byTag.isEmpty()) {
            boolean restClient = opts.clientKindOrDefault() == OpenApiWizardOptions.ClientKind.REST_CLIENT;
            for (var group : byTag.entrySet()) {
                out.add(renderClient(group.getKey(), group.getValue(), packageName, opts, restClient));
            }
            out.add(renderClientConfig(packageName, opts, restClient));
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
            for (String method : METHODS) {
                if (operationOf(item, method) != null) out.add(method + " " + e.getKey());
            }
        }
        return out;
    }

    private static Operation operationOf(PathItem item, String method) {
        return switch (method) {
            case "GET" -> item.getGet();
            case "POST" -> item.getPost();
            case "PUT" -> item.getPut();
            case "DELETE" -> item.getDelete();
            case "PATCH" -> item.getPatch();
            case "HEAD" -> item.getHead();
            case "OPTIONS" -> item.getOptions();
            default -> null;
        };
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

    // ── Operations ────────────────────────────────────────────────────────────

    private List<OperationModel> buildOperations(OpenAPI api, SchemaSet schemas) {
        List<OperationModel> out = new ArrayList<>();
        if (api.getPaths() == null) return out;
        Set<String> usedOpIds = new LinkedHashSet<>();
        for (var e : api.getPaths().entrySet()) {
            PathItem item = e.getValue();
            if (item == null) continue;
            // HEAD and OPTIONS were listed by the preview and then left out of the code.
            for (String method : METHODS) {
                Operation op = operationOf(item, method);
                if (op != null) out.add(toOperation(usedOpIds, schemas, method, e.getKey(), item, op));
            }
        }
        return out;
    }

    @SuppressWarnings("rawtypes")
    private OperationModel toOperation(Set<String> usedOpIds, SchemaSet schemas, String method, String path,
                                       PathItem item, Operation op) {
        String opIdRaw = op.getOperationId();
        String opId = (opIdRaw == null || opIdRaw.isBlank())
                ? fallbackOpId(method, path) : SchemaRecordEmitter.javaName(opIdRaw);
        opId = uniqueName(opId, usedOpIds);
        String typeStem = toPascalCase(opId);
        String tag = (op.getTags() == null || op.getTags().isEmpty())
                ? "Default" : toPascalCase(op.getTags().get(0));

        List<ParamModel> params = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();
        for (Parameter p : parametersOf(item, op)) {
            ParamModel.ParamLocation loc = switch (String.valueOf(p.getIn()).toLowerCase(Locale.ROOT)) {
                case "path" -> ParamModel.ParamLocation.PATH;
                case "header" -> ParamModel.ParamLocation.HEADER;
                case "cookie" -> ParamModel.ParamLocation.COOKIE;
                default -> ParamModel.ParamLocation.QUERY;
            };
            Schema schema = p.getSchema();
            if (schema == null && p.getContent() != null) {
                MediaType media = pickJsonOrFirst(p.getContent());
                schema = media == null ? null : media.getSchema();
            }
            JavaTypeRef type = schema == null ? new JavaTypeRef("String", Set.of()) : schemas.parameterTypeOf(schema);
            Set<String> annotationImports = new LinkedHashSet<>();
            // "required" is the binding annotation's to say; the rest are the schema's constraints.
            List<String> annotations = new ArrayList<>(schemas.constraintsOf(schema, type, false, annotationImports));
            String format = dateFormatOf(type.typeName());
            if (format != null) {
                // Without it Spring reads a date in the server locale's short style, not ISO 8601.
                annotations.add("@DateTimeFormat(iso = DateTimeFormat.ISO." + format + ")");
                annotationImports.add("org.springframework.format.annotation.DateTimeFormat");
            }
            params.add(new ParamModel(
                    parameterName(p.getName(), usedNames),
                    p.getName(),
                    loc,
                    type.typeName(),
                    type.imports(),
                    // A path variable is always there.
                    loc == ParamModel.ParamLocation.PATH || Boolean.TRUE.equals(p.getRequired()),
                    List.copyOf(annotations),
                    annotationImports));
        }

        String reqType = null;
        Set<String> reqImports = Set.of();
        boolean reqRequired = true;
        RequestBody rb = op.getRequestBody();
        if (rb != null && rb.getContent() != null) {
            MediaType mt = pickJsonOrFirst(rb.getContent());
            if (mt != null && mt.getSchema() != null) {
                JavaTypeRef tr = schemas.typeOf(mt.getSchema(), typeStem + "Request");
                reqType = tr.typeName();
                reqImports = tr.imports();
                // Only a document that says "required: false" gets an optional body; one that says
                // nothing keeps Spring's default, as before.
                reqRequired = !Boolean.FALSE.equals(rb.getRequired());
            }
        }

        String respType = null;
        Set<String> respImports = Set.of();
        int status = 200;
        ApiResponses responses = op.getResponses();
        if (responses != null) {
            String code = successCodeOf(responses);
            ApiResponse ok = code == null ? null : responses.get(code);
            if (code != null && code.matches("2\\d\\d")) status = Integer.parseInt(code);
            if (ok != null && ok.getContent() != null) {
                MediaType mt = pickJsonOrFirst(ok.getContent());
                if (mt != null && mt.getSchema() != null) {
                    JavaTypeRef tr = schemas.typeOf(mt.getSchema(), typeStem + "Response");
                    respType = tr.typeName();
                    respImports = tr.imports();
                }
            }
        }

        return new OperationModel(method, path, opId, tag, op.getSummary(),
                params, reqType, reqImports, reqRequired, respType, respImports, status);
    }

    /** The operation's parameters, after those declared once for the whole path (which it may override). */
    private static List<Parameter> parametersOf(PathItem item, Operation op) {
        Map<String, Parameter> byKey = new LinkedHashMap<>();
        if (item.getParameters() != null) {
            for (Parameter p : item.getParameters()) if (p != null && p.getName() != null) byKey.put(p.getIn() + ":" + p.getName(), p);
        }
        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) if (p != null && p.getName() != null) byKey.put(p.getIn() + ":" + p.getName(), p);
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * The response that describes success: the first 2xx the operation declares, lowest first,
     * then a {@code 2XX} range, then {@code default}. (A 204 used to lose to {@code default}, so
     * a delete returned the error body's type.)
     */
    private static String successCodeOf(ApiResponses responses) {
        String best = null;
        for (String code : responses.keySet()) {
            if (code.matches("2\\d\\d") && (best == null || code.compareTo(best) < 0)) best = code;
        }
        if (best != null) return best;
        for (String code : responses.keySet()) {
            if (code.equalsIgnoreCase("2XX")) return code;
        }
        return responses.containsKey("default") ? "default" : null;
    }

    private static String dateFormatOf(String javaType) {
        String base = javaType.startsWith("List<") ? javaType.substring(5, javaType.length() - 1) : javaType;
        return switch (base) {
            case "LocalDate" -> "DATE";
            case "OffsetDateTime" -> "DATE_TIME";
            default -> null;
        };
    }

    private static String parameterName(String wire, Set<String> used) {
        String name = SchemaRecordEmitter.javaName(wire);
        if (RESERVED_PARAMETER_NAMES.contains(name)) name = name + "Param";
        String candidate = name;
        for (int n = 2; !used.add(candidate); n++) candidate = name + n;
        return candidate;
    }

    private Map<String, List<OperationModel>> groupByTag(List<OperationModel> ops) {
        Map<String, List<OperationModel>> map = new LinkedHashMap<>();
        for (OperationModel op : ops) {
            map.computeIfAbsent(op.tag(), k -> new ArrayList<>()).add(op);
        }
        return map;
    }

    // ── Operations → controllers ──────────────────────────────────────────────

    private GeneratedOpenApiFile renderController(String tag, List<OperationModel> ops,
                                                  String packageName, OpenApiWizardOptions opts,
                                                  boolean hasValidation) {
        String apiPkg = opts.apiPackageOrDefault();
        String className = tag + "Controller";

        Set<String> imports = new TreeSet<>();
        imports.add("org.springframework.web.bind.annotation.RestController");
        imports.add("org.springframework.web.bind.annotation.RequestMapping");
        imports.add("org.springframework.validation.annotation.Validated");

        boolean hasBody = false;
        for (OperationModel op : ops) {
            if (isPlainMethod(op.httpMethod())) {
                imports.add("org.springframework.web.bind.annotation." + mappingAnnotation(op.httpMethod()));
            } else {
                imports.add("org.springframework.web.bind.annotation.RequestMethod");
            }
            if (op.requestBodyType() != null) hasBody = true;
            if (STATUS_NAMES.containsKey(op.successStatus())) {
                imports.add("org.springframework.web.bind.annotation.ResponseStatus");
                imports.add("org.springframework.http.HttpStatus");
            }
            for (ParamModel p : op.params()) {
                imports.add("org.springframework.web.bind.annotation." + switch (p.in()) {
                    case PATH -> "PathVariable";
                    case QUERY -> "RequestParam";
                    case HEADER -> "RequestHeader";
                    case COOKIE -> "CookieValue";
                });
                imports.addAll(p.typeImports());
                imports.addAll(p.annotationImports());
            }
            imports.addAll(op.requestBodyImports());
            imports.addAll(op.responseImports());
        }
        if (hasBody) {
            imports.add("org.springframework.web.bind.annotation.RequestBody");
            if (hasValidation) imports.add("jakarta.validation.Valid");
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", packageName + "." + apiPkg);
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
        String path = "\"" + SchemaRecordEmitter.javaString(op.path()) + "\"";
        m.put("mappingLine", isPlainMethod(op.httpMethod())
                ? "@" + mappingAnnotation(op.httpMethod()) + "(" + path + ")"
                : "@RequestMapping(value = " + path + ", method = RequestMethod." + op.httpMethod() + ")");
        // The status the document declares for success, where it is not the 200 a method answers anyway.
        String statusName = STATUS_NAMES.get(op.successStatus());
        m.put("hasStatus", statusName != null);
        m.put("statusLine", statusName == null ? "" : "@ResponseStatus(HttpStatus." + statusName + ")");

        List<String> args = new ArrayList<>();
        for (ParamModel p : op.params()) {
            StringBuilder arg = new StringBuilder();
            for (String annotation : p.annotations()) arg.append(annotation).append(' ');
            // The request's own name, not the Java one: "X-Request-Id" is not "xRequestId".
            String wire = SchemaRecordEmitter.javaString(p.wireName());
            switch (p.in()) {
                case PATH -> arg.append("@PathVariable(\"").append(wire).append("\") ");
                case QUERY -> arg.append("@RequestParam(name = \"").append(wire)
                        .append("\", required = ").append(p.required()).append(") ");
                case HEADER -> arg.append("@RequestHeader(name = \"").append(wire)
                        .append("\", required = ").append(p.required()).append(") ");
                case COOKIE -> arg.append("@CookieValue(name = \"").append(wire)
                        .append("\", required = ").append(p.required()).append(") ");
            }
            arg.append(p.javaType()).append(' ').append(p.name());
            args.add(arg.toString());
        }
        if (op.requestBodyType() != null) {
            args.add((hasValidation ? "@Valid " : "")
                    + (op.requestBodyRequired() ? "@RequestBody " : "@RequestBody(required = false) ")
                    + op.requestBodyType() + " body");
        }
        m.put("arguments", String.join(", ", args));
        return m;
    }

    /** What both the controller and the client template read of an operation. */
    private static Map<String, Object> operationView(OperationModel op) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean summarized = op.summary() != null && !op.summary().isBlank();
        m.put("hasSummary", summarized);
        m.put("summaryJavadoc", summarized ? SchemaRecordEmitter.escapeJavadoc(op.summary()) : null);
        m.put("path", SchemaRecordEmitter.javaString(op.path()));
        m.put("returnType", op.responseType() == null ? "void" : op.responseType());
        m.put("operationId", op.operationId());
        return m;
    }

    private static boolean isPlainMethod(String httpMethod) {
        return !httpMethod.equals("HEAD") && !httpMethod.equals("OPTIONS");
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

    // ── Operations → client ───────────────────────────────────────────────────

    private GeneratedOpenApiFile renderClient(String tag, List<OperationModel> ops, String packageName,
                                              OpenApiWizardOptions opts, boolean restClient) {
        String clientPkg = opts.clientPackageOrDefault();
        String className = tag + "Client";

        Set<String> imports = new TreeSet<>();
        imports.add("org.springframework.http.HttpMethod");
        imports.add("org.springframework.stereotype.Component");
        if (restClient) {
            imports.add("org.springframework.web.client.RestClient");
        } else {
            imports.add("org.springframework.beans.factory.annotation.Value");
            imports.add("org.springframework.http.HttpEntity");
            imports.add("org.springframework.http.HttpHeaders");
            imports.add("org.springframework.http.ResponseEntity");
            imports.add("org.springframework.web.client.RestTemplate");
            imports.add("org.springframework.web.util.UriComponentsBuilder");
        }
        for (OperationModel op : ops) {
            for (ParamModel p : op.params()) {
                imports.addAll(p.typeImports());
                if (restClient && p.in() == ParamModel.ParamLocation.QUERY) imports.add("java.util.Optional");
                if (restClient && p.in() == ParamModel.ParamLocation.COOKIE) imports.add("org.springframework.http.HttpHeaders");
            }
            imports.addAll(op.requestBodyImports());
            imports.addAll(op.responseImports());
            if (isGeneric(op.responseType())) imports.add("org.springframework.core.ParameterizedTypeReference");
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", packageName + "." + clientPkg);
        view.put("imports", imports);
        view.put("className", className);
        // Written whole: "${" beside a Mustache tag would read as a triple brace.
        view.put("baseUrlPlaceholder", "${" + opts.baseUrlPropertyOrDefault() + "}");
        List<Map<String, Object>> methods = new ArrayList<>();
        for (OperationModel op : ops) methods.add(clientMethod(op));
        view.put("operations", methods);

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/" + className + ".java",
                WizardTemplates.render(restClient ? "openapi/RestClientClient.java" : "openapi/Client.java", view));
    }

    /**
     * One operation as the client templates read it: the URI is the base URL plus the path, with
     * the path variables expanded and the query parameters appended (an optional one only when
     * given); headers and cookies likewise; the body travels with them.
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
        List<Map<String, Object>> cookieParams = new ArrayList<>();
        Map<String, String> pathNames = new LinkedHashMap<>();
        for (ParamModel p : op.params()) {
            Map<String, Object> pv = new LinkedHashMap<>();
            pv.put("name", p.name());
            pv.put("wireName", SchemaRecordEmitter.javaString(p.wireName()));
            pv.put("required", p.required());
            switch (p.in()) {
                case QUERY -> queryParams.add(pv);
                case HEADER -> headerParams.add(pv);
                case COOKIE -> cookieParams.add(pv);
                case PATH -> pathNames.put(p.wireName(), p.name());
            }
        }
        // The variables are expanded by position: in the order the path names them, which need
        // not be the order the document lists the parameters in.
        List<String> pathArguments = new ArrayList<>();
        Matcher variable = PATH_VARIABLE.matcher(op.path());
        while (variable.find()) {
            String name = pathNames.get(variable.group(1));
            // A variable the document never declared: the call would not compile without a value.
            pathArguments.add(name != null ? name : "\"" + SchemaRecordEmitter.javaString(variable.group(1)) + "\"");
        }
        m.put("queryParams", queryParams);
        m.put("hasPathParams", !pathArguments.isEmpty());
        m.put("pathArguments", String.join(", ", pathArguments));
        boolean hasHeader = !headerParams.isEmpty() || !cookieParams.isEmpty();
        m.put("hasHeaders", hasHeader);
        m.put("headerParams", headerParams);
        m.put("cookieParams", cookieParams);
        m.put("hasBody", op.requestBodyType() != null);

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

        String respTypeArg;
        if (op.responseType() == null) {
            respTypeArg = "Void.class";
        } else if (isGeneric(op.responseType())) {
            respTypeArg = "new ParameterizedTypeReference<" + op.responseType() + ">() {}";
        } else {
            respTypeArg = op.responseType() + ".class";
        }
        m.put("httpMethod", op.httpMethod().toUpperCase(Locale.ROOT));
        m.put("responseBoxed", op.responseType() == null ? "Void" : op.responseType());
        m.put("responseTypeArg", respTypeArg);
        m.put("returnsBody", op.responseType() != null);
        // RestClient: the call is one expression.
        m.put("returnKeyword", op.responseType() == null ? "" : "return ");
        m.put("terminal", op.responseType() == null ? "toBodilessEntity()" : "body(" + respTypeArg + ")");
        return m;
    }

    private static boolean isGeneric(String type) {
        return type != null && type.indexOf('<') >= 0;
    }

    private GeneratedOpenApiFile renderClientConfig(String packageName, OpenApiWizardOptions opts, boolean restClient) {
        String clientPkg = opts.clientPackageOrDefault();
        String fullPkg = packageName + "." + clientPkg;
        String baseUrlProp = opts.baseUrlPropertyOrDefault();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("baseUrlProperty", baseUrlProp);
        view.put("baseUrlPlaceholder", "${" + baseUrlProp + "}");

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/OpenApiClientConfig.java",
                WizardTemplates.render(restClient ? "openapi/RestClientConfig.java" : "openapi/ClientConfig.java", view));
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

    // ── Helpers ──────────────────────────────────────────────────────────────

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
        return SchemaRecordEmitter.toCamelCase(name);
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
}
