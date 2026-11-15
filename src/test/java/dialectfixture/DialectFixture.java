package dialectfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One column per shape in {@code Entity.java.mustache} and the generator's own entities, for
 * {@code SchemaDialectTest} to have Hibernate export on each dialect.
 *
 * <p>In a package of its own, outside {@code com.menora.initializr}: the application scans that
 * package for entities, and its {@code ddl-auto: validate} would look for this one's table in
 * the catalog database of every Spring test.
 */
@Entity
@Table(name = "fixture")
public class DialectFixture {

    public enum Kind { A, B }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @jakarta.persistence.Column(name = "id")
    Long id;

    @jakarta.persistence.Column(name = "c_varchar", nullable = false, length = 80)
    String varchar;

    @jakarta.persistence.Column(name = "c_string")
    String string;

    /** What Hibernate picks for a string too long for VARCHAR: the model for TEXT fields. */
    @jakarta.persistence.Column(name = "c_text", length = Length.LONG32)
    String text;

    @jakarta.persistence.Column(name = "c_bigint")
    Long bigint;

    @jakarta.persistence.Column(name = "c_integer")
    Integer integer;

    @jakarta.persistence.Column(name = "c_boolean")
    Boolean bool;

    @jakarta.persistence.Column(name = "c_date")
    LocalDate date;

    @jakarta.persistence.Column(name = "c_timestamp")
    LocalDateTime timestamp;

    @jakarta.persistence.Column(name = "c_time")
    LocalTime time;

    @jakarta.persistence.Column(name = "c_instant")
    Instant instant;

    @jakarta.persistence.Column(name = "c_decimal")
    BigDecimal decimal;

    @jakarta.persistence.Column(name = "c_money", precision = 19, scale = 2)
    BigDecimal money;

    @jakarta.persistence.Column(name = "c_uuid")
    UUID uuid;

    @Enumerated(EnumType.STRING)
    @jakarta.persistence.Column(name = "c_enum")
    Kind kind;

    @JdbcTypeCode(SqlTypes.LONG32VARBINARY)
    @jakarta.persistence.Column(name = "c_blob", nullable = false, length = Length.LONG32)
    byte[] blob;

    @jakarta.persistence.Column(name = "deleted", nullable = false)
    boolean deleted;

    @Version
    @jakarta.persistence.Column(name = "version")
    Long version;
}
