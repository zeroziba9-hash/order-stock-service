package com.zeroziba.orderstock.inventory;

import com.zeroziba.orderstock.auth.AdminAccess;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryLedger {
    public enum Type { BASELINE, ORDER, CANCEL, REFUND, EXPIRE, MANUAL }
    public record Movement(long movementId, long productId, String productName, String type, String reference,
            Long actorId, int delta, int beforeStock, int afterStock, String reason, java.time.Instant createdAt) { }
    public record History(List<Movement> movements, Long nextBeforeId, int currentStock, long recordedStock, boolean consistent) { }
    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final AdminAccess admins;
    private final Clock clock;
    public InventoryLedger(JdbcTemplate jdbc, ProductRepository products, AdminAccess admins, Clock clock) {
        this.jdbc=jdbc; this.products=products; this.admins=admins; this.clock=clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Product product, int before, Type type, String reference, Long actor, String reason) {
        // Baselines belong to catalog creation/V11 cutover. Re-upserting an existing
        // baseline can take primary-index next-key locks and deadlock distributed inserts.
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (type == Type.BASELINE) {
            jdbc.update("""
                INSERT INTO inventory_movements
                (product_id,product_name,movement_type,event_reference,quantity_delta,before_stock,after_stock,reason,created_at)
                VALUES (?,?,'BASELINE','opening',?,0,?,'기록 시작 재고',?)
                """, product.getId(), product.getName(), before, before, now);
            return;
        }
        jdbc.update("""
                INSERT INTO inventory_movements
                (product_id,product_name,movement_type,event_reference,actor_id,quantity_delta,before_stock,after_stock,reason,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, product.getId(), product.getName(), type.name(), reference, actor,
                product.getStock()-before, before, product.getStock(), reason, now);
    }

    @Transactional(readOnly = true)
    public History history(long adminId, long productId, Long beforeId) {
        admins.require(adminId);
        if (productId < 1 || (beforeId != null && beforeId < 1))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","상품과 커서 ID를 확인하세요.");
        var product=products.findById(productId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","상품을 찾을 수 없습니다."));
        var rows=jdbc.query("""
                SELECT * FROM inventory_movements WHERE product_id=? AND id<? ORDER BY id DESC LIMIT 21
                """, (row,index) -> new Movement(row.getLong("id"),row.getLong("product_id"),row.getString("product_name"),
                        row.getString("movement_type"),row.getString("event_reference"),row.getObject("actor_id",Long.class),
                        row.getInt("quantity_delta"),row.getInt("before_stock"),row.getInt("after_stock"),row.getString("reason"),
                        row.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),productId,beforeId==null?Long.MAX_VALUE:beforeId);
        // Compare against the complete ledger, not just this cursor page.
        long recorded=jdbc.queryForObject("SELECT COALESCE(SUM(quantity_delta),0) FROM inventory_movements WHERE product_id=?",Long.class,productId);
        return new History(rows.stream().limit(20).toList(),rows.size()>20?rows.get(19).movementId():null,
                product.getStock(),recorded,product.getStock()==recorded);
    }
}
