package com.menora.initializr.openapi;

import com.menora.initializr.config.WizardTemplates;
import com.menora.initializr.gen.WizardMemo;
import com.menora.initializr.openapi.SchemaRecordEmitter.JavaTypeRef;
import com.menora.initializr.openapi.SchemaRecordEmitter.SchemaSet;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
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
 * controllers (or interfaces and their implementations), clients and the DTO types they exchange
 * ({@link SchemaRecordEmitter}). One instance handles one spec at generation time; it is stateless
 * and {@code @Service}-scoped.
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
            "uriBuilder", "httpHeaders", "form", "credentials");

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");

    private static final String MULTIPART = "multipart/form-data";
    private static final String FORM = "application/x-www-form-urlencoded";

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
        return generate(spec, packageName, options, hasValidation, false);
    }

    /**
     * @param springdoc whether springdoc (the {@code openapi} dependency) is on the build: the
     *                  document's security schemes are then declared for the generated API
     *                  documentation, and each operation names the ones it requires.
     */
    public List<GeneratedOpenApiFile> generate(String spec, String packageName, OpenApiWizardOptions options,
                                               boolean hasValidation, boolean springdoc) {
        if (spec == null || spec.isBlank()) return List.of();
        OpenApiWizardOptions opts = options != null ? options
                : new OpenApiWizardOptions(null, null, null, null, null);
        // A wizard request checks its spec by generating it, then writes what the same inputs give.
        return WizardMemo.get(WizardMemo.Key.of("openapi", spec, packageName, opts, hasValidation, springdoc),
                () -> render(spec, packageName, opts, hasValidation, springdoc));
    }

    private List<GeneratedOpenApiFile> render(String spec, String packageName, OpenApiWizardOptions opts,
                                              boolean hasValidation, boolean springdoc) {
        OpenAPI api = parseOrThrow(spec);

        String dtoPackage = packageName + "." + opts.dtoPackageOrDefault();
        SchemaSet schemas = SchemaRecordEmitter.read(api, hasValidation, dtoPackage);
        Map<String, SecurityUse> schemes = securitySchemes(api);
        // Reading the operations names the objects written inside them (a request body, a response).
        List<OperationModel> operations = buildOperations(api, schemas, schemes);

        List<GeneratedOpenApiFile> out = new ArrayList<>();
        for (SchemaModel s : schemas.models().values()) {
            out.add(new GeneratedOpenApiFile(
                    "src/main/java/{{packagePath}}/" + opts.dtoPackageOrDefault() + "/" + s.name() + ".java",
                    SchemaRecordEmitter.render(s, dtoPackage)));
        }
        Map<String, List<OperationModel>> byTag = groupByTag(operations);
        boolean documented = springdoc && operations.stream().anyMatch(op -> !op.security().isEmpty());
        if (opts.emitControllers()) {
            for (var group : byTag.entrySet()) {
                if (opts.interfaces()) {
                    out.add(renderApi(group.getKey(), group.getValue(), packageName, opts, hasValidation, documented));
                    out.add(renderApiController(group.getKey(), packageName, opts));
                } else {
                    out.add(renderController(group.getKey(), group.getValue(), packageName, opts, hasValidation, documented));
                }
            }
            if (documented) out.add(renderSecuritySchemes(api, packageName, opts));
        }
        if (opts.emitClient() && !byTag.isEmpty()) {
            boolean restClient = opts.clientKindOrDefault() == OpenApiWizardOptions.ClientKind.REST_CLIENT;
            Map<String, CredentialField> credentials = credentialFields(operations, opts);
            for (var group : byTag.entrySet()) {
                out.add(renderClient(group.getKey(), group.getValue(), packageName, opts, restClient, credentials));
            }
            boolean oauth2 = operations.stream().flatMap(op -> op.clientSecurity().stream())
                    .anyMatch(use -> use.kind() == SecurityUse.Kind.OAUTH2_CLIENT_CREDENTIALS);
            out.add(renderClientConfig(packageName, opts, restClient, oauth2));
            if (!credentials.isEmpty()) out.add(renderCredentials(packageName, opts, credentials, operations));
            if (oauth2) out.add(renderOAuth2Tokens(packageName, opts));
            out.add(renderApplicationYmlStub(opts, credentials));
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
        // The parser fetches what a document refers to; one that refers outside itself is refused.
        SelfContainedDocument.Checked document;
        try {
            document = SelfContainedDocument.check(spec);
        } catch (SelfContainedDocument.ExternalReferenceException e) {
            throw new OpenApiParseException(e.messages());
        }
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(document.text(), null, document.parseOptions());
        if (result.getOpenAPI() == null) {
            List<String> msgs = result.getMessages() == null || result.getMessages().isEmpty()
                    ? List.of("Unrecognised OpenAPI document") : result.getMessages();
            throw new OpenApiParseException(msgs);
        }
        return result.getOpenAPI();
    }

    // ── Operations ────────────────────────────────────────────────────────────

    private List<OperationModel> buildOperations(OpenAPI api, SchemaSet schemas, Map<String, SecurityUse> schemes) {
        List<OperationModel> out = new ArrayList<>();
        if (api.getPaths() == null) return out;
        Set<String> usedOpIds = new LinkedHashSet<>();
        for (var e : api.getPaths().entrySet()) {
            PathItem item = e.getValue();
            if (item == null) continue;
            // HEAD and OPTIONS were listed by the preview and then left out of the code.
            for (String method : METHODS) {
                Operation op = operationOf(item, method);
                if (op != null) out.add(toOperation(api, usedOpIds, schemas, schemes, method, e.getKey(), item, op));
            }
        }
        return out;
    }

    @SuppressWarnings("rawtypes")
    private OperationModel toOperation(OpenAPI api, Set<String> usedOpIds, SchemaSet schemas, Map<String, SecurityUse> schemes,
                                       String method, String path, PathItem item, Operation op) {
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
            addDateFormat(type, annotations, annotationImports);
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
        OperationModel.BodyKind bodyKind = OperationModel.BodyKind.NONE;
        List<FormField> formFields = List.of();
        RequestBody rb = op.getRequestBody();
        if (rb != null && rb.getContent() != null) {
            Content content = rb.getContent();
            MediaType multipart = content.get(MULTIPART);
            MediaType form = content.get(FORM);
            // JSON when the document offers it; a form only when it is the body's way in.
            if (content.get("application/json") == null && multipart != null && multipart.getSchema() != null) {
                bodyKind = OperationModel.BodyKind.MULTIPART;
                reqRequired = !Boolean.FALSE.equals(rb.getRequired());
                formFields = formFields(schemas, multipart.getSchema(), typeStem, true, reqRequired, usedNames);
            } else if (content.get("application/json") == null && form != null && form.getSchema() != null) {
                bodyKind = OperationModel.BodyKind.FORM;
                reqRequired = !Boolean.FALSE.equals(rb.getRequired());
                formFields = formFields(schemas, form.getSchema(), typeStem, false, reqRequired, usedNames);
            } else {
                MediaType mt = pickJsonOrFirst(content);
                if (mt != null && mt.getSchema() != null) {
                    JavaTypeRef tr = schemas.typeOf(mt.getSchema(), typeStem + "Request");
                    reqType = tr.typeName();
                    reqImports = tr.imports();
                    // Only a document that says "required: false" gets an optional body; one that says
                    // nothing keeps Spring's default, as before.
                    reqRequired = !Boolean.FALSE.equals(rb.getRequired());
                    bodyKind = OperationModel.BodyKind.JSON;
                }
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
                params, reqType, reqImports, reqRequired, respType, respImports, status,
                bodyKind, formFields, securityOf(api, op, schemes));
    }

    /**
     * The properties of a form body, each a parameter of its own: a binary one a file
     * ({@code MultipartFile} in the controller, a {@code Resource} in the client), an object in a
     * multipart body a JSON part, anything else a plain field. A form-urlencoded body has no parts:
     * an object there travels as text.
     */
    @SuppressWarnings("rawtypes")
    private static List<FormField> formFields(SchemaSet schemas, Schema body, String typeStem, boolean multipart,
                                              boolean bodyRequired, Set<String> usedNames) {
        Set<String> required = new LinkedHashSet<>();
        Map<String, Schema> props = schemas.propertiesOf(body, required);
        List<FormField> out = new ArrayList<>();
        for (var e : props.entrySet()) {
            String wire = e.getKey();
            Schema prop = e.getValue();
            boolean req = bodyRequired && required.contains(wire);
            String name = parameterName(wire, usedNames);
            if (multipart && schemas.isBinary(prop)) {
                out.add(new FormField(name, wire, FormField.Part.FILE,
                        "MultipartFile", Set.of("org.springframework.web.multipart.MultipartFile"),
                        "Resource", Set.of("org.springframework.core.io.Resource"), req, List.of(), Set.of()));
                continue;
            }
            if (multipart && schemas.isBinaryArray(prop)) {
                out.add(new FormField(name, wire, FormField.Part.FILES,
                        "List<MultipartFile>", Set.of("java.util.List", "org.springframework.web.multipart.MultipartFile"),
                        "List<Resource>", Set.of("java.util.List", "org.springframework.core.io.Resource"), req, List.of(), Set.of()));
                continue;
            }
            JavaTypeRef type = schemas.parameterTypeOf(prop);
            FormField.Part kind = FormField.Part.FIELD;
            if (isObject(schemas, type)) {
                if (multipart) {
                    type = schemas.typeOf(prop, typeStem + toPascalCase(wire));
                    kind = FormField.Part.JSON;
                } else {
                    type = new JavaTypeRef("String", Set.of());
                }
            }
            Set<String> annotationImports = new LinkedHashSet<>();
            List<String> annotations = new ArrayList<>(schemas.constraintsOf(prop, type, false, annotationImports));
            if (kind == FormField.Part.FIELD) addDateFormat(type, annotations, annotationImports);
            out.add(new FormField(name, wire, kind, type.typeName(), type.imports(), type.typeName(), type.imports(),
                    req, List.copyOf(annotations), annotationImports));
        }
        return out;
    }

    /** An object: a record, a union, a map or an untyped one — not something a form field holds. */
    private static boolean isObject(SchemaSet schemas, JavaTypeRef type) {
        String name = type.typeName();
        if (name.equals("Object") || name.startsWith("Map<")) return true;
        SchemaModel model = schemas.models().get(name);
        return model != null && model.kind() != SchemaModel.Kind.ENUM;
    }

    private static void addDateFormat(JavaTypeRef type, List<String> annotations, Set<String> imports) {
        String format = dateFormatOf(type.typeName());
        if (format != null) {
            // Without it Spring reads a date in the server locale's short style, not ISO 8601.
            annotations.add("@DateTimeFormat(iso = DateTimeFormat.ISO." + format + ")");
            imports.add("org.springframework.format.annotation.DateTimeFormat");
        }
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

    // ── Security ──────────────────────────────────────────────────────────────

    /** Every {@code components.securitySchemes} entry, by its name in the document. */
    private static Map<String, SecurityUse> securitySchemes(OpenAPI api) {
        Map<String, SecurityUse> out = new LinkedHashMap<>();
        if (api.getComponents() == null || api.getComponents().getSecuritySchemes() == null) return out;
        Set<String> used = new LinkedHashSet<>();
        api.getComponents().getSecuritySchemes().forEach((name, scheme) -> {
            if (scheme == null || scheme.getType() == null) return;
            out.put(name, useOf(name, uniqueName(SchemaRecordEmitter.javaName(name), used), scheme));
        });
        return out;
    }

    private static SecurityUse useOf(String name, String javaName, SecurityScheme scheme) {
        return switch (scheme.getType()) {
            case APIKEY -> {
                String in = scheme.getIn() == null ? "header" : scheme.getIn().toString().toLowerCase(Locale.ROOT);
                SecurityUse.Kind kind = switch (in) {
                    case "query" -> SecurityUse.Kind.API_KEY_QUERY;
                    case "cookie" -> SecurityUse.Kind.API_KEY_COOKIE;
                    default -> SecurityUse.Kind.API_KEY_HEADER;
                };
                String param = scheme.getName() == null || scheme.getName().isBlank() ? "api_key" : scheme.getName();
                yield new SecurityUse(name, javaName, kind, param, "API key in " + in + " " + param);
            }
            case HTTP -> {
                String s = scheme.getScheme() == null ? "" : scheme.getScheme().toLowerCase(Locale.ROOT);
                yield switch (s) {
                    case "bearer" -> new SecurityUse(name, javaName, SecurityUse.Kind.BEARER, null, "HTTP bearer token");
                    case "basic" -> new SecurityUse(name, javaName, SecurityUse.Kind.BASIC, null, "HTTP basic");
                    default -> new SecurityUse(name, javaName, SecurityUse.Kind.OTHER, null, "HTTP " + s);
                };
            }
            case OAUTH2 -> {
                var flow = scheme.getFlows() == null ? null : scheme.getFlows().getClientCredentials();
                if (flow != null && flow.getTokenUrl() != null && !flow.getTokenUrl().isBlank()) {
                    List<String> scopes = flow.getScopes() == null ? List.of() : new ArrayList<>(flow.getScopes().keySet());
                    yield new SecurityUse(name, javaName, SecurityUse.Kind.OAUTH2_CLIENT_CREDENTIALS, null,
                            "OAuth2 client credentials", flow.getTokenUrl(), scopes);
                }
                yield new SecurityUse(name, javaName, SecurityUse.Kind.BEARER, null, "OAuth2 access token");
            }
            case OPENIDCONNECT -> new SecurityUse(name, javaName, SecurityUse.Kind.BEARER, null, "OpenID Connect token");
            case MUTUALTLS -> new SecurityUse(name, javaName, SecurityUse.Kind.OTHER, null, "mutual TLS");
        };
    }

    /**
     * The requirements an operation declares — its own, else the document's. Each is an
     * alternative; an empty one ({@code {}}) says the operation may be called without any, and so
     * does an empty list. A scheme the document does not define is left out.
     */
    private static List<List<SecurityUse>> securityOf(OpenAPI api, Operation op, Map<String, SecurityUse> schemes) {
        List<SecurityRequirement> requirements = op.getSecurity() != null ? op.getSecurity() : api.getSecurity();
        if (requirements == null) return List.of();
        List<List<SecurityUse>> out = new ArrayList<>();
        boolean any = false;
        for (SecurityRequirement requirement : requirements) {
            List<SecurityUse> alternative = new ArrayList<>();
            if (requirement != null) {
                for (String name : requirement.keySet()) {
                    SecurityUse use = schemes.get(name);
                    if (use != null) alternative.add(use);
                }
            }
            any |= !alternative.isEmpty();
            out.add(List.copyOf(alternative));
        }
        return any ? List.copyOf(out) : List.of();
    }

    /**
     * Javadoc for a server method: the summary as before, and — when the operation is secured and
     * springdoc does not say so — which schemes it requires. Lines with their indentation.
     */
    private static List<String> serverJavadoc(OperationModel op, boolean documented) {
        boolean summarized = op.summary() != null && !op.summary().isBlank();
        String summary = summarized ? SchemaRecordEmitter.escapeJavadoc(op.summary()) : null;
        if (op.security().isEmpty() || documented) {
            return summarized ? List.of("    /** " + summary + " */") : List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("    /**");
        if (summarized) {
            lines.add("     * " + summary);
            lines.add("     *");
        }
        lines.add("     * " + (summarized ? "<p>" : "") + "Security: " + SchemaRecordEmitter.escapeJavadoc(requirementText(op.security())) + ".");
        lines.add("     * Not checked here: the gateway, or the application's own security, enforces it.");
        lines.add("     */");
        return lines;
    }

    /** {@code api_key (API key in header X-API-Key) or petstore_auth (OAuth2 access token)}. */
    private static String requirementText(List<List<SecurityUse>> requirements) {
        List<String> alternatives = new ArrayList<>();
        for (List<SecurityUse> alternative : requirements) {
            if (alternative.isEmpty()) {
                alternatives.add("none");
                continue;
            }
            alternatives.add(String.join(" and ", alternative.stream().map(u -> u.scheme() + " (" + u.label() + ")").toList()));
        }
        return String.join(" or ", alternatives);
    }

    /** springdoc's {@code @SecurityRequirement} per scheme an operation may be called with. */
    private static List<String> securityAnnotations(OperationModel op, boolean documented) {
        if (!documented) return List.of();
        Set<String> names = new LinkedHashSet<>();
        for (List<SecurityUse> alternative : op.security()) {
            for (SecurityUse use : alternative) names.add(use.scheme());
        }
        return names.stream()
                .map(n -> "@SecurityRequirement(name = \"" + SchemaRecordEmitter.javaString(n) + "\")")
                .toList();
    }

    /** An OAuth2 scheme's flows as springdoc's {@code @OAuthFlows} (Swagger UI's Authorize uses them), or null for none. */
    private static String oauthFlows(io.swagger.v3.oas.models.security.OAuthFlows flows) {
        if (flows == null) return null;
        List<String> out = new ArrayList<>();
        addFlow(out, "implicit", flows.getImplicit());
        addFlow(out, "password", flows.getPassword());
        addFlow(out, "clientCredentials", flows.getClientCredentials());
        addFlow(out, "authorizationCode", flows.getAuthorizationCode());
        return out.isEmpty() ? null : "@OAuthFlows(" + String.join(", ", out) + ")";
    }

    private static void addFlow(List<String> out, String name, io.swagger.v3.oas.models.security.OAuthFlow flow) {
        if (flow == null) return;
        List<String> attributes = new ArrayList<>();
        if (flow.getAuthorizationUrl() != null) attributes.add("authorizationUrl = \"" + SchemaRecordEmitter.javaString(flow.getAuthorizationUrl()) + "\"");
        if (flow.getTokenUrl() != null) attributes.add("tokenUrl = \"" + SchemaRecordEmitter.javaString(flow.getTokenUrl()) + "\"");
        if (flow.getRefreshUrl() != null) attributes.add("refreshUrl = \"" + SchemaRecordEmitter.javaString(flow.getRefreshUrl()) + "\"");
        if (flow.getScopes() != null && !flow.getScopes().isEmpty()) {
            List<String> scopes = new ArrayList<>();
            flow.getScopes().forEach((scope, description) -> scopes.add("@OAuthScope(name = \"" + SchemaRecordEmitter.javaString(scope)
                    + "\", description = \"" + SchemaRecordEmitter.javaString(description == null ? "" : description) + "\")"));
            attributes.add("scopes = {" + String.join(", ", scopes) + "}");
        }
        out.add(name + " = @OAuthFlow(" + String.join(", ", attributes) + ")");
    }

    private GeneratedOpenApiFile renderSecuritySchemes(OpenAPI api, String packageName, OpenApiWizardOptions opts) {
        String apiPkg = opts.apiPackageOrDefault();
        Set<String> imports = new TreeSet<>(List.of(
                "io.swagger.v3.oas.annotations.enums.SecuritySchemeType",
                "io.swagger.v3.oas.annotations.security.SecurityScheme",
                "io.swagger.v3.oas.annotations.security.SecuritySchemes",
                "org.springframework.context.annotation.Configuration"));
        List<String> schemes = new ArrayList<>();
        api.getComponents().getSecuritySchemes().forEach((name, scheme) -> {
            if (scheme == null || scheme.getType() == null) return;
            StringBuilder a = new StringBuilder("@SecurityScheme(name = \"").append(SchemaRecordEmitter.javaString(name)).append("\"");
            switch (scheme.getType()) {
                case APIKEY -> {
                    imports.add("io.swagger.v3.oas.annotations.enums.SecuritySchemeIn");
                    String in = scheme.getIn() == null ? "HEADER" : scheme.getIn().toString().toUpperCase(Locale.ROOT);
                    a.append(", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.").append(in)
                            .append(", paramName = \"").append(SchemaRecordEmitter.javaString(
                                    scheme.getName() == null ? "api_key" : scheme.getName())).append("\"");
                }
                case HTTP -> {
                    a.append(", type = SecuritySchemeType.HTTP, scheme = \"")
                            .append(SchemaRecordEmitter.javaString(scheme.getScheme() == null ? "" : scheme.getScheme())).append("\"");
                    if (scheme.getBearerFormat() != null && !scheme.getBearerFormat().isBlank()) {
                        a.append(", bearerFormat = \"").append(SchemaRecordEmitter.javaString(scheme.getBearerFormat())).append("\"");
                    }
                }
                case OAUTH2 -> {
                    a.append(", type = SecuritySchemeType.OAUTH2");
                    String flows = oauthFlows(scheme.getFlows());
                    if (flows != null) {
                        imports.add("io.swagger.v3.oas.annotations.security.OAuthFlow");
                        imports.add("io.swagger.v3.oas.annotations.security.OAuthFlows");
                        if (flows.contains("@OAuthScope")) imports.add("io.swagger.v3.oas.annotations.security.OAuthScope");
                        a.append(", flows = ").append(flows);
                    }
                }
                case OPENIDCONNECT -> {
                    a.append(", type = SecuritySchemeType.OPENIDCONNECT");
                    if (scheme.getOpenIdConnectUrl() != null) {
                        a.append(", openIdConnectUrl = \"").append(SchemaRecordEmitter.javaString(scheme.getOpenIdConnectUrl())).append("\"");
                    }
                }
                case MUTUALTLS -> a.append(", type = SecuritySchemeType.MUTUALTLS");
            }
            if (scheme.getDescription() != null && !scheme.getDescription().isBlank()) {
                a.append(", description = \"").append(SchemaRecordEmitter.javaString(scheme.getDescription())).append("\"");
            }
            schemes.add(a.append(")").toString());
        });
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", packageName + "." + apiPkg);
        view.put("imports", imports);
        view.put("schemes", String.join(",\n        ", schemes));
        return new GeneratedOpenApiFile("src/main/java/{{packagePath}}/" + apiPkg + "/OpenApiSecuritySchemes.java",
                WizardTemplates.render("openapi/SecuritySchemes.java", view));
    }

    // ── Operations → controllers ──────────────────────────────────────────────

    private GeneratedOpenApiFile renderController(String tag, List<OperationModel> ops,
                                                  String packageName, OpenApiWizardOptions opts,
                                                  boolean hasValidation, boolean documented) {
        String apiPkg = opts.apiPackageOrDefault();
        String className = tag + "Controller";

        Set<String> imports = serverImports(ops, hasValidation, documented);
        imports.add("org.springframework.web.bind.annotation.RestController");
        imports.add("org.springframework.web.bind.annotation.RequestMapping");
        imports.add("org.springframework.validation.annotation.Validated");

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", packageName + "." + apiPkg);
        view.put("imports", imports);
        view.put("className", className);
        List<Map<String, Object>> methods = new ArrayList<>();
        for (OperationModel op : ops) methods.add(serverMethod(op, hasValidation, documented));
        view.put("operations", methods);

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + apiPkg + "/" + className + ".java",
                WizardTemplates.render("openapi/Controller.java", view));
    }

    /**
     * The interface of a tag's operations: every mapping, binding and constraint, each method a
     * default that answers 501. Regenerated whole from the document.
     */
    private GeneratedOpenApiFile renderApi(String tag, List<OperationModel> ops, String packageName,
                                           OpenApiWizardOptions opts, boolean hasValidation, boolean documented) {
        String apiPkg = opts.apiPackageOrDefault();
        Set<String> imports = serverImports(ops, hasValidation, documented);
        imports.add("org.springframework.http.HttpStatus");
        imports.add("org.springframework.web.server.ResponseStatusException");

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", packageName + "." + apiPkg);
        view.put("imports", imports);
        view.put("className", tag + "Api");
        view.put("implName", tag + "Controller");
        List<Map<String, Object>> methods = new ArrayList<>();
        for (OperationModel op : ops) methods.add(serverMethod(op, hasValidation, documented));
        view.put("operations", methods);
        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + apiPkg + "/" + tag + "Api.java",
                WizardTemplates.render("openapi/Api.java", view));
    }

    /**
     * The class that implements a tag's interface — the team's. It names nothing the document says
     * but the tag, so a change of the document never changes it, and an update never patches it.
     */
    private GeneratedOpenApiFile renderApiController(String tag, String packageName, OpenApiWizardOptions opts) {
        String apiPkg = opts.apiPackageOrDefault();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", packageName + "." + apiPkg);
        view.put("className", tag + "Controller");
        view.put("apiName", tag + "Api");
        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + apiPkg + "/" + tag + "Controller.java",
                WizardTemplates.render("openapi/ApiController.java", view));
    }

    /** What the methods of a controller or an API interface import. */
    private static Set<String> serverImports(List<OperationModel> ops, boolean hasValidation, boolean documented) {
        Set<String> imports = new TreeSet<>();
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
            if (op.isForm()) {
                imports.add("org.springframework.http.MediaType");
                for (FormField f : op.formFields()) {
                    imports.add("org.springframework.web.bind.annotation."
                            + (f.kind() == FormField.Part.FIELD ? "RequestParam" : "RequestPart"));
                    imports.addAll(f.imports());
                    imports.addAll(f.annotationImports());
                }
            }
            if (!securityAnnotations(op, documented).isEmpty()) {
                imports.add("io.swagger.v3.oas.annotations.security.SecurityRequirement");
            }
            imports.addAll(op.requestBodyImports());
            imports.addAll(op.responseImports());
        }
        if (hasBody) {
            imports.add("org.springframework.web.bind.annotation.RequestBody");
            if (hasValidation) imports.add("jakarta.validation.Valid");
        }
        return imports;
    }

    /** One operation as the controller and API templates read it. */
    private Map<String, Object> serverMethod(OperationModel op, boolean hasValidation, boolean documented) {
        Map<String, Object> m = operationView(op);
        m.put("javadoc", serverJavadoc(op, documented));
        m.put("securityAnnotations", securityAnnotations(op, documented));
        String path = "\"" + SchemaRecordEmitter.javaString(op.path()) + "\"";
        String consumes = switch (op.bodyKind()) {
            case MULTIPART -> "MediaType.MULTIPART_FORM_DATA_VALUE";
            case FORM -> "MediaType.APPLICATION_FORM_URLENCODED_VALUE";
            default -> null;
        };
        String mapping;
        if (isPlainMethod(op.httpMethod())) {
            mapping = consumes == null
                    ? "@" + mappingAnnotation(op.httpMethod()) + "(" + path + ")"
                    : "@" + mappingAnnotation(op.httpMethod()) + "(value = " + path + ", consumes = " + consumes + ")";
        } else {
            mapping = "@RequestMapping(value = " + path + ", method = RequestMethod." + op.httpMethod()
                    + (consumes == null ? "" : ", consumes = " + consumes) + ")";
        }
        m.put("mappingLine", mapping);
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
        for (FormField f : op.formFields()) {
            StringBuilder arg = new StringBuilder();
            for (String annotation : f.annotations()) arg.append(annotation).append(' ');
            arg.append(f.kind() == FormField.Part.FIELD ? "@RequestParam" : "@RequestPart")
                    .append("(name = \"").append(SchemaRecordEmitter.javaString(f.wireName()))
                    .append("\", required = ").append(f.required()).append(") ")
                    .append(f.javaType()).append(' ').append(f.name());
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

    /**
     * A value the client reads from a property: an API key, a token, a user name or a password —
     * or an OAuth2 client's id, secret, scopes, token endpoint and how it authenticates.
     *
     * @param defaultValue what it is when the property is not set (null: empty)
     * @param inYaml       whether {@code application.yaml} lists it (empty, for the environment to fill)
     */
    private record CredentialField(String field, String property, String label, String defaultValue, boolean inYaml) {
        CredentialField(String field, String property, String label) {
            this(field, property, label, null, true);
        }
    }

    /**
     * The credentials the clients send, by field name, in the order the schemes are first used:
     * {@code <base>.security.<scheme>.api-key|token|username|password}, where {@code <base>} is the
     * base-URL property without its last segment.
     */
    private static Map<String, CredentialField> credentialFields(List<OperationModel> ops, OpenApiWizardOptions opts) {
        Map<String, CredentialField> out = new LinkedHashMap<>();
        String base = securityPrefix(opts.baseUrlPropertyOrDefault());
        for (OperationModel op : ops) {
            for (SecurityUse use : op.clientSecurity()) {
                String prefix = base + "." + kebab(use.scheme()) + ".";
                switch (use.kind()) {
                    case API_KEY_HEADER, API_KEY_QUERY, API_KEY_COOKIE ->
                            out.putIfAbsent(use.javaName(), new CredentialField(use.javaName(), prefix + "api-key", use.label()));
                    case BEARER ->
                            out.putIfAbsent(use.javaName(), new CredentialField(use.javaName(), prefix + "token", use.label()));
                    case OAUTH2_CLIENT_CREDENTIALS -> {
                        String j = use.javaName();
                        out.putIfAbsent(j + "Token", new CredentialField(j + "Token", prefix + "token",
                                "OAuth2 access token, sent as it is when no client id is set"));
                        out.putIfAbsent(j + "ClientId", new CredentialField(j + "ClientId", prefix + "client-id", "OAuth2 client id"));
                        out.putIfAbsent(j + "ClientSecret", new CredentialField(j + "ClientSecret", prefix + "client-secret", "OAuth2 client secret"));
                        out.putIfAbsent(j + "Scopes", new CredentialField(j + "Scopes", prefix + "scopes",
                                "OAuth2 scopes asked for, separated by spaces", String.join(" ", use.scopes()), false));
                        out.putIfAbsent(j + "TokenUrl", new CredentialField(j + "TokenUrl", prefix + "token-url",
                                "OAuth2 token endpoint", use.tokenUrl(), false));
                        out.putIfAbsent(j + "ClientAuthentication", new CredentialField(j + "ClientAuthentication",
                                prefix + "client-authentication", "How the client proves itself to the token endpoint: basic or post", "basic", false));
                    }
                    case BASIC -> {
                        out.putIfAbsent(use.javaName() + "Username",
                                new CredentialField(use.javaName() + "Username", prefix + "username", "HTTP basic user"));
                        out.putIfAbsent(use.javaName() + "Password",
                                new CredentialField(use.javaName() + "Password", prefix + "password", "HTTP basic password"));
                    }
                    case OTHER -> { }
                }
            }
        }
        return out;
    }

    static String securityPrefix(String baseUrlProperty) {
        int dot = baseUrlProperty.lastIndexOf('.');
        return (dot > 0 ? baseUrlProperty.substring(0, dot) : "openapi.client") + ".security";
    }

    /** {@code petstore_auth} → {@code petstore-auth}, {@code apiKey} → {@code api-key}. */
    static String kebab(String raw) {
        String s = raw.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "scheme" : s;
    }

    private GeneratedOpenApiFile renderClient(String tag, List<OperationModel> ops, String packageName,
                                              OpenApiWizardOptions opts, boolean restClient,
                                              Map<String, CredentialField> credentials) {
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
        boolean usesCredentials = false;
        for (OperationModel op : ops) {
            for (ParamModel p : op.params()) {
                imports.addAll(p.typeImports());
                if (restClient && p.in() == ParamModel.ParamLocation.QUERY) imports.add("java.util.Optional");
                if (restClient && p.in() == ParamModel.ParamLocation.COOKIE) imports.add("org.springframework.http.HttpHeaders");
            }
            if (op.isForm()) {
                imports.add("org.springframework.http.MediaType");
                imports.add("org.springframework.util.LinkedMultiValueMap");
                imports.add("org.springframework.util.MultiValueMap");
                for (FormField f : op.formFields()) imports.addAll(f.clientImports());
            }
            for (SecurityUse use : op.clientSecurity()) {
                usesCredentials = true;
                if (use.kind() == SecurityUse.Kind.API_KEY_COOKIE) imports.add("org.springframework.http.HttpHeaders");
            }
            imports.addAll(op.requestBodyImports());
            imports.addAll(op.responseImports());
            if (isGeneric(op.responseType())) imports.add("org.springframework.core.ParameterizedTypeReference");
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", packageName + "." + clientPkg);
        view.put("imports", imports);
        view.put("className", className);
        view.put("credentials", usesCredentials);
        // Written whole: "${" beside a Mustache tag would read as a triple brace.
        view.put("baseUrlPlaceholder", "${" + opts.baseUrlPropertyOrDefault() + "}");
        List<Map<String, Object>> methods = new ArrayList<>();
        for (OperationModel op : ops) methods.add(clientMethod(op, restClient, credentials));
        view.put("operations", methods);

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/" + className + ".java",
                WizardTemplates.render(restClient ? "openapi/RestClientClient.java" : "openapi/Client.java", view));
    }

    /**
     * One operation as the client templates read it: the URI is the base URL plus the path, with
     * the path variables expanded and the query parameters appended (an optional one only when
     * given); headers and cookies likewise; the body travels with them — a form as its parts —
     * and the credentials its security requires, when they are configured.
     */
    private Map<String, Object> clientMethod(OperationModel op, boolean restClient, Map<String, CredentialField> credentials) {
        Map<String, Object> m = operationView(op);

        List<String> args = new ArrayList<>();
        for (ParamModel p : op.params()) {
            args.add(p.javaType() + " " + p.name());
        }
        for (FormField f : op.formFields()) {
            args.add(f.clientType() + " " + f.name());
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

        // The credentials: a query key with the parameters, the rest as headers. With several
        // requirements, the first whose credentials are all configured is sent (authN says which).
        List<String> authQuery = new ArrayList<>();
        List<String> authHeaders = new ArrayList<>();
        List<String> authChoice = new ArrayList<>();
        String headersVar = restClient ? "httpHeaders" : "headers";
        List<List<SecurityUse>> alternatives = op.clientAlternatives();
        boolean choose = alternatives.size() > 1;
        for (int k = 0; k < alternatives.size(); k++) {
            String chosen = "auth" + k;
            if (choose) {
                List<String> earlier = new ArrayList<>();
                for (int j = 0; j < k; j++) earlier.add("!auth" + j);
                List<String> configured = new ArrayList<>(earlier);
                for (SecurityUse use : alternatives.get(k)) configured.add(configuredExpr(use));
                authChoice.add("boolean " + chosen + " = " + String.join(" && ", configured) + ";");
            }
            for (SecurityUse use : alternatives.get(k)) {
                String value = "credentials." + use.javaName() + "()";
                String param = use.paramName() == null ? null : SchemaRecordEmitter.javaString(use.paramName());
                // One requirement: each credential is sent when configured. Several: the chosen one's.
                String when = choose ? "if (" + chosen + ") " : "if (" + configuredExpr(use) + ") ";
                switch (use.kind()) {
                    case API_KEY_QUERY -> authQuery.add(restClient
                            ? ".queryParamIfPresent(\"" + param + "\", OpenApiCredentials.optional(" + (choose ? chosen + " ? " + value + " : null" : value) + "))"
                            : when + "builder.queryParam(\"" + param + "\", " + value + ");");
                    case API_KEY_HEADER -> authHeaders.add(when + headersVar + ".add(\"" + param + "\", " + value + ");");
                    case API_KEY_COOKIE -> authHeaders.add(when + headersVar + ".add(HttpHeaders.COOKIE, \"" + param + "=\" + " + value + ");");
                    case BEARER, OAUTH2_CLIENT_CREDENTIALS -> authHeaders.add(when + headersVar + ".setBearerAuth(" + value + ");");
                    case BASIC -> {
                        String user = "credentials." + use.javaName() + "Username()";
                        String password = "credentials." + use.javaName() + "Password()";
                        authHeaders.add(when + headersVar + ".setBasicAuth(" + user + ", " + password + ");");
                    }
                    case OTHER -> { }
                }
            }
        }
        m.put("authQuery", authQuery);
        m.put("authChoice", authChoice);

        // A form body: its parts, and its content type.
        List<String> formLines = new ArrayList<>();
        if (op.isForm()) {
            boolean multipart = op.bodyKind() == OperationModel.BodyKind.MULTIPART;
            formLines.add(multipart ? "MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();"
                    : "MultiValueMap<String, String> form = new LinkedMultiValueMap<>();");
            for (FormField f : op.formFields()) {
                String wire = SchemaRecordEmitter.javaString(f.wireName());
                boolean list = f.clientType().startsWith("List<");
                // A file or a JSON part is sent as it is; a plain field as its text, which is what
                // the controller's @RequestParam reads (a date would otherwise travel as JSON).
                boolean asIs = multipart && f.kind() != FormField.Part.FIELD;
                String add = asIs ? "form.add(\"" + wire + "\", %s)" : "form.add(\"" + wire + "\", String.valueOf(%s))";
                formLines.add(list
                        ? "if (" + f.name() + " != null) " + f.name() + ".forEach(v -> " + String.format(add, "v") + ");"
                        : "if (" + f.name() + " != null) " + String.format(add, f.name()) + ";");
            }
            if (!restClient) {
                authHeaders.add(0, "headers.setContentType(MediaType." + (multipart ? "MULTIPART_FORM_DATA" : "APPLICATION_FORM_URLENCODED") + ");");
            }
        }
        m.put("formLines", formLines);
        m.put("contentType", op.isForm() && restClient
                ? List.of(".contentType(MediaType." + (op.bodyKind() == OperationModel.BodyKind.MULTIPART
                        ? "MULTIPART_FORM_DATA" : "APPLICATION_FORM_URLENCODED") + ")")
                : List.of());

        boolean hasHeader = !headerParams.isEmpty() || !cookieParams.isEmpty() || !authHeaders.isEmpty();
        m.put("hasHeaders", hasHeader);
        m.put("headerParams", headerParams);
        m.put("cookieParams", cookieParams);
        m.put("authHeaders", authHeaders);
        String bodyVar = op.isForm() ? "form" : op.requestBodyType() != null ? "body" : null;
        m.put("hasBody", bodyVar != null);
        m.put("bodyVar", bodyVar);

        String entityExpr;
        if (bodyVar != null && hasHeader) {
            entityExpr = "new HttpEntity<>(" + bodyVar + ", headers)";
        } else if (bodyVar != null) {
            entityExpr = "new HttpEntity<>(" + bodyVar + ")";
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

    /** Whether a scheme's credentials are configured, as the client tests it before sending. */
    private static String configuredExpr(SecurityUse use) {
        return switch (use.kind()) {
            case OAUTH2_CLIENT_CREDENTIALS -> "credentials." + use.javaName() + "Configured()";
            case BASIC -> "OpenApiCredentials.present(credentials." + use.javaName() + "Username())";
            default -> "OpenApiCredentials.present(credentials." + use.javaName() + "())";
        };
    }

    private static boolean isGeneric(String type) {
        return type != null && type.indexOf('<') >= 0;
    }

    private GeneratedOpenApiFile renderClientConfig(String packageName, OpenApiWizardOptions opts, boolean restClient, boolean oauth2) {
        String clientPkg = opts.clientPackageOrDefault();
        String fullPkg = packageName + "." + clientPkg;
        String baseUrlProp = opts.baseUrlPropertyOrDefault();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("baseUrlProperty", baseUrlProp);
        view.put("baseUrlPlaceholder", "${" + baseUrlProp + "}");
        view.put("oauth2", oauth2);

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/OpenApiClientConfig.java",
                WizardTemplates.render(restClient ? "openapi/RestClientConfig.java" : "openapi/ClientConfig.java", view));
    }

    /** The credentials the clients send, each read from its property (empty: not sent). */
    private GeneratedOpenApiFile renderCredentials(String packageName, OpenApiWizardOptions opts,
                                                   Map<String, CredentialField> credentials, List<OperationModel> operations) {
        String clientPkg = opts.clientPackageOrDefault();
        // The OAuth2 client-credentials schemes: a token the client fetches.
        Map<String, SecurityUse> oauth = new LinkedHashMap<>();
        for (OperationModel op : operations) {
            for (SecurityUse use : op.clientSecurity()) {
                if (use.kind() == SecurityUse.Kind.OAUTH2_CLIENT_CREDENTIALS) oauth.putIfAbsent(use.scheme(), use);
            }
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        List<CredentialField> list = new ArrayList<>(credentials.values());
        for (int i = 0; i < list.size(); i++) {
            CredentialField c = list.get(i);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("field", c.field());
            f.put("property", c.property());
            f.put("placeholder", "${" + c.property() + ":" + (c.defaultValue() == null ? "" : SchemaRecordEmitter.javaString(c.defaultValue())) + "}");
            f.put("label", SchemaRecordEmitter.escapeJavadoc(c.label()));
            f.put("separator", i == list.size() - 1 && oauth.isEmpty() ? ") {" : ",");
            fields.add(f);
        }
        List<Map<String, Object>> oauthViews = new ArrayList<>();
        String prefix = securityPrefix(opts.baseUrlPropertyOrDefault());
        for (SecurityUse use : oauth.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("java", use.javaName());
            o.put("scheme", SchemaRecordEmitter.escapeJavadoc(use.scheme()));
            o.put("schemeJava", SchemaRecordEmitter.javaString(use.scheme()));
            o.put("tokenProperty", prefix + "." + kebab(use.scheme()) + ".token");
            oauthViews.add(o);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", packageName + "." + clientPkg);
        view.put("fields", fields);
        view.put("oauth", oauthViews);
        view.put("hasTokens", !oauthViews.isEmpty());
        view.put("prefix", securityPrefix(opts.baseUrlPropertyOrDefault()));
        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/OpenApiCredentials.java",
                WizardTemplates.render("openapi/Credentials.java", view));
    }

    /** The OAuth2 client-credentials token client, beside the credentials. */
    private GeneratedOpenApiFile renderOAuth2Tokens(String packageName, OpenApiWizardOptions opts) {
        String clientPkg = opts.clientPackageOrDefault();
        return new GeneratedOpenApiFile("src/main/java/{{packagePath}}/" + clientPkg + "/OpenApiOAuth2Tokens.java",
                WizardTemplates.render("openapi/OAuth2Tokens.java", Map.of("package", packageName + "." + clientPkg)));
    }

    private GeneratedOpenApiFile renderApplicationYmlStub(OpenApiWizardOptions opts, Map<String, CredentialField> credentials) {
        // The base URL, and each credential's property, empty: an environment variable or Vault
        // gives its value (OPENAPI_CLIENT_SECURITY_<SCHEME>_TOKEN and the like).
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(opts.baseUrlPropertyOrDefault(), "http://localhost:8080");
        for (CredentialField c : credentials.values()) {
            if (c.inYaml()) properties.put(c.property(), "\"\"");
        }
        return new GeneratedOpenApiFile("src/main/resources/application.yaml", yaml(properties));
    }

    /** Dotted properties as nested YAML, in the order first named. */
    @SuppressWarnings("unchecked")
    private static String yaml(Map<String, String> properties) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (var e : properties.entrySet()) {
            String[] parts = e.getKey().split("\\.");
            Map<String, Object> node = tree;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = node.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    node.put(parts[i], next);
                }
                node = (Map<String, Object>) next;
            }
            node.put(parts[parts.length - 1], e.getValue());
        }
        StringBuilder sb = new StringBuilder();
        writeYaml(tree, 0, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeYaml(Map<String, Object> node, int depth, StringBuilder sb) {
        for (var e : node.entrySet()) {
            sb.append("  ".repeat(depth)).append(e.getKey());
            if (e.getValue() instanceof Map) {
                sb.append(":\n");
                writeYaml((Map<String, Object>) e.getValue(), depth + 1, sb);
            } else {
                sb.append(": ").append(e.getValue()).append('\n');
            }
        }
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
