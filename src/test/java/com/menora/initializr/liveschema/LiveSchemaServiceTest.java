package com.menora.initializr.liveschema;

import com.menora.initializr.db.repository.SchemaSourceRepository;
import com.menora.initializr.liveschema.LiveSchemaService.ConnectionBody;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The limits on connections typed in by hand. */
class LiveSchemaServiceTest {

    private static LiveSchemaService service(boolean typed, String allowedHosts) {
        SchemaSourceRepository repo = Mockito.mock(SchemaSourceRepository.class);
        Mockito.when(repo.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of());
        return new LiveSchemaService(repo, new MockEnvironment(), typed, allowedHosts, 300, 4, false);
    }

    private static ConnectionBody pg(String host) {
        return new ConnectionBody(null, "POSTGRESQL", host, null, "hr", null, "reader", "pw", null);
    }

    @Test
    void typedConnectionsCanBeTurnedOff() {
        assertThatThrownBy(() -> service(false, "").resolve(pg("db1")))
                .isInstanceOfSatisfying(LiveSchemaException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).contains("Ask an admin");
                });
        assertThat(service(false, "").options().typedConnections()).isFalse();
    }

    @Test
    void onlyAllowedHostsWhenThereIsAList() {
        LiveSchemaService s = service(true, "*.menora.co.il, 10.20.*");
        assertThat(s.resolve(pg("pg01.menora.co.il")).host()).isEqualTo("pg01.menora.co.il");
        assertThat(s.resolve(pg("10.20.1.5")).label()).isEqualTo("10.20.1.5/hr");
        assertThatThrownBy(() -> s.resolve(pg("evil.example.com")))
                .isInstanceOfSatisfying(LiveSchemaException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> s.resolve(pg("menora.co.il.evil.com"))).isInstanceOf(LiveSchemaException.class);
    }

    @Test
    void h2OnlyWhenATestAllowsIt() {
        assertThatThrownBy(() -> service(true, "").resolve(
                new ConnectionBody(null, "H2", "localhost", null, "x", null, "sa", "", null)))
                .hasMessageContaining("Database type must be one of");
        assertThat(service(true, "").options().drivers()).doesNotContainKey("H2");
    }

    @Test
    void partsThatWouldReachTheUrlAreChecked() {
        LiveSchemaService s = service(true, "");
        assertThatThrownBy(() -> s.resolve(pg("db1:5432/x?socketFactory=y"))).hasMessageContaining("Host must be");
        assertThatThrownBy(() -> s.resolve(new ConnectionBody(null, "POSTGRESQL", "db1", 70000, "hr", null, "u", "p", null)))
                .hasMessageContaining("Port must be");
        assertThatThrownBy(() -> s.resolve(new ConnectionBody(null, "ORACLE", "db1", null, "HR?x=1", null, "u", "p", null)))
                .hasMessageContaining("Service name");
        assertThatThrownBy(() -> s.resolve(new ConnectionBody(null, "DB2", "as400", null, null, "LIB;x=1", "u", "p", null)))
                .hasMessageContaining("Library");
        assertThatThrownBy(() -> s.resolve(new ConnectionBody(null, "POSTGRESQL", "db1", null, "hr", null, " ", "p", null)))
                .hasMessageContaining("User is required");
        // DB2 for IBM i needs no database.
        assertThat(s.resolve(new ConnectionBody(null, "DB2", "as400", null, null, "HRLIB", "u", "p", null)).library())
                .isEqualTo("HRLIB");
    }

    @Test
    void passwordReferencesAreLimited() {
        assertThat(LiveSchemaService.PASSWORD_REF.matcher("SCHEMA_SOURCE_HR").matches()).isTrue();
        assertThat(LiveSchemaService.PASSWORD_REF.matcher("menora.live-schema.secrets.hr").matches()).isTrue();
        assertThat(LiveSchemaService.PASSWORD_REF.matcher("admin.password").matches()).isFalse();
        assertThat(LiveSchemaService.PASSWORD_REF.matcher("SPRING_DATASOURCE_PASSWORD").matches()).isFalse();
    }
}
