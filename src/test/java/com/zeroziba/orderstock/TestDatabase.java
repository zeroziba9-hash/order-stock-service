package com.zeroziba.orderstock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TestDatabase {
    private TestDatabase() { }
    public static void cleanInventory(JdbcTemplate jdbc) {
        if (!"order_stock_test".equals(jdbc.queryForObject("SELECT DATABASE()",String.class)))
            throw new IllegalStateException("Dedicated test database required");
        jdbc.update("DELETE FROM app_sessions");
        for (var table:List.of("checkout_quotes","saved_carts","catalog_changes","inventory_movements","catalog_creation_requests","inventory_adjustment_requests","inventory_adjustments"))
            jdbc.update("DELETE FROM "+table);
    }
}
