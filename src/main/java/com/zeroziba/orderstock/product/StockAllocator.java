package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.common.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Component
public class StockAllocator {
    public record Allocation(Product product, int beforeStock) { }
    private final ProductRepository products;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final boolean conditional;
    public StockAllocator(ProductRepository products,JdbcTemplate jdbc,EntityManager entities,Environment env) {
        this.products=products; this.jdbc=jdbc; this.entities=entities;
        String mode=env.getProperty("app.inventory.allocation","locking");
        if (!mode.equals("locking") && !mode.equals("conditional")) throw new IllegalStateException("Unknown inventory allocation mode");
        conditional=mode.equals("conditional");
        var profiles=Arrays.asList(env.getActiveProfiles());
        if (conditional && (profiles.contains("prod") || !(profiles.contains("test") || profiles.contains("benchmark"))))
            throw new IllegalStateException("Conditional allocation is limited to isolated benchmark/test profiles");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Allocation allocate(long id,int quantity) {
        if (conditional) {
            products.flush();
            int changed=jdbc.update("UPDATE products SET stock=stock-? WHERE id=? AND selling=TRUE AND stock>=?",quantity,id,quantity);
            var product=products.findByIdForUpdate(id).orElseThrow(this::missing);
            entities.refresh(product,LockModeType.PESSIMISTIC_WRITE);
            requireSelling(product);
            if (changed==0) throw new ApiException(HttpStatus.CONFLICT,"INSUFFICIENT_STOCK","상품의 재고가 부족합니다.");
            return new Allocation(product,Math.addExact(product.getStock(),quantity));
        }
        var product=products.findByIdForUpdate(id).orElseThrow(this::missing);
        requireSelling(product);
        int before=product.getStock(); product.decreaseStock(quantity);
        return new Allocation(product,before);
    }
    private void requireSelling(Product product) {
        if (!product.isSelling()) throw new ApiException(HttpStatus.CONFLICT,"PRODUCT_NOT_SELLING","판매 중지된 상품입니다.");
    }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","상품을 찾을 수 없습니다."); }
}
