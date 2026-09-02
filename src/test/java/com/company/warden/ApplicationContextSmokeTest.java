package com.company.warden;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * Boots the full application context.
 *
 * This exists because every wiring mistake this project has hit — a missing
 * mcp.jwt.secret, a @Value on a field that Spring never populates, a new service with
 * an unsatisfiable constructor — is invisible to compilation and to the unit tests, and
 * only shows up as a failure to start.
 *
 * The application runs on PostgreSQL + pgvector everywhere, including the 'local'
 * profile. This test deliberately does not: it overrides to in-memory H2 so that
 * `mvn test` needs no running database. Everything Postgres-specific is therefore
 * switched off here rather than in the profile — Liquibase (its migrations are Postgres
 * SQL: CREATE EXTENSION vector, tsvector, DO blocks) and the pgvector store (H2 has no
 * vector type). Hibernate creates the tables instead, and the VectorStore is mocked.
 *
 * The cost of that trade: this test proves the context wires, not that the schema is
 * valid. Migration correctness is proved by running the app against real Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:context-smoke-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.liquibase.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-h2.sql",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
})
class ApplicationContextSmokeTest {

    /** No pgvector on H2. The real store is exercised against Postgres, not here. */
    @MockBean
    private VectorStore vectorStore;

    @Autowired
    private MockMvc mvc;

    @Test
    void contextLoads() {
        // A failure to reach this line is the assertion: the context did not start.
    }

    /**
     * The credential endpoints must not be storable. Spring Security's default header
     * writers already send {@code no-store} on every response, so this is not a feature —
     * it is the pin that fails the build if someone calls {@code http.headers(...)} and
     * turns the defaults off while chasing a caching problem elsewhere.
     */
    @Test
    void theLoginResponseIsNeverCached() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }
}
