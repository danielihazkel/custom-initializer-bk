package com.menora.initializr.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
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
import java.util.Collections;
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

    private static final String TODO_UNSUPPORTED = "    // TODO: unsupported schema composition (allOf/oneOf/anyOf)";

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
        if (spec == null || spec.isBlank()) return List.of();
        OpenAPI api = parseOrThrow(spec);
        OpenApiWizardOptions opts = options != null ? options
                : new OpenApiWizardOptions(null, null, null, null, null);

        Map<String, SchemaModel> schemas = buildSchemas(api);
        List<OperationModel> operations = buildOperations(api, schemas.keySet());

        List<GeneratedOpenApiFile> out = new ArrayList<>();
        for (SchemaModel s : schemas.values()) {
            out.add(renderRecord(s, packageName, opts));
        }
        Map<String, List<OperationModel>> byTag = groupByTag(operations);
        if (opts.emitControllers()) {
            for (var group : byTag.entrySet()) {
                out.add(renderController(group.getKey(), group.getValue(), packageName, opts));
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

    /** For the wizard's live preview — returns {@code "GET /pets"} style strings. */
    public List<String> detectPaths(String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        OpenAPI api;
        try {
            api = parseOrThrow(spec);
        } catch (OpenApiParseException e) {
            return List.of();
        }
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

    // ── Schema → record ───────────────────────────────────────────────────────

    private Map<String, SchemaModel> buildSchemas(OpenAPI api) {
        Map<String, SchemaModel> out = new LinkedHashMap<>();
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return out;
        }
        for (var e : api.getComponents().getSchemas().entrySet()) {
            String rawName = e.getKey();
            Schema<?> schema = e.getValue();
            SchemaModel model = toSchemaModel(rawName, schema);
            out.put(model.name(), model);
        }
        return out;
    }

    @SuppressWarnings("rawtypes")
    private SchemaModel toSchemaModel(String rawName, Schema schema) {
        String className = toPascalCase(rawName);
        List<FieldModel> fields = new ArrayList<>();
        if (schema instanceof ComposedSchema) {
            // Fallback: emit an empty record with a TODO marker (recorded via description).
            return new SchemaModel(className, List.of(), "TODO: unsupported schema composition (allOf/oneOf/anyOf)");
        }
        Set<String> required = schema.getRequired() == null ? Set.of() : new LinkedHashSet<>(schema.getRequired());
        Map<String, Schema> props = schema.getProperties() == null ? Map.of() : schema.getProperties();
        for (var e : props.entrySet()) {
            String propName = e.getKey();
            Schema<?> propSchema = e.getValue();
            JavaTypeRef tr = mapType(propSchema);
            fields.add(new FieldModel(
                    toCamelCase(propName),
                    tr.typeName(),
                    required.contains(propName),
                    propSchema.getDescription(),
                    tr.imports()));
        }
        return new SchemaModel(className, fields, schema.getDescription());
    }

    private GeneratedOpenApiFile renderRecord(SchemaModel s, String packageName, OpenApiWizardOptions opts) {
        String subPkg = opts.dtoPackageOrDefault();
        String fullPkg = packageName + "." + subPkg;

        Set<String> imports = new TreeSet<>();
        for (FieldModel f : s.fields()) imports.addAll(f.imports());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        if (!imports.isEmpty()) sb.append('\n');

        if (s.description() != null && !s.description().isBlank()) {
            sb.append("/** ").append(escapeJavadoc(s.description())).append(" */\n");
        }
        sb.append("public record ").append(s.name()).append("(");
        if (s.fields().isEmpty()) {
            sb.append(") {\n");
            sb.append("    // Empty record — original schema had no properties or was composed.\n");
            sb.append("}\n");
        } else {
            sb.append('\n');
            for (int i = 0; i < s.fields().size(); i++) {
                FieldModel f = s.fields().get(i);
                sb.append("        ").append(f.javaType()).append(' ').append(f.name());
                if (i < s.fields().size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append(") {\n}\n");
        }

        String path = "src/main/java/{{packagePath}}/" + subPkg + "/" + s.name() + ".java";
        return new GeneratedOpenApiFile(path, sb.toString());
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
                JavaTypeRef tr = p.getSchema() == null ? new JavaTypeRef("String", Set.of())
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
                JavaTypeRef tr = mapType(mt.getSchema());
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
                    JavaTypeRef tr = mapType(mt.getSchema());
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
                                                  String packageName, OpenApiWizardOptions opts) {
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
            imports.add("jakarta.validation.Valid");
        }
        if (hasPath) imports.add("org.springframework.web.bind.annotation.PathVariable");
        if (hasQuery) imports.add("org.springframework.web.bind.annotation.RequestParam");
        if (hasHeader) imports.add("org.springframework.web.bind.annotation.RequestHeader");

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        sb.append('\n');

        sb.append("@RestController\n");
        sb.append("@Validated\n");
        sb.append("@RequestMapping\n");
        sb.append("public class ").append(className).append(" {\n\n");
        for (OperationModel op : ops) appendMethod(sb, op);
        sb.append("}\n");

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + apiPkg + "/" + className + ".java",
                sb.toString());
    }

    private void appendMethod(StringBuilder sb, OperationModel op) {
        if (op.summary() != null && !op.summary().isBlank()) {
            sb.append("    /** ").append(escapeJavadoc(op.summary())).append(" */\n");
        }
        sb.append("    @").append(mappingAnnotation(op.httpMethod()))
                .append("(\"").append(op.path()).append("\")\n");

        String ret = op.responseType() == null ? "void" : op.responseType();
        sb.append("    public ").append(ret).append(' ').append(op.operationId()).append('(');

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
            args.add("@Valid @RequestBody " + op.requestBodyType() + " body");
        }
        sb.append(String.join(", ", args));
        sb.append(") {\n");
        sb.append("        throw new UnsupportedOperationException(\"TODO: implement ")
                .append(op.operationId()).append("\");\n");
        sb.append("    }\n\n");
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

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        sb.append('\n');

        sb.append("@Component\n");
        sb.append("public class ").append(className).append(" {\n\n");
        sb.append("    private final RestTemplate restTemplate;\n");
        sb.append("    private final String baseUrl;\n\n");
        sb.append("    public ").append(className)
                .append("(RestTemplate restTemplate, @Value(\"${").append(baseUrlProp).append("}\") String baseUrl) {\n");
        sb.append("        this.restTemplate = restTemplate;\n");
        sb.append("        this.baseUrl = baseUrl;\n");
        sb.append("    }\n\n");

        for (OperationModel op : ops) appendClientMethod(sb, op);
        sb.append("}\n");

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/" + className + ".java",
                sb.toString());
    }

    private void appendClientMethod(StringBuilder sb, OperationModel op) {
        if (op.summary() != null && !op.summary().isBlank()) {
            sb.append("    /** ").append(escapeJavadoc(op.summary())).append(" */\n");
        }
        String ret = op.responseType() == null ? "void" : op.responseType();
        sb.append("    public ").append(ret).append(' ').append(op.operationId()).append('(');

        List<String> args = new ArrayList<>();
        for (ParamModel p : op.params()) {
            args.add(p.javaType() + " " + p.name());
        }
        if (op.requestBodyType() != null) {
            args.add(op.requestBodyType() + " body");
        }
        sb.append(String.join(", ", args)).append(") {\n");

        // Build URI from baseUrl + path, substituting path variables and appending query params.
        sb.append("        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)\n");
        sb.append("                .path(\"").append(op.path()).append("\");\n");

        for (ParamModel p : op.params()) {
            if (p.in() == ParamModel.ParamLocation.QUERY || p.in() == ParamModel.ParamLocation.COOKIE) {
                if (p.required()) {
                    sb.append("        builder.queryParam(\"").append(p.name()).append("\", ").append(p.name()).append(");\n");
                } else {
                    sb.append("        if (").append(p.name()).append(" != null) builder.queryParam(\"")
                            .append(p.name()).append("\", ").append(p.name()).append(");\n");
                }
            }
        }

        // Path-variable substitution via buildAndExpand
        List<ParamModel> pathParams = new ArrayList<>();
        for (ParamModel p : op.params()) if (p.in() == ParamModel.ParamLocation.PATH) pathParams.add(p);
        if (!pathParams.isEmpty()) {
            sb.append("        String uri = builder.buildAndExpand(");
            List<String> names = new ArrayList<>();
            for (ParamModel p : pathParams) names.add(p.name());
            sb.append(String.join(", ", names));
            sb.append(").toUriString();\n");
        } else {
            sb.append("        String uri = builder.toUriString();\n");
        }

        // Headers
        boolean hasHeader = false;
        for (ParamModel p : op.params()) if (p.in() == ParamModel.ParamLocation.HEADER) { hasHeader = true; break; }
        if (hasHeader) {
            sb.append("        HttpHeaders headers = new HttpHeaders();\n");
            for (ParamModel p : op.params()) {
                if (p.in() != ParamModel.ParamLocation.HEADER) continue;
                if (p.required()) {
                    sb.append("        headers.add(\"").append(p.name()).append("\", String.valueOf(").append(p.name()).append("));\n");
                } else {
                    sb.append("        if (").append(p.name()).append(" != null) headers.add(\"")
                            .append(p.name()).append("\", String.valueOf(").append(p.name()).append("));\n");
                }
            }
        }

        // Entity
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

        String httpMethodEnum = op.httpMethod().toUpperCase(Locale.ROOT);
        boolean isList = op.responseType() != null && op.responseType().startsWith("List<");
        String respTypeArg;
        if (op.responseType() == null) {
            respTypeArg = "Void.class";
        } else if (isList) {
            respTypeArg = "new ParameterizedTypeReference<" + op.responseType() + ">() {}";
        } else {
            respTypeArg = op.responseType() + ".class";
        }

        sb.append("        ResponseEntity<").append(op.responseType() == null ? "Void" : op.responseType()).append("> response =\n");
        sb.append("                restTemplate.exchange(uri, HttpMethod.").append(httpMethodEnum)
                .append(", ").append(entityExpr).append(", ").append(respTypeArg).append(");\n");

        if (op.responseType() != null) {
            sb.append("        return response.getBody();\n");
        }
        sb.append("    }\n\n");
    }

    private GeneratedOpenApiFile renderClientConfig(String packageName, OpenApiWizardOptions opts) {
        String clientPkg = opts.clientPackageOrDefault();
        String fullPkg = packageName + "." + clientPkg;
        String baseUrlProp = opts.baseUrlPropertyOrDefault();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        sb.append("import org.springframework.boot.web.client.RestTemplateBuilder;\n");
        sb.append("import org.springframework.context.annotation.Bean;\n");
        sb.append("import org.springframework.context.annotation.Configuration;\n");
        sb.append("import org.springframework.web.client.RestTemplate;\n\n");
        sb.append("/**\n");
        sb.append(" * Provides the RestTemplate bean used by the generated OpenAPI clients.\n");
        sb.append(" * The base URL is read from the {@code ").append(baseUrlProp).append("} property\n");
        sb.append(" * in application.yaml.\n");
        sb.append(" */\n");
        sb.append("@Configuration\n");
        sb.append("public class OpenApiClientConfig {\n\n");
        sb.append("    @Bean\n");
        sb.append("    public RestTemplate openApiRestTemplate(RestTemplateBuilder builder) {\n");
        sb.append("        return builder.build();\n");
        sb.append("    }\n");
        sb.append("}\n");

        return new GeneratedOpenApiFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/OpenApiClientConfig.java",
                sb.toString());
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

    // ── Type mapping ──────────────────────────────────────────────────────────

    record JavaTypeRef(String typeName, Set<String> imports) {}

    @SuppressWarnings("rawtypes")
    private JavaTypeRef mapType(Schema schema) {
        if (schema == null) return new JavaTypeRef("Object", Set.of());
        String ref = schema.get$ref();
        if (ref != null && !ref.isBlank()) {
            String name = refName(ref);
            return new JavaTypeRef(toPascalCase(name), Set.of());
        }
        if (schema instanceof ArraySchema arr) {
            Schema<?> items = arr.getItems();
            JavaTypeRef inner = mapType(items);
            Set<String> imports = new LinkedHashSet<>(inner.imports());
            imports.add("java.util.List");
            return new JavaTypeRef("List<" + inner.typeName() + ">", imports);
        }
        if (schema instanceof ComposedSchema) {
            return new JavaTypeRef("Object", Set.of());
        }
        if (schema instanceof BooleanSchema) {
            return new JavaTypeRef("Boolean", Set.of());
        }
        if (schema instanceof IntegerSchema || "integer".equals(schema.getType())) {
            String fmt = schema.getFormat();
            if ("int64".equals(fmt)) return new JavaTypeRef("Long", Set.of());
            return new JavaTypeRef("Integer", Set.of());
        }
        if (schema instanceof NumberSchema || "number".equals(schema.getType())) {
            String fmt = schema.getFormat();
            if ("float".equals(fmt)) return new JavaTypeRef("Float", Set.of());
            return new JavaTypeRef("Double", Set.of());
        }
        if (schema instanceof UUIDSchema) {
            return new JavaTypeRef("UUID", Set.of("java.util.UUID"));
        }
        if (schema instanceof DateSchema) {
            return new JavaTypeRef("LocalDate", Set.of("java.time.LocalDate"));
        }
        if (schema instanceof DateTimeSchema) {
            return new JavaTypeRef("LocalDateTime", Set.of("java.time.LocalDateTime"));
        }
        if (schema instanceof StringSchema || "string".equals(schema.getType())) {
            String fmt = schema.getFormat();
            if (fmt == null) return new JavaTypeRef("String", Set.of());
            return switch (fmt) {
                case "date" -> new JavaTypeRef("LocalDate", Set.of("java.time.LocalDate"));
                case "date-time" -> new JavaTypeRef("LocalDateTime", Set.of("java.time.LocalDateTime"));
                case "uuid" -> new JavaTypeRef("UUID", Set.of("java.util.UUID"));
                case "binary", "byte" -> new JavaTypeRef("byte[]", Set.of());
                default -> new JavaTypeRef("String", Set.of());
            };
        }
        if (schema instanceof ObjectSchema) {
            return new JavaTypeRef("Object", Set.of());
        }
        return new JavaTypeRef("Object", Set.of());
    }

    private static String refName(String ref) {
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? ref : ref.substring(slash + 1);
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
        if (raw == null || raw.isEmpty()) return "Unnamed";
        String[] parts = raw.split("[_\\-\\s.]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        String out = sb.length() == 0 ? "Unnamed" : sb.toString();
        return out.replaceAll("[^A-Za-z0-9]", "");
    }

    private static String toCamelCase(String raw) {
        String pascal = toPascalCase(raw);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    private static String escapeJavadoc(String s) {
        return s.replace("*/", "*\\/").replace("\n", " ").replace("\r", " ");
    }

    @SuppressWarnings("unused")
    private static List<String> safe(List<String> l) {
        return l == null ? Collections.emptyList() : l;
    }
}
