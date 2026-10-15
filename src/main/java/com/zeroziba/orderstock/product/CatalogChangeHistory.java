package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.auth.AdminAccess;
import com.zeroziba.orderstock.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogChangeHistory {
    public record Snapshot(String name, long price, boolean selling, long revision) {
        static Snapshot from(Product product) {
            return new Snapshot(product.getName(), product.getPrice(), product.isSelling(), product.getCatalogRevision());
        }
    }
    public record Change(long changeId, long productId, long actorId, Snapshot before, Snapshot after, Instant createdAt) { }
    public record Page(List<Change> changes, Long nextBeforeId) { }
    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final AdminAccess admins;
    private final Clock clock;

    public CatalogChangeHistory(JdbcTemplate jdbc, ProductRepository products, AdminAccess admins, Clock clock) {
        this.jdbc = jdbc; this.products = products; this.admins = admins; this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long admin, Product product, Snapshot before) {
        jdbc.update("""
                INSERT INTO catalog_changes
                (product_id,actor_id,before_name,after_name,before_price,after_price,before_selling,after_selling,
                 before_revision,after_revision,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, product.getId(), admin, before.name(), product.getName(), before.price(), product.getPrice(),
                before.selling(), product.isSelling(), before.revision(), product.getCatalogRevision(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public Page list(long admin, long productId, Long beforeId) {
        return list(admin,productId,beforeId,null,null);
    }
    @Transactional(readOnly=true)
    public Page list(long admin,long productId,Long beforeId,java.time.LocalDate from,java.time.LocalDate to) {
        admins.require(admin);
        if (productId < 1 || (beforeId != null && beforeId < 1))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "상품과 커서 ID를 확인하세요.");
        if (!products.existsById(productId))
            throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.");
        var range=com.zeroziba.orderstock.common.DateRange.of(from,to);
        var rows = jdbc.query("""
                SELECT * FROM catalog_changes WHERE product_id=? AND id<?
                AND (? IS NULL OR created_at>=?) AND (? IS NULL OR created_at<?) ORDER BY id DESC LIMIT 21
                """, (row, index) -> new Change(row.getLong("id"), row.getLong("product_id"), row.getLong("actor_id"),
                        new Snapshot(row.getString("before_name"), row.getLong("before_price"),
                                row.getBoolean("before_selling"), row.getLong("before_revision")),
                        new Snapshot(row.getString("after_name"), row.getLong("after_price"),
                                row.getBoolean("after_selling"), row.getLong("after_revision")),
                        row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                productId, beforeId == null ? Long.MAX_VALUE : beforeId,range.from(),range.from(),range.until(),range.until());
        return new Page(rows.stream().limit(20).toList(), rows.size() > 20 ? rows.get(19).changeId() : null);
    }
}
