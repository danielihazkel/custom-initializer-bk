package com.menora.initializr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /starter-fullstack.zip}: each entity's access levels, with and without ldap-auth. */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackAccessGenerationTests extends FullstackEndpointTestSupport {

    @Test
    void fullstackEndpoint_enforcesEachEntitysAccessWithLdapAuth() throws Exception {
        // With an ldap-auth dependency on the build — a default of both backend sets — every
        // endpoint carries a live @RequiresPermission at its entity's level: read for users and
        // write for admins unless the entity says otherwise. It used to be the `secured` option's
        // commented-out hints, so every generated endpoint was open.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("name", "Note");
        note.put("fields", List.of(pkField()));
        note.put("access", Map.of("read", "signed_in", "write", "user"));
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("name", "AuditEntry");
        audit.put("fields", List.of(pkField()));
        audit.put("access", Map.of("read", "ADMIN"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField())), note, audit));

        Map<String, String> entries = generateZip(body);

        String users = contentEndingWith(entries, "/controller/UserController.java");
        assertThat(users)
                .contains("\nimport com.menora.demo.security.Constants;\nimport com.menora.demo.security.RequiresPermission;\n")
                .contains("    @RequiresPermission(Constants.USER)\n    @GetMapping\n")
                .contains("    @RequiresPermission(Constants.USER)\n    @GetMapping(\"/{id}\")")
                .contains("    @RequiresPermission(Constants.ADMIN)\n    @PostMapping\n")
                .contains("    @RequiresPermission(Constants.ADMIN)\n    @PutMapping(\"/{id}\")")
                .contains("    @RequiresPermission(Constants.ADMIN)\n    @DeleteMapping(\"/{id}\")")
                .doesNotContain("// @RequiresPermission").doesNotContain("Authorization scaffold");
        // No mapping is left without a check.
        assertThat(users.split("@RequiresPermission\\(", -1).length - 1)
                .isEqualTo(users.split("    @(Get|Post|Put|Delete|Patch)Mapping", -1).length - 1);

        // An entity's own levels: any signed-in user reads notes, users write them.
        assertThat(contentEndingWith(entries, "/controller/NoteController.java"))
                .contains("    @RequiresPermission(Constants.SIGNED_IN)\n    @GetMapping\n")
                .contains("    @RequiresPermission(Constants.USER)\n    @PostMapping\n")
                .doesNotContain("Constants.ADMIN");
        // Only read named: write keeps its default, which is no weaker.
        assertThat(contentEndingWith(entries, "/controller/AuditEntryController.java"))
                .contains("    @RequiresPermission(Constants.ADMIN)\n    @GetMapping\n")
                .doesNotContain("Constants.USER");

        // The classes behind the check: an admin is a user too, roles are remembered for a
        // minute, and the local profile gives the dev user roles without the directory.
        assertThat(contentEndingWith(entries, "/security/PermissionService.java"))
                .contains("public Set<String> rolesOf(String username)")
                .contains("if (all.contains(Constants.ADMIN)) all.add(Constants.USER);")
                .contains("app.security.dev-roles");
        assertThat(contentEndingWith(entries, "/security/PermissionAspect.java"))
                .contains("permissionService.holds(userName, requiredPermissionValue)");
        assertThat(contentEndingWith(entries, "/security/Constants.java")).contains("SIGNED_IN");
        assertThat(contentEndingWith(entries, "backend/src/main/resources/application-local.yaml"))
                .contains("dev-roles:\n      dev-user: ADMIN");
        assertThat(contentEndingWith(entries, "/web/MeController.java")).contains("permissionService.rolesOf(user)");
        assertThat(entries.get("demo/README.md"))
                .contains("mvn spring-boot:run -Dspring-boot.run.profiles=local")
                .contains("## Who may do what");
        // The model the project keeps says what was asked, as asked.
        assertThat(entries.get("demo/menora-model.json")).contains("\"access\"");

        // The old option still validates, and changes nothing.
        body.put("opts", Map.of("scaffold", List.of("secured")));
        assertThat(contentEndingWith(generateZip(body), "/controller/UserController.java")).isEqualTo(users);
    }

    @Test
    void fullstackEndpoint_rejectsInvalidAccessLevels() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", "Note");
        entity.put("fields", List.of(pkField()));
        entity.put("access", Map.of("read", "EVERYONE"));
        body.put("entities", List.of(entity));
        ResponseEntity<String> unknown = postFullstack(b -> b.putAll(body));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).contains("access: read 'EVERYONE' is not one of SIGNED_IN, USER, ADMIN")
                .contains("entities[0].access.read");

        // Writing open to more people than reading: whoever may change a row has to be able to see it.
        entity.put("access", Map.of("read", "ADMIN", "write", "USER"));
        ResponseEntity<String> inverted = postFullstack(b -> b.putAll(body));
        assertThat(inverted.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inverted.getBody()).contains("write (USER) cannot be open to more people than read (ADMIN)");

        // Levels need the classes that check them.
        entity.put("access", Map.of("read", "SIGNED_IN"));
        body.put("dependencies", List.of("data-jpa", "web"));
        ResponseEntity<String> noLdap = postFullstack(b -> b.putAll(body));
        assertThat(noLdap.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noLdap.getBody()).contains("sets access levels, which need the ldap-auth or ldap-auth-rest dependency")
                .contains("entities[0].access");
    }

    @Test
    void fullstackEndpoint_checksNothingWithoutLdapAuth() throws Exception {
        // No ldap-auth dependency: there is nobody to ask, so no @RequiresPermission and no
        // security.* import (the classes would not exist). The old `secured` option changes nothing.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("opts", Map.of("scaffold", List.of("secured")));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/controller/UserController.java"))
                .doesNotContain("@RequiresPermission")
                .doesNotContain(".security.RequiresPermission");
    }
}
