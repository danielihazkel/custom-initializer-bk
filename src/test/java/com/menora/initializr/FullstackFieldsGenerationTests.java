package com.menora.initializr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /starter-fullstack.zip}: field types, constraints, labels, defaults and the names a model may not use. */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackFieldsGenerationTests extends FullstackEndpointTestSupport {

    @Test
    void fullstackEndpoint_rendersCompilableEnumAndDateFormControls() throws Exception {
        // Regression: an ENUM field's <option> map must be wrapped in JSX braces (otherwise
        // `tsc -b` fails with "Cannot find name 'v'"), and a LOCAL_DATE field must use a
        // `date` input — not `datetime-local`, which sends `...T00:00` and is rejected by
        // Jackson for java.time.LocalDate. Neither is exercised by the User/Order test above.
        Map<String, Object> statusField = new LinkedHashMap<>();
        statusField.put("name", "status");
        statusField.put("type", "Enum");
        statusField.put("enumValues", List.of("ACTIVE", "INACTIVE"));
        Map<String, Object> birthField = Map.of("name", "birthDate", "type", "LocalDate");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "people");
        body.put("packageName", "com.menora.people");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Person", "fields", List.of(pkField(), statusField, birthField))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String personForm = entries.get("people/frontend/src/features/person-form/ui/PersonForm.tsx");
        assertThat(personForm).isNotNull();
        // 1.1 — enum <option> map wrapped in JSX braces
        assertThat(personForm)
                .contains("{ PersonStatusTypeOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>) }");
        // 1.2 — LocalDate uses a plain date input, never datetime-local
        assertThat(personForm).contains("type=\"date\"");
        assertThat(personForm).doesNotContain("type=\"datetime-local\"");

        // Enum union type is emitted for the field
        String personType = entries.get("people/frontend/src/entities/person/model/types.ts");
        assertThat(personType).contains("export type PersonStatusType = 'ACTIVE' | 'INACTIVE'")
                // No enumLabels → humanized constants, still emitted so every enum surface reads the same map.
                .contains("export const PersonStatusTypeLabels: Record<PersonStatusType, string> = { ACTIVE: 'Active', INACTIVE: 'Inactive' }")
                .contains("export const PersonStatusTypeOptions: { value: PersonStatusType; label: string }[] = PersonStatusTypeValues.map(v => ({ value: v, label: PersonStatusTypeLabels[v] }))");

        // Backend: the DTO lives in its own .dto sub-package and imports the entity plus its
        // nested enum, so the bare PersonStatusType reference still resolves across packages.
        assertThat(contentEndingWith(entries, "/dto/PersonDto.java"))
                .contains("package com.menora.people.dto;")
                .contains("import com.menora.people.entity.Person;")
                .contains("import com.menora.people.entity.Person.PersonStatusType;");
    }

    @Test
    void fullstackEndpoint_wiresBeanValidationAndScopesSearch() throws Exception {
        // Account has a required, length-bounded String → DTO gets @NotNull/@Size and a
        // searchable list; Ledger has no String fields → its list hides the search box.
        Map<String, Object> email = Map.of("name", "email", "type", "String", "required", true, "length", 200);
        Map<String, Object> balance = Map.of("name", "balance", "type", "BigDecimal");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "bank");
        body.put("packageName", "com.menora.bank");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Account", "fields", List.of(pkField(), email)),
                Map.of("name", "Ledger", "fields", List.of(pkField(), balance))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        // DTO carries the constraints, with only the imports it uses.
        String accountDto = contentEndingWith(entries, "/dto/AccountDto.java");
        assertThat(accountDto)
                .contains("import jakarta.validation.constraints.NotNull;")
                .contains("import jakarta.validation.constraints.Size;")
                .contains("@NotNull")
                .contains("@Size(max = 200)");
        // id is a generated PK → it must NOT be @NotNull (it is null until persisted).
        assertThat(accountDto).contains("Long id");

        // Controller validates the body; the 400 / 409 mapping lives in the shared advice, not here.
        String accountController = contentEndingWith(entries, "/controller/AccountController.java");
        assertThat(accountController)
                .contains("import jakarta.validation.Valid;")
                .contains("@Valid")
                .doesNotContain("MethodArgumentNotValidException")
                .doesNotContain("DataIntegrityViolationException");
        assertThat(contentEndingWith(entries, "/web/ApiExceptionHandler.java"))
                .contains("@ExceptionHandler(MethodArgumentNotValidException.class)")
                .contains("@ExceptionHandler(DataIntegrityViolationException.class)")
                .contains("HttpStatus.CONFLICT");

        // Required fields are marked in the generated form (email is required → asterisk).
        assertThat(entries.get("bank/frontend/src/features/account-form/ui/AccountForm.tsx"))
                .contains("label=\"Email\" required error={errors?.email}");

        // Frontend search box is gated on the entity having a string field.
        assertThat(ListPages.of(entries, "bank/frontend/src/pages/account/ui/AccountPage.tsx")).contains("searchable={true}");
        assertThat(ListPages.of(entries, "bank/frontend/src/pages/ledger/ui/LedgerPage.tsx")).contains("searchable={false}");
        assertThat(entries.get("bank/frontend/src/shared/ui/Table.tsx"))
                .contains("searchable")
                .contains("{searchable ? (");
    }

    @Test
    void fullstackEndpoint_perFieldSearchAndFilterOptOut() throws Exception {
        // Widget opts its only String field out of search (searchable=false) and its only numeric
        // field out of the filter bar (filterable=false) → no search box, no Filters carrier.
        // Gadget keeps the defaults as a control, so it still gets both.
        Map<String, Object> widgetTitle = Map.of("name", "title", "type", "String", "required", true, "searchable", false);
        Map<String, Object> widgetPriority = Map.of("name", "priority", "type", "Integer", "filterable", false);
        Map<String, Object> gadgetTitle = Map.of("name", "title", "type", "String", "required", true);
        Map<String, Object> gadgetPriority = Map.of("name", "priority", "type", "Integer");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "tools");
        body.put("packageName", "com.menora.tools");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Widget", "fields", List.of(pkField(), widgetTitle, widgetPriority)),
                Map.of("name", "Gadget", "fields", List.of(pkField(), gadgetTitle, gadgetPriority))));

        Map<String, String> entries = generateZip(body);

        // Widget: both opted out → plain findAll (no Filters arg / record), search box hidden.
        String widgetService = contentEndingWith(entries, "/service/WidgetService.java");
        assertThat(widgetService)
                .contains("findAll(String q, Pageable pageable)")
                .doesNotContain("record Filters(")
                .doesNotContain("priorityMin");
        String widgetPage = ListPages.of(entries, "tools/frontend/src/pages/widget/ui/WidgetPage.tsx");
        assertThat(widgetPage).contains("searchable={false}").doesNotContain("filterDescriptors");

        // Gadget: defaults preserved → Filters carrier with the numeric bounds, search box shown.
        String gadgetService = contentEndingWith(entries, "/service/GadgetService.java");
        assertThat(gadgetService)
                .contains("findAll(String q, Filters filters, Pageable pageable)")
                .contains("record Filters(")
                .contains("priorityMin");
        String gadgetPage = ListPages.of(entries, "tools/frontend/src/pages/gadget/ui/GadgetPage.tsx");
        assertThat(gadgetPage).contains("searchable={true}").contains("filterDescriptors");
    }

    @Test
    void fullstackEndpoint_perFieldLabelAndReadOnly() throws Exception {
        // title carries a custom (Hebrew) display label; status is read-only (locked after create).
        Map<String, Object> title = Map.of("name", "title", "type", "String", "required", true, "label", "כותרת");
        Map<String, Object> status = Map.of("name", "status", "type", "String", "readOnly", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "tasks");
        body.put("packageName", "com.menora.tasks");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Task", "fields", List.of(pkField(), title, status))));

        Map<String, String> entries = generateZip(body);

        // Frontend column uses the custom label, but the data/sort key stays the field name.
        String taskPage = ListPages.of(entries, "tasks/frontend/src/pages/task/ui/TaskPage.tsx");
        assertThat(taskPage).contains("label: 'כותרת', sortKey: 'title'");

        // Frontend form: the read-only field is locked after create (disabled on edit); the
        // custom label reaches the Field. A generated PK uses a bare `disabled`, so `disabled={!isNew}`
        // here comes from the read-only status field (there is no non-generated PK in this entity).
        String taskForm = contentEndingWith(entries, "/task-form/ui/TaskForm.tsx");
        assertThat(taskForm).contains("label=\"כותרת\"");
        assertThat(taskForm).contains("disabled={!isNew}");

        // Backend Service.update never overwrites the read-only field, but still copies the editable one.
        String taskService = contentEndingWith(entries, "/service/TaskService.java");
        assertThat(taskService).contains("existing.setTitle(updated.getTitle());");
        assertThat(taskService).doesNotContain("existing.setStatus(updated.getStatus());");
        // But status is still a real column/field (bound on create) — the entity carries the setter.
        String taskEntity = contentEndingWith(entries, "/entity/Task.java");
        assertThat(taskEntity).contains("private String status;");
    }

    @Test
    void fullstackEndpoint_rendersFieldConstraints() throws Exception {
        // email (String, email=true, length-bounded), age (Integer, min/max), and code
        // (String, regex pattern) exercise @Email/@Min/@Max/@Pattern in the DTO and the
        // matching HTML input attributes in the form.
        Map<String, Object> email = Map.of("name", "email", "type", "String", "required", true, "length", 200, "email", true);
        Map<String, Object> age = Map.of("name", "age", "type", "Integer", "min", 0, "max", 120);
        Map<String, Object> code = Map.of("name", "code", "type", "String", "pattern", "[A-Z]{3}");
        // A decimal bound on a BigDecimal renders through @DecimalMin/@DecimalMax (string-valued)
        // and reaches the form/validator verbatim — integral bounds stay plain integers.
        Map<String, Object> salary = Map.of("name", "salary", "type", "BigDecimal", "min", 0.5, "max", 99999.99);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "people");
        body.put("packageName", "com.menora.people");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Person", "fields", List.of(pkField(), email, age, code, salary))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String dto = contentEndingWith(entries, "/dto/PersonDto.java");
        assertThat(dto)
                .contains("import jakarta.validation.constraints.Email;")
                .contains("import jakarta.validation.constraints.Pattern;")
                .contains("import jakarta.validation.constraints.Min;")
                .contains("import jakarta.validation.constraints.Max;")
                .contains("@Email")
                .contains("@Pattern(regexp = \"[A-Z]{3}\")")
                .contains("@Min(0)")
                .contains("@Max(120)")
                .contains("@DecimalMin(value = \"0.5\")")
                .contains("@DecimalMax(value = \"99999.99\")");

        String form = entries.get("people/frontend/src/features/person-form/ui/PersonForm.tsx");
        assertThat(form)
                .contains("type=\"email\"")
                .contains("min=\"0\"")
                .contains("max=\"120\"")
                .contains("min=\"0.5\"")
                .contains("step=\"any\"")
                .contains("pattern={\"[A-Z]{3}\"}");
        String validate = entries.get("people/frontend/src/features/person-form/model/validate.ts");
        assertThat(validate).contains("0.5").contains("99999.99");
    }

    @Test
    void fullstackEndpoint_rejectsReservedKeywordEnumConstant() {
        Map<String, Object> enumField = new LinkedHashMap<>();
        enumField.put("name", "status");
        enumField.put("type", "Enum");
        enumField.put("enumValues", List.of("ACTIVE", "class"));

        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "demo");
            b.put("bootVersion", "3.2.1");
            b.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField(), enumField))));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("reserved keyword");
    }

    @Test
    void fullstackEndpoint_rendersCompositePrimaryKey() throws Exception {
        // OrderLine has a two-field composite PK (orderId + lineNo). It is rendered with @IdClass +
        // a separate top-level key class (OrderLineId.java, matching the SQL wizard); the repository
        // id type, the controller path, and the frontend key-array addressing all follow.
        Map<String, Object> orderId = Map.of("name", "orderId", "type", "Long", "primaryKey", true);
        Map<String, Object> lineNo = Map.of("name", "lineNo", "type", "Integer", "primaryKey", true);
        Map<String, Object> qty = Map.of("name", "qty", "type", "Integer");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "OrderLine", "fields", List.of(orderId, lineNo, qty))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/entity/OrderLine.java"))
                .contains("@IdClass(OrderLineId.class)")
                .doesNotContain("public static class OrderLineId");
        // The composite key is its own top-level class file, not a nested class.
        assertThat(contentEndingWith(entries, "/entity/OrderLineId.java"))
                .contains("public class OrderLineId implements java.io.Serializable")
                .contains("private Long orderId;")
                .contains("private Integer lineNo;")
                .contains("java.util.Objects.hash(orderId, lineNo)");
        assertThat(contentEndingWith(entries, "/repository/OrderLineRepository.java"))
                .contains("import com.menora.shop.entity.OrderLineId;")
                .contains("JpaRepository<OrderLine, OrderLineId>");
        assertThat(contentEndingWith(entries, "/controller/OrderLineController.java"))
                .contains("@GetMapping(\"/{orderId}/{lineNo}\")")
                .contains("@DeleteMapping(\"/{orderId}/{lineNo}\")")
                .contains("new OrderLineId(orderId, lineNo)");
        // The shared resource hook gained ordered-key path joining for composite addressing.
        assertThat(entries.get("shop/frontend/src/shared/api/useResource.ts"))
                .contains("Array.isArray(id)");
        // The page's React row key joins the key parts (no `id` property to fall back on).
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/order-line/ui/OrderLinePage.tsx"))
                .contains("const rowKey = (row: OrderLine) => [String(row.orderId), String(row.lineNo)].join('/')")
                .contains("rowKey={list.rowKey}");
        assertThat(entries.get("shop/frontend/src/shared/ui/Table.tsx"))
                .contains("key={rowKey(row)}")
                .doesNotContain(".id ?? idx");
    }

    @Test
    void fullstackEndpoint_formValidatesClientSideAndDataHookGuardsStaleResponses() throws Exception {
        Map<String, Object> name = Map.of("name", "name", "type", "String", "required", true, "length", 40);
        Map<String, Object> email = Map.of("name", "email", "type", "String", "email", true);
        Map<String, Object> age = Map.of("name", "age", "type", "Integer", "min", 0, "max", 120);
        Map<String, Object> code = Map.of("name", "code", "type", "String", "pattern", "[A-Z]{3}");
        Map<String, Object> rel = Map.of(
                "type", "MANY_TO_ONE", "fieldName", "company", "targetEntity", "Company", "required", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Company", "fields", List.of(pkField(), Map.of("name", "name", "type", "String"))),
                Map.of("name", "Contact", "fields", List.of(pkField(), name, email, age, code), "relations", List.of(rel))));
        Map<String, String> entries = generateZip(body);

        // The feature's model/validate.ts exports a validator derived from the same field metadata
        // as the DTO's Bean Validation; the generated PK is never validated.
        String validate = entries.get("shop/frontend/src/features/contact-form/model/validate.ts");
        assertThat(validate)
                .contains("export function validateContact(value: Partial<Contact>): Record<string, string>")
                .contains("import { t } from '@shared/i18n'")
                .contains("const blank = (v: unknown)")
                .contains("if (blank(value.name)) errors.name = t('required')")
                .contains("value.name.length > 40) errors.name = t('maxChars', { n: 40 })")
                .contains("Number(value.age) < 0) errors.age = t('atLeast', { n: 0 })")
                .contains("Number(value.age) > 120) errors.age = t('atMost', { n: 120 })")
                .contains("new RegExp('^(?:' + \"[A-Z]{3}\" + ')$').test(value.code)) errors.code = t('invalidFormat')")
                .contains("errors.email = t('invalidEmail')")
                .contains("if (blank(value.companyId)) errors.companyId = t('required')")
                .doesNotContain("errors.id =");
        // Nothing required on Company → no `blank` helper and no string import (the generated lint
        // forbids unused vars).
        assertThat(entries.get("shop/frontend/src/features/company-form/model/validate.ts"))
                .contains("export function validateCompany(")
                .doesNotContain("const blank")
                .doesNotContain("@shared/i18n");
        assertThat(entries.get("shop/frontend/src/features/contact-form/ui/ContactForm.tsx")).doesNotContain("validateContact");
        assertThat(entries.get("shop/frontend/src/features/contact-form/index.ts"))
                .contains("export { validateContact } from './model/validate'");
        // The page validates before calling the API, and — with no soft delete — offers no Undo.
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/contact/ui/ContactPage.tsx"))
                .contains("import { ContactDetail, ContactForm } from '@features/contact-form'")
                .contains("import { validateContact } from '@features/contact-form'")
                .contains("const clientErrors = validateContact(editing)")
                .contains("k in list.formErrors")
                .doesNotContain("label: t('undo')")
                .doesNotContain("create(removed");
        // The drawer is a real form (Enter submits, Save is type=submit, browser bubbles off).
        assertThat(entries.get("shop/frontend/src/shared/ui/FormDrawer.tsx"))
                .contains("<form")
                .contains("noValidate")
                .contains("onSubmit={e => {")
                .contains("type=\"submit\"")
                .doesNotContain("onClick={onSave}");
        // Out-of-order responses are dropped; a refetch keeps the current rows on screen.
        assertThat(entries.get("shop/frontend/src/shared/api/useResource.ts"))
                .contains("const requestRef = useRef(0)")
                .contains("if (requestId !== requestRef.current) return")
                .contains("const restore = useCallback(");
        assertThat(entries.get("shop/frontend/src/shared/ui/Table.tsx"))
                .contains("loading && rows.length === 0 ? (");
        assertThat(entries.get("shop/frontend/src/shared/ui/CardGrid.tsx"))
                .contains("loading && rows.length === 0 ? (");
    }

    @Test
    void fullstackEndpoint_roundNineFieldTypes() throws Exception {
        Map<String, String> entries = generateZip(FullstackExampleRequests.fieldTypesBody("react-tailwind-crud"));

        // Backend: each type's Java type, its column, its default.
        assertThat(contentEndingWith(entries, "/entity/Shift.java"))
                .contains("import java.time.LocalTime;")
                .contains("import java.time.Instant;")
                .contains("private LocalTime startsAt = java.time.LocalTime.parse(\"09:00\");")
                .contains("private Instant clockedIn;")
                // Money keeps two decimals; its currency is the frontend's to print.
                .contains("@Column(name = \"rate\", nullable = false, precision = 19, scale = 2)")
                .contains("private BigDecimal rate;")
                .contains("private BigDecimal bonus = new BigDecimal(\"12.5\");")
                // Markdown is long text to the database.
                .containsPattern("@Column\\(name = \"notes\", columnDefinition = \"TEXT\"\\)\\s+private String notes;");
        assertThat(contentEndingWith(entries, "/dto/ShiftDto.java"))
                .contains("LocalTime startsAt")
                .contains("Instant clockedIn")
                // A decimal bound, as on BIG_DECIMAL.
                .contains("@DecimalMin(value = \"0\")");
        // Money filters and sums like the number it is; a time and an instant are not filters.
        assertThat(contentEndingWith(entries, "/controller/ShiftController.java"))
                .contains("rateMin").contains("bonusMax")
                .doesNotContain("startsAtFrom").doesNotContain("clockedInFrom");
        // Bulk edit offers what one typed value can set: not the time, the instant or the Markdown.
        assertThat(contentEndingWith(entries, "/service/ShiftService.java"))
                .contains("case \"rate\" -> entity.setRate(value == null ? null : new java.math.BigDecimal(value.toString()));")
                .contains("case \"title\" -> entity.setTitle(value == null ? null : value.toString());")
                .doesNotContain("case \"startsAt\"").doesNotContain("case \"clockedIn\"").doesNotContain("case \"notes\"");
        assertThat(contentEndingWith(entries, "/config/DemoDataLoader.java"))
                .contains("java.time.Instant.now().minus(java.time.Duration.ofHours(i))");

        // Frontend: the shared formatters and the Markdown renderer ship because a field needs them.
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("frontend/src/shared/ui/format.ts"))
                .anyMatch(p -> p.endsWith("frontend/src/shared/ui/Markdown.tsx"));
        assertThat(ListPages.of(entries, "/ui/ShiftPage.tsx"))
                .contains("import { attachmentUrl, formatInstant, formatMoney } from '@shared/ui/format'")
                // The currency is upper-cased, and ILS when a money field names none.
                .contains("render: r => r.rate == null ? '—' : formatMoney(r.rate, 'USD')")
                .contains("formatMoney(r.bonus, 'ILS')")
                .contains("formatInstant(r.clockedIn)")
                .contains("render: r => r.startsAt == null ? '—' : String(r.startsAt)");
        assertThat(contentEndingWith(entries, "/ui/ShiftDetail.tsx"))
                .contains("import { Markdown } from '@shared/ui/Markdown'")
                .contains("<Markdown source={String(value.notes)} />")
                .contains("formatMoney(value.rate, 'USD')")
                .contains("formatInstant(value.clockedIn)");
        assertThat(contentEndingWith(entries, "/ui/ShiftForm.tsx"))
                .contains("import { fromLocalInput, toLocalInput } from '@shared/ui/format'")
                .contains("type=\"time\"")
                // An instant is picked in the browser's zone and stored in UTC.
                .contains("value={toLocalInput(value.clockedIn)}")
                .contains("onChange={e => set('clockedIn', fromLocalInput(e.target.value))}")
                .contains("type=\"number\" min=\"0\" step=\"0.01\"");
        assertThat(contentEndingWith(entries, "/model/types.ts"))
                .contains("rate").contains("startsAt");
    }

    @Test
    void fullstackEndpoint_fileAndImageFields() throws Exception {
        Map<String, String> entries = generateZip(FullstackExampleRequests.fieldTypesBody("react-tailwind-crud"));

        // The attachment package ships because a field needs it: a table behind a storage interface.
        for (String file : List.of("Attachment", "AttachmentRepository", "Attachments", "AttachmentStorage",
                "DbAttachmentStorage", "FileSystemAttachmentStorage", "S3AttachmentStorage", "AttachmentProperties",
                "AttachmentException", "AttachmentController", "AttachmentConfig", "Thumbnails")) {
            assertThat(entries.keySet()).as(file)
                    .anyMatch(p -> p.endsWith("backend/src/main/java/com/menora/rota/attachment/" + file + ".java"));
        }
        assertThat(contentEndingWith(entries, "/attachment/Attachment.java"))
                .contains("@Table(name = \"app_attachment\", indexes = @Index(name = \"ix_app_attachment_created\", columnList = \"created_at\"))")
                // A binary column, not a large object (which is an oid on PostgreSQL).
                .contains("@JdbcTypeCode(SqlTypes.LONG32VARBINARY)")
                .doesNotContain("@Lob");
        assertThat(contentEndingWith(entries, "/attachment/AttachmentController.java"))
                .contains("@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)")
                // Never served as a page: nosniff, and inline only for what its bytes say is a picture.
                .contains("\"X-Content-Type-Options\", \"nosniff\"")
                .contains("image != null ? ContentDisposition.inline() : ContentDisposition.attachment()")
                // A picture's small version, under the same read check; the original when none.
                .contains("@GetMapping(\"/{id}/thumbnail\")")
                .contains("if (small.isEmpty()) return download(id, user);");
        assertThat(contentEndingWith(entries, "/attachment/Thumbnails.java"))
                .contains("static final int SIZE = 320;")
                // The size is read before decoding; an enormous picture is never decoded.
                .contains("(long) width * height > MAX_PIXELS")
                .contains("reader.getWidth(0)");
        assertThat(contentEndingWith(entries, "/attachment/Attachment.java"))
                .contains("@Column(name = \"thumbnail\", length = Length.LONG32)");
        // Tables, cards and the details draw the thumbnail; the link opens the original.
        assertThat(entries.entrySet().stream().filter(e -> e.getKey().contains("frontend/src/"))
                .map(Map.Entry::getValue).filter(v -> v.contains("attachmentUrl(r.photo, true)")).count())
                .isPositive();
        assertThat(contentEndingWith(entries, "frontend/src/shared/ui/format.ts"))
                .contains("${thumbnail ? '/thumbnail' : ''}");

        // The field is the attachment's id; the entity reads the file's name beside it.
        assertThat(contentEndingWith(entries, "/entity/Shift.java"))
                .contains("@Column(name = \"contract\")")
                .contains("private java.util.UUID contract;")
                .contains("@Column(name = \"photo\", nullable = false)")
                .contains("@org.hibernate.annotations.Formula(\"(select a.file_name from app_attachment a where a.id = photo)\")")
                .contains("private String photoName;")
                .contains("public String getContractName()");
        assertThat(contentEndingWith(entries, "/dto/ShiftDto.java"))
                .contains("java.util.UUID contract")
                .contains("String contractName,")
                .contains("String photoName) {")
                .contains("entity.getPhotoName()");
        // A replaced or deleted record's files go with it; bulk edit and demo data leave files alone.
        assertThat(contentEndingWith(entries, "/service/ShiftService.java"))
                .contains("com.menora.rota.attachment.Attachments attachments")
                .contains("if (!java.util.Objects.equals(existing.getContract(), updated.getContract())) {")
                .contains("attachments.requireAttachable(updated.getContract(), \"contract\");")
                .contains("attachments.requireAttachable(entity.getPhoto(), \"photo\"); // a file no other record holds")
                .contains("attachments.delete(existing.getPhoto());")
                .doesNotContain("case \"contract\"").doesNotContain("case \"photo\"");
        assertThat(contentEndingWith(entries, "/web/ApiExceptionHandler.java"))
                .contains("MaxUploadSizeExceededException").contains("HttpStatus.PAYLOAD_TOO_LARGE");

        // Any database but the default H2 gets a datasource config class that names what it scans:
        // the attachment entity and repository are outside <domain>.entity / .repository and must
        // be named too, or the app does not start (no AttachmentRepository bean).
        Map<String, Object> onPostgres = FullstackExampleRequests.fieldTypesBody("react-tailwind-crud");
        onPostgres.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation"));
        assertThat(contentEndingWith(generateZip(onPostgres), "/config/PostgresqlConfig.java"))
                .contains("basePackages = { \"com.menora.rota.repository\", \"com.menora.rota.attachment\" },")
                .contains("em.setPackagesToScan(\"com.menora.rota.entity\", \"com.menora.rota.attachment\");");
        // Without a file field there is nothing more to scan and the class keeps its bytes.
        Map<String, Object> plain = FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
        plain.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation"));
        assertThat(contentEndingWith(generateZip(plain), "/config/PostgresqlConfig.java"))
                .contains("basePackages = \"com.menora.press.repository\",")
                .contains("em.setPackagesToScan(\"com.menora.press.entity\");");

        // Frontend: the upload call, the field component and its strings.
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("frontend/src/shared/ui/AttachmentField.tsx"));
        assertThat(contentEndingWith(entries, "/shared/api/client.ts"))
                .contains("async function upload<T>(path: string, file: File").contains("  upload,");
        assertThat(contentEndingWith(entries, "/shared/i18n/strings.ts"))
                .contains("chooseFile: 'Choose a file'").contains("chooseImage: 'בחירת תמונה'");
        assertThat(contentEndingWith(entries, "/ui/ShiftForm.tsx"))
                .contains("import { AttachmentField } from '@shared/ui/AttachmentField'")
                .contains("name={value.contractName}")
                .contains("onChange={(id, name) => onChange({ ...value, photo: id, photoName: name })}");
        assertThat(ListPages.of(entries, "/ui/ShiftPage.tsx"))
                .contains("<a href={attachmentUrl(r.contract)}")
                .contains("<img src={attachmentUrl(r.photo, true)} alt={r.photoName ?? ''} loading=\"lazy\"");
        assertThat(contentEndingWith(entries, "/model/types.ts"))
                .contains("photo: string | null").contains("photoName?: string | null");
        // A CSV carries no files.
        assertThat(contentEndingWith(entries, "/model/importFields.ts"))
                .doesNotContain("'contract'").doesNotContain("'photo'");
    }

    @Test
    void fullstackEndpoint_projectsWithoutTheNewTypesShipNoneOfTheirFiles() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "plain");
        body.put("packageName", "com.menora.plain");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "Note", "fields", List.of(pkField(),
                Map.of("name", "body", "type", "TEXT"), Map.of("name", "price", "type", "BIG_DECIMAL")))));
        Map<String, String> entries = generateZip(body);
        assertThat(entries.keySet()).noneMatch(p -> p.endsWith("/format.ts") || p.endsWith("/Markdown.tsx")
                || p.endsWith("/AttachmentField.tsx") || p.contains("/attachment/"));
        assertThat(contentEndingWith(entries, "/shared/api/client.ts")).doesNotContain("upload");
        assertThat(ListPages.of(entries, "/ui/NotePage.tsx")).doesNotContain("@shared/ui/format");
    }

    @Test
    void fullstackEndpoint_rejectsMisusedRoundNineTypes() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        record Bad(Map<String, Object> field, String message) {}
        for (Bad bad : List.of(
                new Bad(Map.of("name", "amount", "type", "MONEY", "primaryKey", true), "cannot be the primary key"),
                new Bad(Map.of("name", "amount", "type", "MONEY", "currency", "shekels"), "three-letter currency code"),
                new Bad(Map.of("name", "amount", "type", "BIG_DECIMAL", "currency", "USD"), "currency only allowed on MONEY"),
                new Bad(Map.of("name", "amount", "type", "MONEY", "defaultValue", "1.005"), "more than two decimals"),
                new Bad(Map.of("name", "opens", "type", "LOCAL_TIME", "defaultValue", "9 o'clock"), "not a valid LOCAL_TIME"),
                new Bad(Map.of("name", "seenAt", "type", "INSTANT", "defaultValue", "2026-01-31"), "not a valid INSTANT"),
                new Bad(Map.of("name", "notes", "type", "MARKDOWN", "length", 50), "length only allowed on STRING"),
                new Bad(Map.of("name", "scan", "type", "FILE", "defaultValue", "x.pdf"), "a file is uploaded, not typed"),
                new Bad(Map.of("name", "scan", "type", "IMAGE", "unique", true), "unique does not apply"),
                new Bad(Map.of("name", "scan", "type", "FILE", "primaryKey", true), "cannot be the primary key"))) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("artifactId", "demo");
            body.put("packageName", "com.menora.demo");
            body.put("bootVersion", "3.2.1");
            List<Map<String, Object>> fields = new java.util.ArrayList<>();
            if (!Boolean.TRUE.equals(bad.field().get("primaryKey"))) fields.add(pkField());
            fields.add(bad.field());
            body.put("entities", List.of(Map.of("name", "Thing", "fields", fields)));
            ResponseEntity<String> response = restTemplate.exchange("/starter-fullstack.zip",
                    org.springframework.http.HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            assertThat(response.getStatusCode()).as(bad.message()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains(bad.message());
        }
    }

    @Test
    void fullstackEndpoint_rejectsNamesTheGeneratedCodeUses() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (String name : List.of("Page", "Table", "Dashboard")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("artifactId", "demo");
            body.put("packageName", "com.menora.demo");
            body.put("bootVersion", "3.2.1");
            body.put("entities", List.of(Map.of("name", name, "fields", List.of(pkField()))));
            ResponseEntity<String> response = restTemplate.exchange("/starter-fullstack.zip",
                    org.springframework.http.HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            assertThat(response.getStatusCode()).as(name).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("entities[0].name '" + name + "' cannot be used");
        }
    }

    @Test
    void fullstackEndpoint_rendersTextAndUuidFieldTypes() throws Exception {
        // A UUID generated PK maps to @UuidGenerator + a java.util.UUID column (no import threading),
        // and a TEXT field maps to a SQL TEXT column rendered as a <textarea> in the form.
        Map<String, Object> uuidPk = new LinkedHashMap<>();
        uuidPk.put("name", "id");
        uuidPk.put("type", "UUID");
        uuidPk.put("primaryKey", true);
        uuidPk.put("generated", true);
        Map<String, Object> details = Map.of("name", "details", "type", "TEXT");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "notes");
        body.put("packageName", "com.menora.notes");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "Note", "fields", List.of(uuidPk, details))));

        Map<String, String> entries = generateZip(body);

        String entity = contentEndingWith(entries, "/entity/Note.java");
        assertThat(entity)
                .contains("@org.hibernate.annotations.UuidGenerator")
                .doesNotContain("@GeneratedValue")          // UUID uses the Hibernate generator, not IDENTITY
                .contains("private java.util.UUID id;")
                .contains("columnDefinition = \"TEXT\"")
                .contains("private String details;");
        // Repository/Service address the row by the FQN UUID type (no missing import).
        assertThat(contentEndingWith(entries, "/repository/NoteRepository.java"))
                .contains("JpaRepository<Note, java.util.UUID>");
        // TEXT renders as a textarea; the TS type is string-backed.
        assertThat(entries.get("notes/frontend/src/features/note-form/ui/NoteForm.tsx"))
                .contains("<textarea");
        assertThat(entries.get("notes/frontend/src/entities/note/model/types.ts"))
                .contains("id?: string | null")
                .contains("details?: string | null");
    }

    @Test
    void fullstackEndpoint_rendersFieldDefaults() throws Exception {
        // A declared defaultValue becomes the entity field's Java initializer, the form's initial
        // value for a new row, a null-guard in the DTO's toEntity and the demo-data seed value.
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("name", "Ticket");
        ticket.put("fields", List.of(pkField(),
                Map.of("name", "status", "type", "String", "defaultValue", "draft"),
                Map.of("name", "priority", "type", "Integer", "defaultValue", "1"),
                Map.of("name", "open", "type", "Boolean", "defaultValue", "true"),
                Map.of("name", "stage", "type", "ENUM", "enumValues", List.of("ACTIVE", "CLOSED"), "defaultValue", "ACTIVE"),
                Map.of("name", "due", "type", "LocalDate", "defaultValue", "2024-01-01"),
                Map.of("name", "code", "type", "String", "unique", true, "defaultValue", "X")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "desk");
        body.put("packageName", "com.menora.desk");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(ticket));
        body.put("opts", Map.of("scaffold", List.of("seedData")));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/entity/Ticket.java"))
                .contains("import java.time.LocalDate;")
                .contains("private String status = \"draft\";")
                .contains("private Integer priority = 1;")
                .contains("private Boolean open = true;")
                .contains("private TicketStageType stage = TicketStageType.ACTIVE;")
                .contains("private LocalDate due = LocalDate.parse(\"2024-01-01\");")
                .contains("private String code = \"X\";")
                .contains("private Long id;");
        // An omitted defaulted field keeps the entity initializer on create.
        assertThat(contentEndingWith(entries, "/dto/TicketDto.java"))
                .contains("if (this.status != null) entity.setStatus(this.status);")
                .contains("        entity.setId(this.id);");
        // The demo loader uses the default for plain fields but keeps row-numbered values for
        // unique columns (every seeded row must differ).
        assertThat(contentEndingWith(entries, "/config/DemoDataLoader.java"))
                .contains("row.setStatus(\"draft\");")
                .contains("row.setStage(Ticket.TicketStageType.ACTIVE);")
                .contains("row.setCode(label(\"Code\", i, Integer.MAX_VALUE));");
        // The page seeds a new record with the defaults.
        String page = ListPages.of(entries, "desk/frontend/src/pages/ticket/ui/TicketPage.tsx");
        assertThat(page)
                .contains("const newDefaults = (): Partial<Ticket> => ({ status: 'draft', priority: 1, open: true, stage: 'ACTIVE', due: '2024-01-01', code: 'X', })")
                // ...and with the list's one-value filters on top (an ACTIVE-filtered list makes ACTIVE rows).
                .contains("setEditing({ ...newDefaults(), ...fromFilters(filters) })")
                .contains("setInitial({ ...newDefaults(), ...fromFilters(filters) })")
                .contains("  if (oneValue(values['stage'])) record['stage'] = values['stage']")
                .contains("  if (values['open']) record['open'] = values['open'] === 'true'")
                .doesNotContain("setEditing({})");

        // The Lombok set renders the same initializers.
        body.put("backendTemplateSet", "spring-jpa-crud-lombok");
        assertThat(contentEndingWith(generateZip(body), "/entity/Ticket.java"))
                .contains("@Data")
                .contains("private String status = \"draft\";")
                .contains("private TicketStageType stage = TicketStageType.ACTIVE;");

        // A default that does not parse as the field type is a 400 naming the field.
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "desk");
            b.put("bootVersion", "3.2.1");
            b.put("entities", List.of(Map.of("name", "Ticket", "fields", List.of(pkField(),
                    Map.of("name", "priority", "type", "Integer", "defaultValue", "abc")))));
        });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("defaultValue 'abc' is not a valid INTEGER (field 'priority' on entity 'Ticket')");
    }

    @Test
    void fullstackEndpoint_rendersEnumLabels() throws Exception {
        // Task.status carries a label for two of its three constants (one with an apostrophe, to
        // pin the TS escaping); the third falls back to the humanized constant. Flag has only a
        // boolean breakdown, so its kanban lanes must be headed by the app's own i18n words.
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("name", "status");
        status.put("type", "Enum");
        status.put("enumValues", List.of("OPEN", "IN_REVIEW", "DONE"));
        status.put("enumLabels", Map.of("open", "Open", "DONE", "Won't fix"));
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("name", "Task");
        task.put("listViews", List.of("table", "kanban"));
        task.put("fields", List.of(pkField(), Map.of("name", "title", "type", "String"), status));

        Map<String, Object> flag = new LinkedHashMap<>();
        flag.put("name", "Flag");
        flag.put("listViews", List.of("table", "kanban"));
        flag.put("fields", List.of(pkField(), Map.of("name", "name", "type", "String"),
                Map.of("name", "active", "type", "Boolean")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "ops");
        body.put("packageName", "com.menora.ops");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(task, flag));
        body.put("opts", Map.of("scaffold", List.of("csvExport", "bulkUpdate")));

        Map<String, String> entries = generateZip(body);

        // One label map per enum, next to the union type; Options derives from it.
        String taskType = entries.get("ops/frontend/src/entities/task/model/types.ts");
        assertThat(taskType)
                .contains("export const TaskStatusTypeLabels: Record<TaskStatusType, string> = { OPEN: 'Open', IN_REVIEW: 'In review', DONE: 'Won\\'t fix' }")
                .contains("export const TaskStatusTypeOptions: { value: TaskStatusType; label: string }[] = TaskStatusTypeValues.map(v => ({ value: v, label: TaskStatusTypeLabels[v] }))");

        // Form select shows the label, posts the constant.
        assertThat(entries.get("ops/frontend/src/features/task-form/ui/TaskForm.tsx"))
                .contains("import { TaskStatusTypeOptions } from '@entities/task'")
                .contains("{ TaskStatusTypeOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>) }");

        // List page: the table cell, the filter options, the bulk-edit options and the kanban lanes.
        String taskPage = ListPages.of(entries, "ops/frontend/src/pages/task/ui/TaskPage.tsx");
        assertThat(taskPage)
                .contains("import { TaskStatusTypeLabels, } from '@entities/task'")
                .contains("render: r => r.status == null ? '—' : TaskStatusTypeLabels[r.status] }")
                .contains("options: [{ value: 'OPEN', label: 'Open' }, { value: 'IN_REVIEW', label: 'In review' }, { value: 'DONE', label: 'Won\\'t fix' }]")
                .contains("options?: { value: string; label: string }[] }> = {")
                .contains("<option key={o.value} value={o.value}>{o.label}</option>")
                .contains("groupValues={[{ value: 'OPEN', label: 'Open' }, { value: 'IN_REVIEW', label: 'In review' }, { value: 'DONE', label: 'Won\\'t fix' }]}");
        // Boolean lanes go through i18n, never a hardcoded True/False.
        assertThat(ListPages.of(entries, "ops/frontend/src/pages/flag/ui/FlagPage.tsx"))
                .contains("groupValues={[{ value: 'true', label: t('trueLabel') }, { value: 'false', label: t('falseLabel') }]}")
                .doesNotContain("Labels");

        // Detail view and dashboard chart read the same map; a boolean breakdown needs none.
        assertThat(contentEndingWith(entries, "/TaskDetail.tsx"))
                .contains("import { TaskStatusTypeLabels, } from '@entities/task'")
                .contains("TaskStatusTypeLabels[value.status]");
        assertThat(contentEndingWith(entries, "/DashboardPage.tsx"))
                .contains("import { TaskStatusTypeLabels } from '@entities/task'")
                .contains("field: 'status', labels: TaskStatusTypeLabels },")
                .contains("field: 'active' },")
                // The rollup's bucket key goes through the same label map ('' = the null group).
                .contains("const raw = b.key === '' ? '—' : b.key")
                .contains("label: labels?.[raw] ?? raw");

        // The shared filter bar accepts both bare values and value/label pairs.
        assertThat(entries.get("ops/frontend/src/shared/ui/FilterBar.tsx"))
                .contains("options?: Array<string | { value: string; label: string }>")
                .contains("(f.options ?? []).map(toOption)");

        // Backend: the Java enum and the CSV export keep the raw constants — labels are a UI concern.
        assertThat(contentEndingWith(entries, "/entity/Task.java")).contains("OPEN, IN_REVIEW, DONE");
        assertThat(contentEndingWith(entries, "/controller/TaskController.java"))
                .contains("@GetMapping(\"/export.csv\")")
                .doesNotContain("Won't fix");
    }

    @Test
    void fullstackEndpoint_escapesLabelsWhereverTheyAreSpliced() throws Exception {
        // Hebrew abbreviations carry a geresh (') or gershayim ("): מס' , מע"מ , ת"ז. A label is
        // spliced into single-quoted TS literals, double-quoted JSX attributes and JSX children —
        // each needs its own escaping, or the generated project does not compile.
        Map<String, Object> number = new LinkedHashMap<>();
        number.put("name", "number"); number.put("type", "String"); number.put("label", "מס' חשבונית");
        Map<String, Object> vat = new LinkedHashMap<>();
        vat.put("name", "vat"); vat.put("type", "BigDecimal"); vat.put("label", "מע\"מ");
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("name", "note"); note.put("type", "String"); note.put("label", "a <b> & {c} &amp; \\d");
        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("name", "Invoice");
        invoice.put("label", "חשבונית מס'");
        invoice.put("labelPlural", "חשבוניות \"מס\" <1>");
        invoice.put("fields", List.of(pkField(), number, vat, note));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "books");
        body.put("packageName", "com.menora.books");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(invoice));
        body.put("opts", Map.of("scaffold", List.of("bulkUpdate", "csvImport")));
        body.put("dashboardTitle", "ספרים של \"מנורה\" {2026}");
        body.put("dashboardOverview", "it's <new>");

        Map<String, String> entries = generateZip(body);

        // Single-quoted TS literals: apostrophe and backslash escaped, a double quote left alone.
        String page = ListPages.of(entries, "books/frontend/src/pages/invoice/ui/InvoicePage.tsx");
        assertThat(page)
                .contains("label: 'מס\\' חשבונית', sortKey: 'number'")
                .contains("label: 'מע\"מ', sortKey: 'vat'")
                .contains("label: 'a <b> & {c} &amp; \\\\d', sortKey: 'note'")
                .contains("t('newX', { x: 'חשבונית מס\\'' })")
                .contains("t('importX', { x: 'חשבוניות \"מס\" <1>' })")
                // JSX children: the page heading and the bulk-edit field picker's option text.
                .contains(">חשבוניות \"מס\" &lt;1&gt;</h1>")
                .contains("<option value=\"number\">מס' חשבונית</option>")
                .contains("<option value=\"note\">a &lt;b&gt; & &#123;c&#125; &amp;amp; \\d</option>");
        // Double-quoted JSX attributes: no backslash escapes there, only character references.
        assertThat(entries.get("books/frontend/src/features/invoice-form/ui/InvoiceForm.tsx"))
                .contains("<Field label=\"מס' חשבונית\"")
                .contains("<Field label=\"מע&quot;מ\"")
                .contains("<Field label=\"a <b> & {c} &amp;amp; \\d\"");
        assertThat(contentEndingWith(entries, "/InvoiceDetail.tsx")).contains("<Row label=\"מע&quot;מ\">");
        assertThat(contentEndingWith(entries, "/importFields.ts"))
                .contains("label: 'מס\\' חשבונית', kind:");
        assertThat(entries.get("books/frontend/src/app/App.tsx")).contains("label: 'חשבוניות \"מס\" <1>'");
        assertThat(contentEndingWith(entries, "/DashboardPage.tsx"))
                .contains("label: 'חשבוניות \"מס\" <1>', view: 'invoice'")
                .contains(">ספרים של \"מנורה\" &#123;2026&#125;</h1>")
                .contains("it's &lt;new&gt;");

        // The Menora set authors its own page and dashboard: same rules.
        body.put("frontendTemplateSet", "react-menora-digital-crud");
        Map<String, String> menora = generateZip(body);
        assertThat(ListPages.of(menora, "books/frontend/src/pages/invoice/ui/InvoicePage.tsx"))
                .contains("label: 'מס\\' חשבונית', sortKey: 'number'")
                .contains("<SectionHeader title=\"חשבוניות &quot;מס&quot; <1>\" align=\"start\" />")
                .contains("caption=\"חשבוניות &quot;מס&quot; <1>\"");
        assertThat(contentEndingWith(menora, "/DashboardPage.tsx"))
                .contains("'ספרים של \"מנורה\" {2026}.'")
                .contains("'it\\'s <new>'");
    }

    @Test
    void fullstackEndpoint_rejectsBadEnumLabels() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Unknown key names the field and the key.
        Map<String, Object> unknownKey = new LinkedHashMap<>();
        unknownKey.put("name", "status"); unknownKey.put("type", "Enum");
        unknownKey.put("enumValues", List.of("OPEN", "DONE"));
        unknownKey.put("enumLabels", Map.of("GONE", "Gone"));
        ResponseEntity<String> r1 = restTemplate.exchange("/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(bodyWithField(unknownKey), headers), String.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r1.getBody()).contains("enumLabels key 'GONE' is not one of the enumValues [OPEN, DONE] (field 'status' on entity 'Task')");

        // Labels on a non-ENUM field.
        Map<String, Object> notEnum = new LinkedHashMap<>();
        notEnum.put("name", "title"); notEnum.put("type", "String");
        notEnum.put("enumLabels", Map.of("X", "x"));
        ResponseEntity<String> r2 = restTemplate.exchange("/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(bodyWithField(notEnum), headers), String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r2.getBody()).contains("enumLabels only allowed when type=ENUM (field 'title' on entity 'Task')");
    }
}
