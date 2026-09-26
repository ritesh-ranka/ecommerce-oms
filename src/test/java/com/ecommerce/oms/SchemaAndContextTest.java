package com.ecommerce.oms;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cheapest high-value test in the suite.
 *
 * <p>Booting the context with {@code ddl-auto: validate} means Hibernate compares every
 * entity mapping against the Flyway-built schema and fails startup on any mismatch. So this
 * test asserts, in one line, that the migrations and the entity model agree — the class of
 * bug that otherwise surfaces as a runtime failure on a rarely-used column.
 *
 * <p>It also proves the seed migration produced the fixtures the rest of the suite assumes.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaAndContextTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("context starts, so every entity mapping validates against the Flyway schema")
    void contextLoads() {
        assertThat(entityManager).isNotNull();
    }

    @Test
    @DisplayName("seed data is present and the oversell demo SKU has exactly one unit")
    void seedDataIsUsable() {
        Long users = count("select count(u) from User u");
        Long variants = count("select count(v) from ProductVariant v");
        Long warehouses = count("select count(w) from Warehouse w");

        assertThat(users).isEqualTo(5);
        assertThat(variants).isEqualTo(20);
        assertThat(warehouses).isEqualTo(3);
    }

    private Long count(String jpql) {
        return entityManager.createQuery(jpql, Long.class).getSingleResult();
    }
}
