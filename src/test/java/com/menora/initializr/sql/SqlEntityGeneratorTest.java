package com.menora.initializr.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlEntityGeneratorTest {

    private final SqlEntityGenerator generator = new SqlEntityGenerator();

    // ── Dialect: PostgreSQL ───────────────────────────────────────────────────

    @Test
    void postgresql_generatesEntityAndRepositoryWithLombok() {
        String sql = """
                CREATE TABLE users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP,
                    amount_due NUMERIC(12,2),
                    is_active BOOLEAN
                );
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo", null);

        GeneratedJavaFile entity = findFile(files, "entity/Users.java");
        assertThat(entity.content())
                .contains("package com.menora.demo.entity;")
                .contains("@Entity")
                .contains("@Table(name = \"users\")")
                .contains("@Data")
                .contains("@NoArgsConstructor")
                .contains("@AllArgsConstructor")
                .contains("import java.time.LocalDateTime;")
                .contains("import java.math.BigDecimal;")
                .contains("@Id")
                .contains("@GeneratedValue(strategy = GenerationType.IDENTITY)")
                .contains("private Long id;")
                .contains("@Column(nullable = false, length = 200)")
                .contains("private String email;")
                .contains("private LocalDateTime createdAt;")
                .contains("@Column(name = \"created_at\"")
                .contains("private BigDecimal amountDue;")
                .contains("private Boolean isActive;");

        GeneratedJavaFile repo = findFile(files, "repository/UsersRepository.java");
        assertThat(repo.content())
                .contains("public interface UsersRepository extends JpaRepository<Users, Long>");
    }

    @Test
    void postgresql_uuidAndJsonbTypes() {
        String sql = """
                CREATE TABLE events (
                    id UUID PRIMARY KEY,
                    payload JSONB,
                    blob_data BYTEA
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.POSTGRESQL, "p", null),
                "entity/Events.java").content();
        assertThat(entity)
                .contains("import java.util.UUID;")
                .contains("private UUID id;")
                .contains("private String payload;")
                .contains("private byte[] blobData;");
    }

    // ── Dialect: MSSQL ────────────────────────────────────────────────────────

    @Test
    void mssql_nvarcharAndUniqueidentifier() {
        String sql = """
                CREATE TABLE accounts (
                    id UNIQUEIDENTIFIER PRIMARY KEY,
                    display_name NVARCHAR(100) NOT NULL,
                    is_locked BIT,
                    last_login DATETIME2
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.MSSQL, "p", null),
                "entity/Accounts.java").content();
        assertThat(entity)
                .contains("import java.util.UUID;")
                .contains("import java.time.LocalDateTime;")
                .contains("private UUID id;")
                .contains("private String displayName;")
                .contains("private Boolean isLocked;")
                .contains("private LocalDateTime lastLogin;");
    }

    // ── Dialect: Oracle ───────────────────────────────────────────────────────

    @Test
    void oracle_numberPrecisionMapping() {
        String sql = """
                CREATE TABLE orders (
                    id NUMBER(18,0) PRIMARY KEY,
                    count_small NUMBER(5,0),
                    amount NUMBER(10,2),
                    description VARCHAR2(500)
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.ORACLE, "p", null),
                "entity/Orders.java").content();
        assertThat(entity)
                .contains("private Long id;")
                .contains("private Integer countSmall;")
                .contains("private BigDecimal amount;")
                .contains("private String description;");
    }

    @Test
    void oracle_numberWildcardPrecisionMapsToBigDecimal() {
        // Oracle's NUMBER(*, N) means "max precision (38), scale N". JSqlParser
        // rejects * in type args, so the generator normalizes to NUMBER(38, N)
        // before parsing — which then maps to BigDecimal (precision 38 > 18).
        String sql = """
                CREATE TABLE employees (
                    employee_id   NUMBER(*, 0) PRIMARY KEY,
                    first_name    VARCHAR2(50),
                    last_name     VARCHAR2(50),
                    age           NUMBER(*, 0),
                    salary        NUMBER(10, 2),
                    department_id NUMBER(*, 0),
                    created_at    DATE DEFAULT SYSDATE
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.ORACLE, "p", null),
                "entity/Employees.java").content();
        assertThat(entity)
                .contains("import java.math.BigDecimal;")
                .contains("import java.time.LocalDate;")
                .contains("private BigDecimal employeeId;")
                .contains("private String firstName;")
                .contains("private String lastName;")
                .contains("private BigDecimal age;")
                .contains("private BigDecimal salary;")
                .contains("private BigDecimal departmentId;")
                .contains("private LocalDate createdAt;");
    }

    @Test
    void oracle_numberSingleWildcardNormalizes() {
        String sql = "CREATE TABLE t (x NUMBER(*) PRIMARY KEY);";
        String entity = findFile(generator.generate(sql, SqlDialect.ORACLE, "p", null),
                "entity/T.java").content();
        assertThat(entity)
                .contains("import java.math.BigDecimal;")
                .contains("private BigDecimal x;");
    }

    // ── Dialect: H2 ───────────────────────────────────────────────────────────

    @Test
    void h2_identityAndCommonTypes() {
        String sql = """
                CREATE TABLE notes (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(100) NOT NULL,
                    body CLOB,
                    created DATE
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.H2, "p", null),
                "entity/Notes.java").content();
        assertThat(entity)
                .contains("@GeneratedValue(strategy = GenerationType.IDENTITY)")
                .contains("private Long id;")
                .contains("private String title;")
                .contains("private LocalDate created;");
    }

    // ── Dialect: DB2 ──────────────────────────────────────────────────────────

    @Test
    void db2_generatedAsIdentity() {
        String sql = """
                CREATE TABLE line_items (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    sku VARCHAR(32) NOT NULL,
                    when_added TIMESTAMP
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.DB2, "p", null),
                "entity/LineItems.java").content();
        assertThat(entity)
                .contains("private Long id;")
                .contains("@GeneratedValue(strategy = GenerationType.IDENTITY)")
                .contains("private String sku;")
                .contains("private LocalDateTime whenAdded;");
    }

    @Test
    void db2_ignoresCommentAndGrantAndIndexStatements() {
        // Realistic DB2 export — multiple CREATE TABLE blocks interleaved with
        // COMMENT ON, CREATE INDEX, and GRANT statements that the wizard does
        // not act on. Parser must skip them silently and still emit every entity.
        String sql = """
                CREATE TABLE EMPLOYEES (
                    EMPLOYEE_ID INTEGER NOT NULL PRIMARY KEY,
                    FIRST_NAME VARCHAR(50) NOT NULL,
                    EMAIL VARCHAR(100) NOT NULL UNIQUE,
                    HIRE_DATE DATE NOT NULL,
                    STATUS VARCHAR(20) DEFAULT 'ACTIVE',
                    CREATED_AT TIMESTAMP NOT NULL DEFAULT CURRENT TIMESTAMP
                );

                COMMENT ON TABLE EMPLOYEES IS 'Employee master data table';
                COMMENT ON COLUMN EMPLOYEES.EMAIL IS 'Employee email address';

                CREATE INDEX IDX_EMP_EMAIL ON EMPLOYEES(EMAIL);
                CREATE UNIQUE INDEX IDX_EMP_ID ON EMPLOYEES(EMPLOYEE_ID);

                CREATE TABLE DEPARTMENTS (
                    DEPARTMENT_ID INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY,
                    DEPARTMENT_NAME VARCHAR(100) NOT NULL,
                    PRIMARY KEY (DEPARTMENT_ID)
                );

                GRANT SELECT ON EMPLOYEES TO PUBLIC;
                GRANT SELECT, INSERT ON DEPARTMENTS TO USER APP_USER;

                CREATE TABLE EMPLOYEE_PROJECTS (
                    ASSIGNMENT_ID INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY,
                    EMPLOYEE_ID INTEGER NOT NULL,
                    ROLE VARCHAR(50) NOT NULL,
                    PRIMARY KEY (ASSIGNMENT_ID)
                );
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.DB2, "p", null);
        assertThat(files).extracting(GeneratedJavaFile::relativePath)
                .anyMatch(p -> p.endsWith("entity/Employees.java"))
                .anyMatch(p -> p.endsWith("entity/Departments.java"))
                .anyMatch(p -> p.endsWith("entity/EmployeeProjects.java"));
    }

    @Test
    void db2_defaultCurrentTimestampParses() {
        String sql = """
                CREATE TABLE audit (
                    id INTEGER NOT NULL PRIMARY KEY,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT TIMESTAMP,
                    seen_on DATE DEFAULT CURRENT DATE,
                    seen_at TIME DEFAULT CURRENT TIME
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.DB2, "p", null),
                "entity/Audit.java").content();
        assertThat(entity)
                .contains("private Integer id;")
                .contains("private LocalDateTime createdAt;");
    }

    @Test
    void db2_malformedTrailingStatementThrowsWithSnippet() {
        String sql = """
                CREATE TABLE good (id INTEGER NOT NULL PRIMARY KEY);
                CREATE TABLE bad (id INTEGER NOT NULL,
                """ + "GRANT SELECT ON good TO PUBLIC;N foo,OTAL * TAX_RATE)),";
        try {
            generator.generate(sql, SqlDialect.DB2, "p", null);
            throw new AssertionError("expected SqlParseException");
        } catch (SqlEntityGenerator.SqlParseException ex) {
            assertThat(ex.statementIndex()).isNotNull();
            assertThat(ex.statementIndex()).isGreaterThanOrEqualTo(2);
            assertThat(ex.statementSnippet()).isNotBlank();
            assertThat(ex.getMessage()).contains("statement #");
        }
    }

    @Test
    void db2ForI_schemaQualifiedWithForColumnAndCcsidAndCompositePk() {
        // Real DB2-for-i (iSeries) export: schema-qualified table + constraint,
        // CCSID column clauses, FOR COLUMN system short-names, NUMERIC(n,0) codes.
        String sql = """
                CREATE TABLE ENTV.TD_APP_STP (
                    REQUEST_ID CHAR(24) CCSID 424 NOT NULL ,
                    STEP_CODE NUMERIC(2, 0) NOT NULL ,
                    STATUS_CODE FOR COLUMN STATU00001 NUMERIC(3, 0) NOT NULL ,
                    ERROR_MESSAGE FOR COLUMN ERROR00001 VARCHAR(100) CCSID 424 NOT NULL ,
                    TIMESTEMP CHAR(26) CCSID 424 NOT NULL DEFAULT '' ,
                    CONSTRAINT ENTV.Q_ENTV_TD_APP_STP_REQUEST_ID_00001 PRIMARY KEY( REQUEST_ID , STEP_CODE ) )
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.DB2, "p", null);

        String entity = findFile(files, "entity/TdAppStp.java").content();
        assertThat(entity)
                .contains("@Table(name = \"TD_APP_STP\", schema = \"ENTV\")")
                .contains("@IdClass(TdAppStpId.class)")
                // NUMERIC(2,0)/(3,0) → integral, not BigDecimal
                .contains("private Integer stepCode;")
                .contains("private Integer statusCode;")
                // CHAR/VARCHAR → String, real (long) column names kept
                .contains("private String requestId;")
                .contains("private String errorMessage;")
                .contains("private String timestemp;")
                // DB2-for-i clauses must not leak into the generated source
                .doesNotContain("CCSID")
                .doesNotContain("FOR COLUMN")
                .doesNotContain("STATU00001")
                .doesNotContain("BigDecimal");

        // Composite PK id class is emitted with the two key fields.
        String idClass = findFile(files, "entity/TdAppStpId.java").content();
        assertThat(idClass)
                .contains("class TdAppStpId implements Serializable")
                .contains("private String requestId;")
                .contains("private Integer stepCode;");
    }

    // ── Options ──────────────────────────────────────────────────────────────

    @Test
    void repositorySkippedWhenFlagFalse() {
        String sql = "CREATE TABLE foo (id BIGINT PRIMARY KEY);";
        SqlDepOptions opts = new SqlDepOptions("entity",
                List.of(new SqlTableOptions("foo", false)));
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "p", opts);
        assertThat(files).hasSize(1);
        assertThat(files.get(0).relativePath()).contains("entity/Foo.java");
    }

    @Test
    void customSubPackageUsed() {
        String sql = "CREATE TABLE foo (id BIGINT PRIMARY KEY);";
        SqlDepOptions opts = new SqlDepOptions("domain", List.of());
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "p", opts);
        assertThat(findFile(files, "domain/Foo.java").content())
                .contains("package p.domain;");
    }

    @Test
    void foreignKeyToUnknownTableFallsBackToTodo() {
        String sql = """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    FOREIGN KEY (user_id) REFERENCES users(id)
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.POSTGRESQL, "p", null),
                "entity/Orders.java").content();
        assertThat(entity)
                .contains("// TODO: map as @ManyToOne")
                .contains("private Long userId;")
                .doesNotContain("@ManyToOne(");
    }

    @Test
    void foreignKeyToKnownTableEmitsManyToOne() {
        String sql = """
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY,
                    email VARCHAR(200)
                );
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    FOREIGN KEY (user_id) REFERENCES users(id)
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.POSTGRESQL, "p", null),
                "entity/Orders.java").content();
        assertThat(entity)
                .contains("import jakarta.persistence.ManyToOne;")
                .contains("import jakarta.persistence.JoinColumn;")
                .contains("import jakarta.persistence.FetchType;")
                .contains("@ManyToOne(fetch = FetchType.LAZY)")
                .contains("@JoinColumn(name = \"user_id\", nullable = false)")
                .contains("private Users user;")
                .doesNotContain("// TODO: map as @ManyToOne")
                .doesNotContain("private Long userId;");
    }

    @Test
    void multipleForeignKeysToSameTableGetDistinctFieldNames() {
        String sql = """
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY
                );
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    buyer_id BIGINT NOT NULL,
                    seller_id BIGINT NOT NULL,
                    FOREIGN KEY (buyer_id) REFERENCES users(id),
                    FOREIGN KEY (seller_id) REFERENCES users(id)
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.POSTGRESQL, "p", null),
                "entity/Orders.java").content();
        assertThat(entity)
                .contains("@JoinColumn(name = \"buyer_id\", nullable = false)")
                .contains("private Users buyer;")
                .contains("@JoinColumn(name = \"seller_id\", nullable = false)")
                .contains("private Users seller;");
    }

    @Test
    void selfReferentialForeignKeyResolves() {
        String sql = """
                CREATE TABLE employees (
                    id BIGINT PRIMARY KEY,
                    manager_id BIGINT,
                    FOREIGN KEY (manager_id) REFERENCES employees(id)
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.POSTGRESQL, "p", null),
                "entity/Employees.java").content();
        assertThat(entity)
                .contains("@ManyToOne(fetch = FetchType.LAZY)")
                .contains("@JoinColumn(name = \"manager_id\")")
                .contains("private Employees manager;")
                .doesNotContain("// TODO: map as @ManyToOne");
    }

    @Test
    void compositeForeignKeyEmitsJoinColumns() {
        String sql = """
                CREATE TABLE parent (
                    a BIGINT NOT NULL,
                    b BIGINT NOT NULL,
                    PRIMARY KEY (a, b)
                );
                CREATE TABLE child (
                    id BIGINT PRIMARY KEY,
                    a BIGINT NOT NULL,
                    b BIGINT NOT NULL,
                    FOREIGN KEY (a, b) REFERENCES parent(a, b)
                );
                """;
        String entity = findFile(generator.generate(sql, SqlDialect.POSTGRESQL, "p", null),
                "entity/Child.java").content();
        assertThat(entity)
                .contains("import jakarta.persistence.JoinColumns;")
                .contains("@ManyToOne(fetch = FetchType.LAZY)")
                .contains("@JoinColumns({")
                .contains("@JoinColumn(name = \"a\", referencedColumnName = \"a\")")
                .contains("@JoinColumn(name = \"b\", referencedColumnName = \"b\")")
                .contains("private Parent parent;");
        // Only one association block should be emitted for the composite FK
        assertThat(entity.split("@ManyToOne", -1).length - 1).isEqualTo(1);
        // Non-PK composite-FK columns are absorbed by the association
        assertThat(entity)
                .doesNotContain("private Long a;")
                .doesNotContain("private Long b;");
    }

    @Test
    void compositePrimaryKeyEmitsIdClass() {
        String sql = """
                CREATE TABLE enrollment (
                    student_id BIGINT NOT NULL,
                    course_id BIGINT NOT NULL,
                    enrolled_on DATE,
                    PRIMARY KEY (student_id, course_id)
                );
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "p", null);

        String entity = findFile(files, "entity/Enrollment.java").content();
        assertThat(entity).contains("@IdClass(EnrollmentId.class)");

        String idClass = findFile(files, "entity/EnrollmentId.java").content();
        assertThat(idClass)
                .contains("public class EnrollmentId implements Serializable")
                .contains("private Long studentId;")
                .contains("private Long courseId;");

        // Repository uses the composite Id type
        assertThat(findFile(files, "repository/EnrollmentRepository.java").content())
                .contains("extends JpaRepository<Enrollment, EnrollmentId>");
    }

    @Test
    void invalidSqlThrowsSqlParseException() {
        assertThatThrownBy(() ->
                generator.generate("CREATE TABLE x (( nonsense", SqlDialect.POSTGRESQL, "p", null))
                .isInstanceOf(SqlEntityGenerator.SqlParseException.class);
    }

    @Test
    void emptySqlReturnsNoFiles() {
        assertThat(generator.generate("", SqlDialect.POSTGRESQL, "p", null)).isEmpty();
        assertThat(generator.generate(null, SqlDialect.POSTGRESQL, "p", null)).isEmpty();
    }

    @Test
    void detectTableNamesReturnsOrderedList() {
        String sql = "CREATE TABLE a (id BIGINT); CREATE TABLE b (id BIGINT);";
        assertThat(generator.detectTableNames(sql, SqlDialect.POSTGRESQL)).containsExactly("a", "b");
    }

    // ── REST stack generation (apiMode) ───────────────────────────────────────

    private static final String USERS_AND_ORDERS = """
            CREATE TABLE users (
                id BIGINT PRIMARY KEY,
                email VARCHAR(200)
            );
            CREATE TABLE orders (
                id BIGINT PRIMARY KEY,
                total NUMERIC(12,2),
                user_id BIGINT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id)
            );
            """;

    @Test
    void apiModeNone_yieldsNoRestStack() {
        List<GeneratedJavaFile> files = generator.generate(USERS_AND_ORDERS, SqlDialect.POSTGRESQL, "p",
                new SqlDepOptions("entity", List.of(), SqlApiMode.NONE));
        assertThat(files).extracting(GeneratedJavaFile::relativePath)
                .noneMatch(p -> p.contains("/controller/"))
                .noneMatch(p -> p.contains("/service/"))
                .noneMatch(p -> p.contains("/dto/"))
                .noneMatch(p -> p.contains("/mapper/"));
    }

    @Test
    void entityDirect_generatesControllerAndServiceButNoDto() {
        List<GeneratedJavaFile> files = generator.generate(USERS_AND_ORDERS, SqlDialect.POSTGRESQL, "p",
                new SqlDepOptions("entity", List.of(), SqlApiMode.ENTITY_DIRECT));

        assertThat(files).extracting(GeneratedJavaFile::relativePath)
                .anyMatch(p -> p.endsWith("service/OrdersService.java"))
                .anyMatch(p -> p.endsWith("controller/OrdersController.java"))
                .noneMatch(p -> p.contains("/dto/"))
                .noneMatch(p -> p.contains("/mapper/"));

        String controller = findFile(files, "controller/OrdersController.java").content();
        assertThat(controller)
                .contains("@RequestMapping(\"/api/orders\")")
                .contains("public Orders getOne(@PathVariable Long id)")
                .contains("return service.findById(id);")
                .contains("import p.entity.Orders;")
                .doesNotContain("Dto");
    }

    @Test
    void inlineDto_flattensForeignKeyAndAddsMapping() {
        List<GeneratedJavaFile> files = generator.generate(USERS_AND_ORDERS, SqlDialect.POSTGRESQL, "p",
                new SqlDepOptions("entity", List.of(), SqlApiMode.INLINE_DTO));

        String dto = findFile(files, "dto/OrdersDto.java").content();
        assertThat(dto)
                .contains("public record OrdersDto(")
                .contains("Long id")
                .contains("BigDecimal total")
                .contains("Long userId")
                .contains("public static OrdersDto from(Orders entity)")
                .contains("entity.getUser() == null ? null : entity.getUser().getId()")
                .contains("public Orders toEntity()")
                .contains("Users user = new Users();")
                .contains("user.setId(this.userId);")
                .contains("entity.setUser(user);");

        String service = findFile(files, "service/OrdersService.java").content();
        assertThat(service)
                .contains("public Page<Orders> findAll(Pageable pageable)")
                .contains("public Orders findById(Long id)")
                .contains("existing.setTotal(updated.getTotal());")
                .contains("existing.setUser(updated.getUser());")
                .doesNotContain("existing.setId(");

        String controller = findFile(files, "controller/OrdersController.java").content();
        assertThat(controller)
                .contains("public OrdersDto create(@RequestBody OrdersDto body)")
                .contains("OrdersDto.from(service.create(body.toEntity()))")
                .contains(".map(OrdersDto::from)");
    }

    @Test
    void mapstructDto_generatesMapperInterface() {
        List<GeneratedJavaFile> files = generator.generate(USERS_AND_ORDERS, SqlDialect.POSTGRESQL, "p",
                new SqlDepOptions("entity", List.of(), SqlApiMode.MAPSTRUCT_DTO));

        String mapper = findFile(files, "mapper/OrdersMapper.java").content();
        assertThat(mapper)
                .contains("import p.config.MapstructConfig;")
                .contains("@Mapper(config = MapstructConfig.class)")
                .contains("public interface OrdersMapper {")
                .contains("@Mapping(target = \"userId\", source = \"user.id\")")
                .contains("OrdersDto toDto(Orders entity);")
                .contains("@Mapping(target = \"user\", source = \"userId\", qualifiedByName = \"userFromId\")")
                .contains("Orders toEntity(OrdersDto dto);")
                .contains("@Named(\"userFromId\")")
                .contains("default Users userFromId(Long id)");

        String controller = findFile(files, "controller/OrdersController.java").content();
        assertThat(controller)
                .contains("private final OrdersMapper mapper;")
                .contains("mapper.toDto(")
                .contains("mapper.toEntity(body)");
    }

    @Test
    void compositePk_controllerUsesOrderedPathSegments() {
        String sql = """
                CREATE TABLE enrollment (
                    student_id BIGINT NOT NULL,
                    course_id BIGINT NOT NULL,
                    enrolled_on DATE,
                    PRIMARY KEY (student_id, course_id)
                );
                """;
        String controller = findFile(generator.generate(sql, SqlDialect.POSTGRESQL, "p",
                        new SqlDepOptions("entity", List.of(), SqlApiMode.ENTITY_DIRECT)),
                "controller/EnrollmentController.java").content();
        assertThat(controller)
                .contains("import p.entity.EnrollmentId;")
                .contains("@GetMapping(\"/{studentId}/{courseId}\")")
                .contains("getOne(@PathVariable Long studentId, @PathVariable Long courseId)")
                .contains("new EnrollmentId(studentId, courseId)");
    }

    @Test
    void apiModeSkippedForTableWithoutPrimaryKey() {
        String sql = "CREATE TABLE logline (message VARCHAR(500));";
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "p",
                new SqlDepOptions("entity", List.of(), SqlApiMode.INLINE_DTO));
        assertThat(files).extracting(GeneratedJavaFile::relativePath)
                .anyMatch(p -> p.endsWith("entity/Logline.java"))
                .noneMatch(p -> p.contains("/controller/"))
                .noneMatch(p -> p.contains("/dto/"));
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private GeneratedJavaFile findFile(List<GeneratedJavaFile> files, String suffix) {
        return files.stream()
                .filter(f -> f.relativePath().endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No file ending with " + suffix
                        + " in: " + files.stream().map(GeneratedJavaFile::relativePath).toList()));
    }
}
