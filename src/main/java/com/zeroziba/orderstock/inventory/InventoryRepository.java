package com.zeroziba.orderstock.inventory;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {
    public record Reservation(long productId, int delta, String reason, Long adjustmentId) { }
    private static final RowMapper<InventoryAdjustment> MAPPER = (row, index) -> new InventoryAdjustment(
            row.getLong("id"), row.getLong("admin_id"), row.getLong("product_id"), row.getString("product_name"),
            row.getInt("quantity_delta"), row.getInt("before_stock"), row.getInt("after_stock"), row.getString("reason"),
            row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Reservation reserve(long adminId, String key, long productId, int delta, String reason) {
        jdbc.update("""
                INSERT INTO inventory_adjustment_requests (admin_id, request_key, product_id, quantity_delta, reason)
                VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE request_key = request_key
                """, adminId, key, productId, delta, reason);
        return jdbc.queryForObject("""
                SELECT product_id, quantity_delta, reason, adjustment_id FROM inventory_adjustment_requests
                WHERE admin_id = ? AND request_key = ? FOR UPDATE
                """, (row, index) -> new Reservation(row.getLong("product_id"), row.getInt("quantity_delta"),
                        row.getString("reason"), row.getObject("adjustment_id", Long.class)), adminId, key);
    }

    public InventoryAdjustment insert(long adminId, long productId, String name, int delta, int before, int after,
            String reason, LocalDateTime now) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO inventory_adjustments
                    (admin_id, product_id, product_name, quantity_delta, before_stock, after_stock, reason, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, adminId);
            statement.setLong(2, productId);
            statement.setString(3, name);
            statement.setInt(4, delta);
            statement.setInt(5, before);
            statement.setInt(6, after);
            statement.setString(7, reason);
            statement.setObject(8, now);
            return statement;
        }, holder);
        return find(holder.getKey().longValue());
    }

    public void complete(long adminId, String key, long adjustmentId) {
        if (jdbc.update("""
                UPDATE inventory_adjustment_requests SET adjustment_id = ?
                WHERE admin_id = ? AND request_key = ? AND adjustment_id IS NULL
                """, adjustmentId, adminId, key) != 1) {
            throw new IllegalStateException("Locked inventory request could not be completed");
        }
    }

    public InventoryAdjustment find(long id) {
        // The earlier role lookup may have opened a REPEATABLE READ snapshot before
        // another request committed. Read the row referenced by the locked reservation
        // with a current read, rather than that older snapshot.
        return jdbc.queryForObject("SELECT * FROM inventory_adjustments WHERE id = ? FOR SHARE", MAPPER, id);
    }

    public List<InventoryAdjustment> recent(Long productId, Long beforeId) {
        return recent(productId,beforeId,new com.zeroziba.orderstock.common.DateRange(null,null));
    }
    public List<InventoryAdjustment> recent(Long productId,Long beforeId,com.zeroziba.orderstock.common.DateRange range) {
        return jdbc.query("SELECT * FROM inventory_adjustments WHERE (? IS NULL OR product_id=?) AND id<? AND (? IS NULL OR created_at>=?) AND (? IS NULL OR created_at<?) ORDER BY id DESC LIMIT 21",
                MAPPER,productId,productId,beforeId==null?Long.MAX_VALUE:beforeId,range.from(),range.from(),range.until(),range.until());
    }
}
