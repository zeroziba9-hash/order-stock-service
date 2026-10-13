package com.zeroziba.orderstock.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductIntegrationTests {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void prepareTestDatabase() {
        assertTestDatabase();
        com.zeroziba.orderstock.TestDatabase.cleanInventory(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM refund_attempts");
        jdbcTemplate.update("DELETE FROM payment_attempts");
        jdbcTemplate.update("DELETE FROM order_requests");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM purchase_orders");
        productRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM app_users");
    }

    @AfterEach
    void cleanTestDatabase() {
        assertTestDatabase();
        com.zeroziba.orderstock.TestDatabase.cleanInventory(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM refund_attempts");
        jdbcTemplate.update("DELETE FROM payment_attempts");
        jdbcTemplate.update("DELETE FROM order_requests");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM purchase_orders");
        productRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM app_users");
    }

    private void assertTestDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .as("Tests must use the isolated MySQL test database")
                .isEqualTo("order_stock_test");
    }

    @Test
    void listReturnsCommittedProductsInIdOrder() throws Exception {
        productRepository.saveAndFlush(new Product("테스트 머그컵", 15000L, 10));
        productRepository.saveAndFlush(new Product("테스트 스티커", 5000L, 30));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].name").value("테스트 머그컵"))
                .andExpect(jsonPath("$[0].price").value(15000))
                .andExpect(jsonPath("$[0].stock").value(10))
                .andExpect(jsonPath("$[1].name").value("테스트 스티커"));
    }

    @Test
    void listReflectsDatabaseUpdatesWithoutRestart() throws Exception {
        Product saved = productRepository.saveAndFlush(new Product("기존 이름", 15000L, 10));
        jdbcTemplate.update("UPDATE products SET name = ?, price = ?, stock = ? WHERE id = ?",
                "DB에서 바꾼 이름", 17000L, 7, saved.getId());

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("DB에서 바꾼 이름"))
                .andExpect(jsonPath("$[0].price").value(17000))
                .andExpect(jsonPath("$[0].stock").value(7));
    }

    @Test
    void listReturnsAnEmptyArrayWhenDatabaseHasNoProducts() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void databaseRejectsNegativeStock() {
        UncategorizedSQLException exception = assertThrows(UncategorizedSQLException.class, () -> jdbcTemplate.update(
                "INSERT INTO products (name, price, stock) VALUES (?, ?, ?)", "잘못된 재고", 1000L, -1));
        assertThat(exception.getSQLException().getErrorCode()).isEqualTo(3819);
        assertThat(exception.getSQLException().getMessage()).contains("chk_products_stock");
    }

    @Test
    void databaseRejectsNegativePrice() {
        UncategorizedSQLException exception = assertThrows(UncategorizedSQLException.class, () -> jdbcTemplate.update(
                "INSERT INTO products (name, price, stock) VALUES (?, ?, ?)", "잘못된 가격", -1000L, 1));
        assertThat(exception.getSQLException().getErrorCode()).isEqualTo(3819);
        assertThat(exception.getSQLException().getMessage()).contains("chk_products_price");
    }
}
