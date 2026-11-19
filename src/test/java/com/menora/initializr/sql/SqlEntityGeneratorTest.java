package com.menora.initializr.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlEntityGeneratorTest {

    private final SqlEntityGenerator generator = new SqlEntityGenerator();

    @Test
    void db2_keepsAnUnderscoreBeforeADigitInTheColumnName() {
        // The field camel-cases to futureUse8, which snake-cases back to future_use8 — the
        // entity must name the real column instead of deriving it.
        String sql = """
                CREATE TABLE VNRF.ORDERS (
                    ORD_NO NUMERIC(6, 0) NOT NULL PRIMARY KEY,
                    FRF_QOD_MISHLOAH_FUTURE_USE_8 CHAR(1),
                    ADDR_2_ID NUMERIC(6, 0)
                );
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.DB2, "com.menora.demo", null);
        assertThat(findFile(files, "entity/Orders.java").content())
                .contains("@Column(name = \"FRF_QOD_MISHLOAH_FUTURE_USE_8\", length = 1)")
                .contains("private String frfQodMishloahFutureUse8;")
                .contains("@Column(name = \"ADDR_2_ID\"");
    }

    @Test
    void postgresql_mixedCaseNamesSurviveTheNamingStrategy() {
        // Boot's naming strategy would turn orderDate into order_date and lower-case a quoted
        // "UnitPrice": unquoted humps are folded, quoted names stay exact with the project's
        // own naming strategy alongside.
        String sql = """
                CREATE TABLE app."OrderItems" (
                    id BIGINT PRIMARY KEY,
                    "UnitPrice" NUMERIC(10, 2),
                    orderDate DATE,
                    "status" VARCHAR(10)
                );
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo", null);
        assertThat(findFile(files, "entity/OrderItems.java").content())
                .contains("@Table(name = \"\\\"OrderItems\\\"\", schema = \"app\")")
                .contains("@Column(name = \"\\\"UnitPrice\\\"\", precision = 10, scale = 2)")
                .contains("@Column(name = \"ORDERDATE\")")
                .contains("private String status;")
                .doesNotContain("name = \"status\"");
        assertThat(findFile(files, "config/QuotedNamesNamingStrategy.java").content())
                .contains("package com.menora.demo.config;")
                .contains("public class QuotedNamesNamingStrategy extends CamelCaseToUnderscoresNamingStrategy");
    }

    @Test
    void db2_quoteEverythingExportNeedsNoNamingStrategy() {
        // db2look quotes every name; upper-case ones are plain DB2 names, rendered unquoted.
        String sql = """
                CREATE TABLE "VNRF"."ORDERS" ("ORD_NO" NUMERIC(6, 0) NOT NULL PRIMARY KEY, "ORD_SHEM" CHAR(20));
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.DB2, "com.menora.demo", null);
        assertThat(findFile(files, "entity/Orders.java").content())
                .contains("@Table(name = \"ORDERS\", schema = \"VNRF\")")
                .contains("@Column(name = \"ORD_SHEM\"");
        assertThat(files).noneMatch(f -> f.relativePath().endsWith("QuotedNamesNamingStrategy.java"));
    }

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

    /** Real SQL Server Management Studio "Script Table As CREATE" output: bracket-quoted
     *  schema/column/type names, CLUSTERED PK, WITH (…) index options, ON [PRIMARY]
     *  filegroups — none of which JSqlParser parses without normalizeMssql. */
    @Test
    void mssql_bracketQuotedTSqlScript() {
        String sql = """
                CREATE TABLE [dbo].[clearing_request_status_history](
                    [id] [nvarchar](36) NOT NULL,
                    [request_id] [nvarchar](36) NOT NULL,
                    [status_code] [int] NULL,
                    [status_description] [nvarchar](255) NULL,
                    [created_date] [datetime2](7) NOT NULL,
                    [archive_doc_id] [varchar](15) NULL,
                PRIMARY KEY CLUSTERED
                (
                    [id] ASC
                )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
                ) ON [PRIMARY]
                """;
        List<TableModel> tables = generator.parseTablesForImport(sql, SqlDialect.MSSQL);
        assertThat(tables).hasSize(1);
        TableModel t = tables.get(0);
        assertThat(t.name()).isEqualTo("clearing_request_status_history");
        assertThat(t.schema()).isEqualTo("dbo");
        assertThat(t.pkColumns()).containsExactly("id");

        String entity = findFile(generator.generate(sql, SqlDialect.MSSQL, "p", null),
                "entity/ClearingRequestStatusHistory.java").content();
        assertThat(entity)
                .contains("@Id")
                .contains("private String id;")
                .contains("private Integer statusCode;")
                .contains("private LocalDateTime createdDate;")
                .contains("private String archiveDocId;");
    }

    /** The same T-SQL with no explicit dialect (H2 default) still parses, auto-routed by
     *  the bracket-quoting detector. Types degrade to String off the MSSQL branch — fine. */
    @Test
    void mssql_bracketQuotedTSqlAutoDetectedAtDefaultDialect() {
        String sql = """
                CREATE TABLE [dbo].[widgets](
                    [id] [nvarchar](36) NOT NULL,
                    [name] [nvarchar](255) NULL,
                PRIMARY KEY CLUSTERED ([id] ASC) WITH (PAD_INDEX = OFF) ON [PRIMARY]
                ) ON [PRIMARY]
                """;
        List<TableModel> tables = generator.parseTablesForImport(sql, SqlDialect.H2);
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).name()).isEqualTo("widgets");
        assertThat(tables.get(0).pkColumns()).containsExactly("id");
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

    @Test
    void db2ForI_systemNamesWithAtAndHashAndCommentLinesParse() {
        // Real iSeries export: every FOR COLUMN short name starts with '@' and some
        // carry '#'; SQL150x diagnostic comment lines sit between the columns —
        // sometimes two in a row, which once blanked out is the LF run JSqlParser
        // reads as a statement terminator.
        String sql = """
                CREATE TABLE VNRF.VNPSXN (
                --  SQL150B   10   REUSEDLT(*NO) in table VNPSXN in VNRF ignored.
                    FRF_ZIHUY_HEVRAT_BITUAH FOR COLUMN @ZIHUY_HVR NUMERIC(1, 0) NOT NULL DEFAULT 0 ,
                --  SQL150D   10   EDTCDE in column FRF_ZIHUY_HEVRAT_BITUAH ignored.
                    FRF_MISPAR_SOXEN FOR COLUMN @SOXEN#    NUMERIC(6, 0) NOT NULL DEFAULT 0 ,
                --  SQL150D   10   VALUES in column FRF_MISPAR_SOXEN ignored.
                --  SQL150D   10   EDTCDE in column FRF_MISPAR_SOXEN ignored.
                    FRF_QOD_SOXEN FOR COLUMN @QSOXEN    CHAR(1) CCSID 424 NOT NULL DEFAULT '' ,
                    FRF_QOD_MISHLOAH_ISHUR_MAS_SX FOR COLUMN @QMSSX_MAS CHAR(1) CCSID 424 NOT NULL DEFAULT ' ' ,
                    FRF_HESHBON_ANALAT_HESHBONOT FOR COLUMN @HESHBONH  NUMERIC(11, 0) NOT NULL DEFAULT 0 ,
                    FRF_KAMUT_PLS_HAYIM_SOXEN FOR COLUMN @#PLS_SXNH DECIMAL(5, 0) NOT NULL DEFAULT 0 ,
                    PRIMARY KEY( FRF_ZIHUY_HEVRAT_BITUAH , FRF_MISPAR_SOXEN ) )
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.DB2, "p", null);

        String entity = findFile(files, "entity/Vnpsxn.java").content();
        assertThat(entity)
                .contains("@Table(name = \"VNPSXN\", schema = \"VNRF\")")
                .contains("@IdClass(VnpsxnId.class)")
                .contains("private Integer frfZihuyHevratBituah;")
                .contains("private Integer frfMisparSoxen;")
                .contains("private String frfQodSoxen;")
                .contains("private String frfQodMishloahIshurMasSx;")
                .contains("private Long frfHeshbonAnalatHeshbonot;")
                .contains("private Integer frfKamutPlsHayimSoxen;")
                .doesNotContain("FOR COLUMN")
                .doesNotContain("CCSID")
                .doesNotContain("@ZIHUY_HVR")
                .doesNotContain("@SOXEN#")
                .doesNotContain("@#PLS_SXNH")
                .doesNotContain("BigDecimal");
    }

    @Test
    void db2ForI_clausesAutoDetectedUnderH2Default() {
        // The fullstack Import-from-DDL drawer defaults to H2; Db2-for-i clauses must
        // still be normalized so the script parses without the user picking DB2.
        String sql = """
                CREATE TABLE VNRF.VNPSXN (
                    FRF_ZIHUY_HEVRAT_BITUAH FOR COLUMN @ZIHUY_HVR NUMERIC(1, 0) NOT NULL DEFAULT 0 ,
                    FRF_SHEM_SOXEN FOR COLUMN @SXN_SHEM  CHAR(22) CCSID 424 NOT NULL DEFAULT '' ,
                    PRIMARY KEY( FRF_ZIHUY_HEVRAT_BITUAH ) )
                """;
        List<String> tables = generator.detectTableNames(sql, SqlDialect.H2);
        assertThat(tables).containsExactly("VNPSXN");
    }

    @Test
    void consecutiveBlankLinesInsideCreateTableDoNotSplitTheStatement() {
        // JSqlParser 4.9 treats "\n\n\n" as a statement terminator; two real blank
        // lines (or two adjacent -- comments) inside a CREATE TABLE must not 400.
        String sql = "CREATE TABLE t (\n  id BIGINT PRIMARY KEY,\n\n\n\n  name VARCHAR(20),\n"
                + "-- a\n-- b\n-- c\n  note VARCHAR(10)\n)";
        List<String> tables = generator.detectTableNames(sql, SqlDialect.H2);
        assertThat(tables).containsExactly("t");
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
                .noneMatch(p -> p.contains("/dto/"))
                // …and no repository: the class is not an entity (below).
                .noneMatch(p -> p.contains("/repository/"));
        // A table without a key cannot be a JPA entity: Hibernate refuses one with no @Id, and
        // the application did not start because of it. The class is written with its mapping and
        // a note, without @Entity, so one log table does not take the project down.
        assertThat(findFile(files, "entity/Logline.java").content())
                .contains("// TODO: logline has no primary key, and a JPA entity needs an @Id")
                .contains("// @Entity\n@Table(name = \"logline\")")
                .doesNotContain("\n@Entity\n")
                .contains("private String message;");
        // A table with a key is an entity as before.
        assertThat(findFile(generator.generate("CREATE TABLE keyed (id BIGINT PRIMARY KEY);", SqlDialect.POSTGRESQL, "p", null),
                "entity/Keyed.java").content()).contains("\n@Entity\n@Table(name = \"keyed\")").doesNotContain("TODO");
    }

    // ── SELECT import (read-only view) ─────────────────────────────────────────

    @Test
    void parseSelect_usesAliasesElseColumnNames() {
        SqlEntityGenerator.SelectProjection p = generator.parseSelectForImport(
                "SELECT u.id, u.full_name AS fullName FROM users u", SqlDialect.H2);
        assertThat(p.columns()).containsExactly("id", "fullName");
        assertThat(p.fromTable()).isEqualTo("users");
        assertThat(p.rawSql()).contains("FROM users");
    }

    @Test
    void parseSelect_rejectsSelectStar() {
        assertThatThrownBy(() -> generator.parseSelectForImport("SELECT * FROM users", SqlDialect.H2))
                .isInstanceOf(SqlEntityGenerator.SqlParseException.class)
                .hasMessageContaining("*");
    }

    @Test
    void parseSelect_rejectsUnaliasedExpression() {
        assertThatThrownBy(() -> generator.parseSelectForImport("SELECT count(*) FROM users", SqlDialect.H2))
                .isInstanceOf(SqlEntityGenerator.SqlParseException.class)
                .hasMessageContaining("alias");
    }

    @Test
    void parseSelect_rejectsNonSelect() {
        assertThatThrownBy(() -> generator.parseSelectForImport("UPDATE users SET x = 1", SqlDialect.H2))
                .isInstanceOf(SqlEntityGenerator.SqlParseException.class);
    }

    // ── generate(): read-only @Subselect view from a SELECT ───────────────────

    @Test
    void generate_selectProducesReadOnlyViewEntity() {
        List<GeneratedJavaFile> files = generator.generate(
                "SELECT u.id AS id, u.full_name AS fullName FROM users u",
                SqlDialect.POSTGRESQL, "p",
                new SqlDepOptions("entity", List.of(), SqlApiMode.ENTITY_DIRECT));

        String entity = findFile(files, "entity/Users.java").content();
        assertThat(entity)
                .contains("@Entity")
                .contains("@Immutable")
                .contains("@Subselect(\"\"\"")
                .contains("SELECT u.id AS id, u.full_name AS fullName FROM users u")
                .contains("@Id")
                .contains("@Column(name = \"id\")")
                // A camelCase alias is folded, or Boot's naming strategy would read full_name.
                .contains("@Column(name = \"FULLNAME\")")
                .contains("private String id;")
                .contains("private String fullName;")
                .contains("// TODO")
                .doesNotContain("@Table")
                .doesNotContain("@GeneratedValue");

        String service = findFile(files, "service/UsersService.java").content();
        assertThat(service)
                .contains("public Users findById(String id)")
                .contains("public Page<Users> findAll(Pageable pageable)")
                .doesNotContain("public Users create(")
                .doesNotContain("public Users update(")
                .doesNotContain("public void delete(");

        String controller = findFile(files, "controller/UsersController.java").content();
        assertThat(controller)
                .contains("@GetMapping")
                .doesNotContain("@PostMapping")
                .doesNotContain("@PutMapping")
                .doesNotContain("@DeleteMapping");
    }

    @Test
    void generate_mixedDdlAndSelect() {
        String sql = """
                CREATE TABLE products (
                    id BIGINT PRIMARY KEY,
                    sku VARCHAR(64) NOT NULL
                );
                SELECT u.id AS id, u.email AS email FROM users u;
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "p",
                new SqlDepOptions("entity", List.of(), SqlApiMode.ENTITY_DIRECT));

        // The table entity keeps full CRUD …
        assertThat(findFile(files, "entity/Products.java").content())
                .contains("@Table(name = \"products\")")
                .doesNotContain("@Subselect");
        assertThat(findFile(files, "controller/ProductsController.java").content())
                .contains("@PostMapping")
                .contains("@DeleteMapping");

        // … while the view entity is read-only.
        assertThat(findFile(files, "entity/Users.java").content())
                .contains("@Subselect(\"\"\"");
        assertThat(findFile(files, "controller/UsersController.java").content())
                .contains("@GetMapping")
                .doesNotContain("@PostMapping");
    }

    @Test
    void generate_selectStarThrows() {
        assertThatThrownBy(() -> generator.generate("SELECT * FROM users", SqlDialect.H2, "p", null))
                .isInstanceOf(SqlEntityGenerator.SqlParseException.class)
                .hasMessageContaining("*");
    }

    @Test
    void generate_viewNameDedupedAgainstTable() {
        String sql = """
                CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50));
                SELECT u.id AS id FROM users u;
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "p", null);

        assertThat(files).extracting(GeneratedJavaFile::relativePath)
                .anyMatch(p -> p.endsWith("entity/Users.java"))
                .anyMatch(p -> p.endsWith("entity/Users2.java"));
        assertThat(findFile(files, "entity/Users.java").content()).contains("@Table");
        assertThat(findFile(files, "entity/Users2.java").content()).contains("@Subselect");
    }

    // ── Heuristic column extraction (native SQL JSqlParser can't parse) ─────────

    @Test
    void extractColumns_aliasesAndDottedAndFunctions() {
        assertThat(SqlSelectReader.extractProjectedColumns(
                "SELECT a AS x, b AS y FROM t")).containsExactly("x", "y");
        assertThat(SqlSelectReader.extractProjectedColumns(
                "SELECT u.id, u.full_name FROM users u")).containsExactly("id", "full_name");
        assertThat(SqlSelectReader.extractProjectedColumns(
                "SELECT count(*) AS n, max(p) AS hi FROM t")).containsExactly("n", "hi");
    }

    @Test
    void extractColumns_skipsStarAndUnaliasedExpressions() {
        // bare * and an unaliased function can't name a field; a plain column survives.
        assertThat(SqlSelectReader.extractProjectedColumns(
                "SELECT *, count(*), id FROM t")).containsExactly("id");
        assertThat(SqlSelectReader.extractProjectedColumns(
                "SELECT t.* FROM t")).isEmpty();
    }

    @Test
    void extractColumns_doesNotSplitInsideFunctionArgs() {
        assertThat(SqlSelectReader.extractProjectedColumns(
                "SELECT coalesce(a, b) AS c, d FROM t")).containsExactly("c", "d");
    }

    @Test
    void extractColumns_handlesDistinctAndDistinctOn() {
        assertThat(SqlSelectReader.extractProjectedColumns(
                "SELECT DISTINCT a, b FROM t")).containsExactly("a", "b");
        assertThat(SqlSelectReader.extractProjectedColumns(
                "SELECT DISTINCT ON (region) region AS region, amount AS amount FROM sales"))
                .containsExactly("region", "amount");
    }

    @Test
    void extractColumns_resolvesCteOuterProjection() {
        assertThat(SqlSelectReader.extractProjectedColumns(
                "WITH recent AS (SELECT id FROM orders) SELECT a, b FROM recent"))
                .containsExactly("a", "b");
    }

    @Test
    void parseSelect_fallsBackForNativeSql() {
        // Oracle MODEL clause — valid native SQL JSqlParser 4.9 can't parse → heuristic path.
        SqlEntityGenerator.SelectProjection p = generator.parseSelectForImport(
                "SELECT id AS id, name AS name FROM t MODEL DIMENSION BY (id) MEASURES (name) RULES ()",
                SqlDialect.ORACLE);
        assertThat(p.heuristic()).isTrue();
        assertThat(p.columns()).containsExactly("id", "name");
        assertThat(p.rawSql()).contains("MODEL");
    }

    // ── Constraints: ALTER TABLE, UNIQUE, CHECK … IN ─────────────────────────

    @Test
    void constraintsDeclaredByAlterTableAreRead() {
        // pg_dump, SSMS and most schema exports create the table bare and add its constraints
        // afterwards. ALTER used to be skipped whole: no key (so no repository, no REST stack)
        // and no associations.
        String sql = """
                CREATE TABLE authors (id BIGINT NOT NULL, name VARCHAR(100));
                CREATE TABLE books (id BIGINT NOT NULL, author_id BIGINT NOT NULL, isbn VARCHAR(20), title VARCHAR(200));
                ALTER TABLE ONLY public.authors ADD CONSTRAINT authors_pkey PRIMARY KEY (id);
                ALTER TABLE ONLY books ADD CONSTRAINT books_pkey PRIMARY KEY (id);
                ALTER TABLE "books" ADD CONSTRAINT books_author_fk FOREIGN KEY (author_id) REFERENCES public.authors(id);
                ALTER TABLE books ADD CONSTRAINT books_isbn UNIQUE (isbn);
                ALTER TABLE books ADD CONSTRAINT books_once UNIQUE (author_id, title);
                ALTER TABLE books ADD COLUMN pages INTEGER;
                ALTER TABLE elsewhere ADD CONSTRAINT x PRIMARY KEY (id);
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.ENTITY_DIRECT));
        String books = findFile(files, "entity/Books.java").content();
        assertThat(books)
                .contains("    @Id\n    @Column(nullable = false)\n    private Long id;")
                .contains("    @ManyToOne(fetch = FetchType.LAZY)\n    @JoinColumn(name = \"author_id\", nullable = false)\n    private Authors author;")
                // One column: on the column. Several: on the table.
                .contains("    @Column(unique = true, length = 20)\n    private String isbn;")
                .contains("@Table(name = \"books\", uniqueConstraints = {@UniqueConstraint(columnNames = {\"author_id\", \"title\"})})")
                .contains("import jakarta.persistence.UniqueConstraint;")
                // ADD COLUMN is not a constraint: skipped as before.
                .doesNotContain("pages");
        // A key means a repository and the REST stack.
        assertThat(files).extracting(GeneratedJavaFile::relativePath)
                .contains("src/main/java/{{packagePath}}/repository/BooksRepository.java",
                        "src/main/java/{{packagePath}}/controller/AuthorsController.java");
    }

    @Test
    void tableLevelUniqueAndInlineUniqueAreMapped() {
        String sql = """
                CREATE TABLE seats (
                    id BIGINT PRIMARY KEY,
                    code VARCHAR(10) UNIQUE,
                    hall VARCHAR(10) NOT NULL,
                    row_no INTEGER NOT NULL,
                    seat_no INTEGER NOT NULL,
                    tag VARCHAR(30),
                    UNIQUE (hall, row_no, seat_no),
                    CONSTRAINT seats_tag UNIQUE (tag)
                );
                """;
        String seats = findFile(generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo", null), "entity/Seats.java").content();
        assertThat(seats)
                .contains("@Column(unique = true, length = 10)\n    private String code;")
                .contains("@Column(unique = true, length = 30)\n    private String tag;")
                .contains("uniqueConstraints = {@UniqueConstraint(columnNames = {\"hall\", \"row_no\", \"seat_no\"})}")
                // The key is unique by being the key: no unique = true on it.
                .contains("    @Id\n    @Column(nullable = false)\n    private Long id;");

        // A ';' or an apostrophe inside a comment is prose: it used to cut the statement in two.
        String commented = """
                -- seats; the hall's own
                CREATE TABLE seats (
                    id BIGINT PRIMARY KEY, /* the key; it's generated elsewhere */
                    code VARCHAR(10) -- unique; see the index
                );
                """;
        assertThat(findFile(generator.generate(commented, SqlDialect.POSTGRESQL, "com.menora.demo", null), "entity/Seats.java").content())
                .contains("private String code;");
    }

    @Test
    void aCheckInBecomesAnEnumOnlyWhenItsValuesAreJavaNames() {
        // The enum is written to the column as its constants are spelt (EnumType.STRING), and
        // the database already holds the values: 'OPEN' can be a constant, 'in progress' cannot.
        String sql = """
                CREATE TABLE tickets (
                    id BIGINT PRIMARY KEY,
                    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'closed')),
                    stage VARCHAR(20),
                    priority VARCHAR(20) CHECK (priority IN ('low', 'very high')),
                    kind VARCHAR(10),
                    CONSTRAINT tickets_stage CHECK (stage IN ('new', 'default')),
                    CHECK (kind IN ('BUG', 'TASK'))
                );
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.INLINE_DTO));
        String tickets = findFile(files, "entity/Tickets.java").content();
        assertThat(tickets)
                .contains("    @Enumerated(EnumType.STRING)\n    @Column(nullable = false, length = 20)\n    private TicketsStatus status;")
                .contains("    @Enumerated(EnumType.STRING)\n    @Column(length = 10)\n    private TicketsKind kind;")
                .contains("import jakarta.persistence.EnumType;").contains("import jakarta.persistence.Enumerated;")
                // 'very high' is not a Java name, 'default' is a keyword: both stay text.
                .contains("    @Column(length = 20)\n    private String priority;")
                .contains("    @Column(length = 20)\n    private String stage;")
                // Same package: no import of the enum.
                .doesNotContain("import com.menora.demo.entity.TicketsStatus;");
        assertThat(findFile(files, "entity/TicketsStatus.java").content())
                .contains("public enum TicketsStatus {\n    OPEN,\n    closed\n}");
        assertThat(files).extracting(GeneratedJavaFile::relativePath)
                .doesNotContain("src/main/java/{{packagePath}}/entity/TicketsPriority.java",
                        "src/main/java/{{packagePath}}/entity/TicketsStage.java");
        // The DTO carries the same type, imported from the entity package.
        assertThat(findFile(files, "dto/TicketsDto.java").content())
                .contains("import com.menora.demo.entity.TicketsStatus;")
                .contains("        TicketsStatus status,");
    }

    /**
     * What an export declares after the table: a CHECK added by ALTER TABLE is an enum like one in
     * the CREATE, and a unique index over plain columns a unique constraint — but not a partial one
     * (duplicates are allowed outside its WHERE) nor one over an expression.
     */
    @Test
    void anAlterTableCheckAndAUniqueIndexAreRead() {
        String sql = """
                CREATE TABLE tickets (
                    id BIGINT PRIMARY KEY,
                    kind VARCHAR(10),
                    code VARCHAR(10),
                    hall VARCHAR(10),
                    seat VARCHAR(10),
                    email VARCHAR(80),
                    tenant VARCHAR(10)
                );
                ALTER TABLE tickets ADD CONSTRAINT tickets_kind CHECK (kind IN ('BUG', 'TASK'));
                CREATE UNIQUE INDEX tickets_code ON tickets (code);
                CREATE UNIQUE INDEX IF NOT EXISTS tickets_seat ON public.tickets USING btree (hall, seat);
                CREATE UNIQUE INDEX tickets_email ON tickets (lower(email));
                CREATE UNIQUE INDEX tickets_tenant ON tickets (tenant) WHERE tenant IS NOT NULL;
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo", null);
        assertThat(findFile(files, "entity/Tickets.java").content())
                .contains("    @Enumerated(EnumType.STRING)\n    @Column(length = 10)\n    private TicketsKind kind;")
                .contains("@Column(unique = true, length = 10)\n    private String code;")
                .contains("uniqueConstraints = {@UniqueConstraint(columnNames = {\"hall\", \"seat\"})}")
                .contains("    @Column(length = 80)\n    private String email;")
                .contains("    @Column(length = 10)\n    private String tenant;");
        assertThat(findFile(files, "entity/TicketsKind.java").content())
                .contains("public enum TicketsKind {\n    BUG,\n    TASK\n}");
    }

    @Test
    void aCompositeKeyPartThatIsAForeignKeyStaysAKeyField() {
        // order_items(order_id, line_no) with order_id → orders: the entity replaced the key
        // column with the association, so OrderItemsId.orderId had no property to bind to and
        // Hibernate refused the entity (the application did not start). The key part stays a
        // scalar @Id; the association reads through the same column and is never written.
        String sql = """
                CREATE TABLE orders (id BIGINT PRIMARY KEY, reference VARCHAR(40));
                CREATE TABLE order_items (
                    order_id BIGINT NOT NULL,
                    line_no INTEGER NOT NULL,
                    quantity INTEGER,
                    PRIMARY KEY (order_id, line_no),
                    FOREIGN KEY (order_id) REFERENCES orders(id)
                );
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.INLINE_DTO));
        assertThat(findFile(files, "entity/OrderItems.java").content())
                .contains("@IdClass(OrderItemsId.class)")
                .contains("    @Id\n    @Column(name = \"order_id\", nullable = false)\n    private Long orderId;")
                .contains("    @ManyToOne(fetch = FetchType.LAZY)\n"
                        + "    @JoinColumn(name = \"order_id\", insertable = false, updatable = false)\n    private Orders order;");
        assertThat(findFile(files, "entity/OrderItemsId.java").content()).contains("private Long orderId;").contains("private Integer lineNo;");
        // The DTO carries the key part as itself; nothing is flagged as left out.
        assertThat(findFile(files, "dto/OrderItemsDto.java").content())
                .contains("        Long orderId,\n        Integer lineNo,\n        Integer quantity\n")
                .doesNotContain("TODO");
        // An update never rewrites the key.
        assertThat(findFile(files, "service/OrderItemsService.java").content())
                .contains("existing.setQuantity(updated.getQuantity());").doesNotContain("setOrderId(").doesNotContain("setOrder(");
    }

    @Test
    void aKeyThatIsAlsoAForeignKeyStaysAScalarId() {
        // A shared-key one-to-one (profiles.user_id is the key and points at users). It was mapped
        // "@Id @OneToOne @MapsId" on the association alone; Spring Data cannot tell the id type of
        // such an entity ("does not define an IdClass") and the application did not start.
        String sql = """
                CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(60));
                CREATE TABLE profiles (
                    user_id BIGINT PRIMARY KEY REFERENCES users(id),
                    bio VARCHAR(400)
                );
                """;
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.INLINE_DTO));
        assertThat(findFile(files, "entity/Profiles.java").content())
                .contains("    @Id\n    @Column(name = \"user_id\", nullable = false)\n    private Long userId;")
                .contains("    @OneToOne(fetch = FetchType.LAZY)\n"
                        + "    @JoinColumn(name = \"user_id\", insertable = false, updatable = false)\n    private Users user;")
                .doesNotContain("@MapsId").doesNotContain("@IdClass");
        assertThat(findFile(files, "repository/ProfilesRepository.java").content()).contains("JpaRepository<Profiles, Long>");
        assertThat(findFile(files, "dto/ProfilesDto.java").content()).contains("        Long userId,\n        String bio\n");
        assertThat(findFile(files, "controller/ProfilesController.java").content()).contains("getOne(@PathVariable Long id)");
    }

    @Test
    void aForeignKeyWrittenOnTheColumnIsRead() {
        // team_id BIGINT REFERENCES teams(id): the usual spelling in PostgreSQL and MySQL scripts.
        // Only the table-level FOREIGN KEY (…) was read, so such a script gave no associations.
        String sql = """
                CREATE TABLE teams (id BIGINT PRIMARY KEY, name VARCHAR(60));
                CREATE TABLE players (
                    id BIGINT PRIMARY KEY,
                    team_id BIGINT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
                    coach_id BIGINT REFERENCES app.teams,
                    agent_id BIGINT REFERENCES agents (id),
                    notes VARCHAR(200)
                );
                """;
        String players = findFile(generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo", null), "entity/Players.java").content();
        assertThat(players)
                .contains("    @ManyToOne(fetch = FetchType.LAZY)\n    @JoinColumn(name = \"team_id\", nullable = false)\n    private Teams team;")
                // No column named: the target's key. A schema before the name is dropped.
                .contains("    @ManyToOne(fetch = FetchType.LAZY)\n    @JoinColumn(name = \"coach_id\")\n    private Teams coach;")
                // A table outside the script stays a plain column.
                .contains("    @Column(name = \"agent_id\")\n    private Long agentId;")
                .contains("private String notes;");
    }

    // ── What the project's build adds: validation, the Flyway baseline, tests ──

    private static final String VALIDATED_SQL = """
            CREATE TABLE teams (id BIGINT PRIMARY KEY, name VARCHAR(60) NOT NULL);
            CREATE TABLE players (
                id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                team_id BIGINT NOT NULL REFERENCES teams(id),
                coach_id BIGINT REFERENCES teams(id),
                nickname VARCHAR(30) NOT NULL,
                bio TEXT,
                shirt INTEGER NOT NULL,
                position VARCHAR(10) NOT NULL CHECK (position IN ('GOAL', 'FIELD'))
            );
            """;

    @Test
    void withTheValidationStarterTheDtoCarriesTheColumnsConstraints() {
        List<GeneratedJavaFile> files = generator.generate(VALIDATED_SQL, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.INLINE_DTO), new SqlProjectFacts(true, false, false));
        assertThat(findFile(files, "dto/PlayersDto.java").content())
                // The database generates the key: never asked of a client.
                .contains("        Long id,\n")
                .contains("        @NotNull @Size(max = 30) String nickname,\n")
                // Nullable, no length: nothing to say.
                .contains("        String bio,\n")
                .contains("        @NotNull Integer shirt,\n")
                // An enum has its values; a length would be noise.
                .contains("        @NotNull PlayersPosition position,\n")
                // A required link is a required id; an optional one is not.
                .contains("        @NotNull Long teamId,\n        Long coachId\n")
                .contains("import jakarta.validation.constraints.NotNull;")
                .contains("import jakarta.validation.constraints.Size;");
        // A key the client supplies is required.
        assertThat(findFile(files, "dto/TeamsDto.java").content())
                .contains("        @NotNull Long id,\n        @NotNull @Size(max = 60) String name\n");
        assertThat(findFile(files, "controller/PlayersController.java").content())
                .contains("import jakarta.validation.Valid;")
                .contains("public PlayersDto create(@Valid @RequestBody PlayersDto body)")
                .contains("public PlayersDto update(@PathVariable Long id, @Valid @RequestBody PlayersDto body)");
        // The DTO is the request body, so the entity stays as it was.
        assertThat(findFile(files, "entity/Players.java").content()).doesNotContain("@NotNull").doesNotContain("@Size");
    }

    @Test
    void exposedDirectlyTheEntityCarriesTheConstraints() {
        List<GeneratedJavaFile> files = generator.generate(VALIDATED_SQL, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.ENTITY_DIRECT), new SqlProjectFacts(true, false, false));
        assertThat(findFile(files, "entity/Players.java").content())
                .contains("    @NotNull\n    @Size(max = 30)\n    @Column(nullable = false, length = 30)\n    private String nickname;")
                .contains("    @NotNull\n    @Enumerated(EnumType.STRING)\n    @Column(nullable = false, length = 10)\n    private PlayersPosition position;")
                .contains("    @NotNull\n    @ManyToOne(fetch = FetchType.LAZY)\n    @JoinColumn(name = \"team_id\", nullable = false)\n    private Teams team;")
                .contains("    @ManyToOne(fetch = FetchType.LAZY)\n    @JoinColumn(name = \"coach_id\")\n    private Teams coach;")
                // Nullable, no length: nothing to say.
                .contains("\n\n    private String bio;\n")
                .contains("import jakarta.validation.constraints.NotNull;")
                .contains("import jakarta.validation.constraints.Size;");
        assertThat(findFile(files, "controller/PlayersController.java").content())
                .contains("public Players create(@Valid @RequestBody Players body)");
    }

    @Test
    void withoutTheValidationStarterNothingNamesIt() {
        // The annotations would not compile: jakarta.validation is not on the build.
        for (SqlApiMode mode : SqlApiMode.values()) {
            List<GeneratedJavaFile> files = generator.generate(VALIDATED_SQL, SqlDialect.POSTGRESQL, "com.menora.demo",
                    new SqlDepOptions("entity", List.of(), mode), new SqlProjectFacts(false, true, true));
            assertThat(files).filteredOn(f -> f.relativePath().endsWith(".java")).allSatisfy(f ->
                    assertThat(f.content()).as(f.relativePath()).doesNotContain("jakarta.validation").doesNotContain("@Valid"));
        }
    }

    @Test
    void withFlywayTheScriptIsTheFirstMigration() {
        String sql = """
                -- the catalogue; kept by the data team
                CREATE TABLE genres (id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL);
                CREATE INDEX genres_name ON genres (name);
                ALTER TABLE genres ADD CONSTRAINT genres_name_once UNIQUE (name);
                SELECT id, name FROM genres;
                """;
        SqlDepOptions opts = new SqlDepOptions("entity", List.of(), SqlApiMode.NONE);
        List<GeneratedJavaFile> files = generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo", opts,
                new SqlProjectFacts(false, true, false));
        String baseline = findFile(files, "db/migration/V1__init.sql").content();
        assertThat(baseline)
                .startsWith("-- Baseline: the schema this project was generated from")
                .contains("baseline-on-migrate")
                // Every statement as written, comments and all — the index and the ALTER too…
                .contains("-- the catalogue; kept by the data team\nCREATE TABLE genres (id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL);\n")
                .contains("CREATE INDEX genres_name ON genres (name);\n")
                .contains("ALTER TABLE genres ADD CONSTRAINT genres_name_once UNIQUE (name);\n")
                // …but not a query: it became a view entity, and a migration that only reads does nothing.
                .doesNotContain("SELECT id, name")
                .endsWith(";\n");
        // It takes the place of the Flyway dependency's placeholder migration.
        assertThat(SqlEntityGenerator.FLYWAY_BASELINE_PATH).isEqualTo("src/main/resources/db/migration/V1__init.sql");

        // Not without Flyway on the build, and not when the caller says no.
        assertThat(generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo", opts, SqlProjectFacts.NONE))
                .extracting(GeneratedJavaFile::relativePath).noneMatch(p -> p.endsWith(".sql"));
        assertThat(generator.generate(sql, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.NONE, false, false), new SqlProjectFacts(false, true, false)))
                .extracting(GeneratedJavaFile::relativePath).noneMatch(p -> p.endsWith(".sql"));
        // Only queries: nothing to migrate.
        assertThat(SqlEntityGenerator.baselineMigration("SELECT 1; -- nothing else\n")).isNull();
    }

    @Test
    void generateTestsWritesASliceTestPerController() {
        SqlDepOptions inline = new SqlDepOptions("entity", List.of(), SqlApiMode.INLINE_DTO, null, true);
        List<GeneratedJavaFile> files = generator.generate(VALIDATED_SQL, SqlDialect.POSTGRESQL, "com.menora.demo", inline,
                SqlProjectFacts.NONE);
        assertThat(files).extracting(GeneratedJavaFile::relativePath)
                .contains("src/test/java/{{packagePath}}/controller/PlayersControllerTest.java",
                        "src/test/java/{{packagePath}}/controller/TeamsControllerTest.java");
        assertThat(findFile(files, "controller/PlayersControllerTest.java").content())
                .contains("package com.menora.demo.controller;")
                .contains("@WebMvcTest(PlayersController.class)")
                .contains("import org.springframework.boot.test.mock.mockito.MockBean;")
                .contains("    @MockBean\n    private PlayersService service;")
                .contains("mockMvc.perform(get(\"/api/players\")).andExpect(status().isOk());")
                .doesNotContain("Mapper");

        // Boot 3.4+: @MockitoBean. With MapStruct the controller takes the mapper too.
        List<GeneratedJavaFile> mapstruct = generator.generate(VALIDATED_SQL, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.MAPSTRUCT_DTO, null, true), new SqlProjectFacts(false, false, true));
        assertThat(findFile(mapstruct, "controller/PlayersControllerTest.java").content())
                .contains("import org.springframework.test.context.bean.override.mockito.MockitoBean;")
                .contains("    @MockitoBean\n    private PlayersService service;")
                .contains("import com.menora.demo.mapper.PlayersMapper;")
                .contains("    @MockitoBean\n    private PlayersMapper mapper;")
                .doesNotContain("MockBean;");

        // No REST stack, no test; and not unless asked.
        assertThat(generator.generate(VALIDATED_SQL, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.NONE, null, true), SqlProjectFacts.NONE))
                .extracting(GeneratedJavaFile::relativePath).noneMatch(p -> p.contains("/test/"));
        assertThat(generator.generate(VALIDATED_SQL, SqlDialect.POSTGRESQL, "com.menora.demo",
                new SqlDepOptions("entity", List.of(), SqlApiMode.INLINE_DTO), SqlProjectFacts.NONE))
                .extracting(GeneratedJavaFile::relativePath).noneMatch(p -> p.contains("/test/"));
    }

    @Test
    void anUnknownApiModeIsRefused() {
        // It used to fall back to NONE: a misspelt mode generated entities only, saying nothing.
        assertThat(SqlApiMode.parse(null)).isEqualTo(SqlApiMode.NONE);
        assertThat(SqlApiMode.parse(" inline_dto ")).isEqualTo(SqlApiMode.INLINE_DTO);
        assertThatThrownBy(() -> SqlApiMode.parse("DTO"))
                .isInstanceOf(com.menora.initializr.config.WizardArgumentException.class)
                .hasMessageContaining("Unknown SQL wizard apiMode: 'DTO'");
    }

    // ── Views and indexes (Round 14, phase 5) ────────────────────────────────

    private static final String SHOP_SQL = """
            CREATE TABLE customers (
                id BIGINT PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                joined DATE,
                credit NUMERIC(10,2)
            );
            CREATE TABLE orders (
                id BIGINT PRIMARY KEY,
                customer_id BIGINT NOT NULL REFERENCES customers(id),
                placed_at TIMESTAMP,
                total NUMERIC(12,2),
                ref UUID
            );
            """;

    private List<GeneratedJavaFile> shop(String more) {
        return generator.generate(SHOP_SQL + more, SqlDialect.POSTGRESQL, "com.menora.demo", null);
    }

    @Test
    void createView_isAReadOnlyEntityOverTheViewTypedByWhatItSelects() {
        // CREATE VIEW used to be dropped (or, where JSqlParser could not read it, a parse error).
        // The entity reads the view by its name; a field takes the type of the table column it
        // selects, of a CAST, of count / min / max — and stays a String with the TODO otherwise.
        String view = findFile(shop("""
                CREATE OR REPLACE VIEW sales.customer_orders AS
                SELECT c.id AS customer_id, c.name, c.joined, count(o.id) AS orders, max(o.total) AS biggest,
                       CAST(sum(o.total) AS NUMERIC(14,2)) AS spent, upper(c.name) AS shout, o.ref
                FROM customers c LEFT JOIN orders o ON o.customer_id = c.id
                GROUP BY c.id, c.name, c.joined, o.ref
                WITH CHECK OPTION;
                """), "entity/CustomerOrders.java").content();
        assertThat(view)
                .contains("@Immutable")
                .contains("@Subselect(\"\"\"\nSELECT * FROM sales.customer_orders\n\"\"\")")
                .contains("import java.math.BigDecimal;\nimport java.time.LocalDate;\nimport java.util.UUID;\nimport jakarta.persistence.Column;")
                .contains("    @Id\n    @Column(name = \"customer_id\")\n    private Long customerId;")
                .contains("    @Column(name = \"name\")\n    private String name;")
                .contains("    private LocalDate joined;")
                .contains("    private Long orders;")
                .contains("    private BigDecimal biggest;")
                .contains("    private BigDecimal spent;")
                .contains("    private UUID ref;")
                .contains("    @Column(name = \"shout\")\n    // TODO: SELECT has no column types — set the real type\n    private String shout;");
        // One TODO: the expression nothing types.
        assertThat(view.split("TODO: SELECT has no column types", -1)).hasSize(2);
    }

    @Test
    void createView_namesItsColumnsByItsListAndStandsInForAStar() {
        List<GeneratedJavaFile> files = shop("""
                CREATE VIEW customer_codes (code, label) AS SELECT id, name FROM customers;
                CREATE VIEW all_customers AS SELECT * FROM customers;
                CREATE MATERIALIZED VIEW IF NOT EXISTS order_copies AS SELECT o.* FROM orders o WITH NO DATA;
                -- a view over a view of the same script
                CREATE VIEW codes_again AS SELECT code FROM customer_codes;
                """);
        assertThat(findFile(files, "entity/CustomerCodes.java").content())
                .contains("SELECT * FROM customer_codes")
                .contains("    @Id\n    @Column(name = \"code\")\n    private Long code;")
                .contains("    @Column(name = \"label\")\n    private String label;");
        assertThat(findFile(files, "entity/AllCustomers.java").content())
                .contains("private Long id;").contains("private String name;")
                .contains("private LocalDate joined;").contains("private BigDecimal credit;")
                .doesNotContain("TODO");
        assertThat(findFile(files, "entity/OrderCopies.java").content())
                .contains("SELECT * FROM order_copies")
                .contains("private Long customerId;").contains("private LocalDateTime placedAt;").contains("private UUID ref;");
        assertThat(findFile(files, "entity/CodesAgain.java").content()).contains("private Long code;");
    }

    @Test
    void createView_mayComeBeforeTheTablesItReads() {
        // A dump orders its statements its own way: the types are read once the script is.
        List<GeneratedJavaFile> files = generator.generate("""
                CREATE VIEW recent AS SELECT o.id, o.placed_at FROM orders o;
                """ + SHOP_SQL, SqlDialect.POSTGRESQL, "com.menora.demo", null);
        assertThat(findFile(files, "entity/Recent.java").content())
                .contains("private Long id;").contains("private LocalDateTime placedAt;");
    }

    @Test
    void createView_withColumnsNobodyCanNameIsRefusedSayingWhatToDo() {
        assertThatThrownBy(() -> shop("CREATE VIEW stock AS SELECT * FROM warehouse_stock;"))
                .isInstanceOf(SqlEntityGenerator.SqlParseException.class)
                .hasMessageContaining("View stock selects * from a table this script does not create")
                .hasMessageContaining("CREATE VIEW stock (a, b) AS");
        assertThatThrownBy(() -> shop("CREATE VIEW totals AS SELECT count(*) FROM orders;"))
                .isInstanceOf(SqlEntityGenerator.SqlParseException.class)
                .hasMessageContaining("View totals selects an expression without a name");
        // With a column list the same views are fine: the list names the columns.
        assertThat(findFile(shop("CREATE VIEW stock (sku, on_hand) AS SELECT * FROM warehouse_stock;"), "entity/Stock.java").content())
                .contains("private String sku;").contains("private String onHand;");
    }

    @Test
    void createView_staysInTheFlywayBaseline() {
        // The view is part of the schema the project was generated from; a bare SELECT is not.
        List<GeneratedJavaFile> files = generator.generate(SHOP_SQL + """
                CREATE VIEW big_orders AS SELECT id, total FROM orders WHERE total > 1000;
                SELECT id AS id, name AS name FROM customers;
                """, SqlDialect.POSTGRESQL, "com.menora.demo", null, new SqlProjectFacts(false, true, false));
        assertThat(findFile(files, "db/migration/V1__init.sql").content())
                .contains("CREATE VIEW big_orders AS SELECT id, total FROM orders WHERE total > 1000;")
                .doesNotContain("SELECT id AS id, name AS name");
    }

    @Test
    void aBareSelectIsTypedTheSameWay() {
        // Every field of a SELECT used to be a String with a TODO.
        String view = findFile(shop("""
                SELECT c.id AS id, c.joined AS since, count(*) AS n, CAST(c.credit AS INTEGER) AS whole, c.name || '!' AS loud
                FROM customers c GROUP BY c.id, c.joined, c.credit, c.name;
                """), "entity/Customers2.java").content();
        assertThat(view)
                .contains("    @Id\n    @Column(name = \"id\")\n    private Long id;")
                .contains("    private LocalDate since;")
                .contains("    private Long n;")
                .contains("    private Integer whole;")
                .contains("    // TODO: SELECT has no column types — set the real type\n    private String loud;");
        // A SELECT over tables the script does not have: nothing to type by, as before.
        assertThat(findFile(generator.generate("SELECT u.id AS id, u.email AS email FROM users u", SqlDialect.H2, "p", null),
                "entity/Users.java").content())
                .contains("    // TODO: SELECT has no column types — set the real type\n    private String id;")
                .contains("    // TODO: SELECT has no column types — set the real type\n    private String email;")
                .doesNotContain("import java.");
    }

    @Test
    void aSelectJSqlParserCannotReadIsStillTypedByItsCasts() {
        // Native SQL (Oracle's MODEL clause): the labels come from the text, and so do the casts.
        String view = findFile(generator.generate(
                "SELECT CAST(id AS NUMBER(12)) AS id, name AS name, CAST(d AS DATE) hired "
                        + "FROM t MODEL DIMENSION BY (id) MEASURES (name) RULES ()",
                SqlDialect.ORACLE, "p", null), "entity/T.java").content();
        assertThat(view)
                .contains("private Long id;")
                .contains("    // TODO: SELECT has no column types — set the real type\n    private String name;")
                .contains("private LocalDate hired;");
    }

    @Test
    void createIndex_isTheTablesIndexAndWhatJpaCannotSayIsANote() {
        // CREATE INDEX was skipped whole. A plain one is @Table(indexes = …); a partial one, one
        // over an expression and one of another kind are said in a comment instead of dropped.
        List<GeneratedJavaFile> files = shop("""
                CREATE INDEX idx_orders_customer ON orders (customer_id);
                CREATE INDEX IF NOT EXISTS idx_orders_recent ON public.orders USING btree (placed_at DESC NULLS LAST, id);
                CREATE INDEX ON customers (name);
                CREATE UNIQUE INDEX uq_orders_ref ON orders (ref);
                CREATE INDEX idx_customers_lower ON customers (lower(name));
                CREATE INDEX idx_orders_open ON orders (customer_id) WHERE total IS NULL;
                CREATE INDEX idx_orders_ref_hash ON orders USING hash (ref);
                CREATE UNIQUE INDEX uq_customers_lower ON customers (lower(name), joined);
                CREATE INDEX idx_elsewhere ON warehouses (id);
                """);
        String orders = findFile(files, "entity/Orders.java").content();
        assertThat(orders)
                .contains("import jakarta.persistence.Index;")
                .contains("@Table(name = \"orders\", indexes = {@Index(name = \"idx_orders_customer\", columnList = \"customer_id\"), "
                        + "@Index(name = \"idx_orders_recent\", columnList = \"placed_at DESC, id\")})")
                .contains("// Index idx_orders_open (customer_id) is not mapped: it is partial (it covers only the rows its WHERE picks).\n"
                        + "// Index idx_orders_ref_hash (ref) is not mapped: it is a hash index.\n@Entity")
                // A unique index over plain columns is still the column's own UNIQUE.
                .contains("@Column(unique = true)\n    private UUID ref;");
        String customers = findFile(files, "entity/Customers.java").content();
        assertThat(customers)
                .contains("@Table(name = \"customers\", indexes = {@Index(columnList = \"name\")})")
                .contains("// Index idx_customers_lower (lower(name)) is not mapped: it is over an expression, not plain columns.\n"
                        + "// Unique index uq_customers_lower (lower(name), joined) is not mapped: it is over an expression, not plain columns.\n@Entity");
        // A table without an index keeps its annotation, and none of the script's statements is lost on a table it does not have.
        assertThat(findFile(shop(""), "entity/Orders.java").content())
                .contains("@Table(name = \"orders\")").doesNotContain("Index");
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
