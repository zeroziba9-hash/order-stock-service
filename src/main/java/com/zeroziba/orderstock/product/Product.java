package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    // 원 단위 정수 금액. 주문 금액 계산에는 long을 사용한다.
    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private boolean selling = true;

    @Column(name = "catalog_revision", nullable = false)
    private long catalogRevision;

    protected Product() {
        // JPA가 DB 행을 엔티티로 읽을 때 사용하는 생성자.
    }

    public Product(String name, long price, int stock) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new IllegalArgumentException("상품명은 1~100자여야 합니다.");
        }
        if (price < 0) {
            throw new IllegalArgumentException("가격은 0원 이상이어야 합니다.");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("재고는 0개 이상이어야 합니다.");
        }
        this.name = name.strip();
        this.price = price;
        this.stock = stock;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public boolean isSelling() { return selling; }
    public long getCatalogRevision() { return catalogRevision; }

    public void editCatalog(String name, long price, boolean selling, long expectedRevision) {
        if (catalogRevision != expectedRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "CATALOG_CHANGED", "다른 변경이 반영됐어요. 상품을 다시 불러오세요.");
        }
        if (name == null || name.isBlank() || name.strip().length() > 100 || price < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "상품명과 가격을 확인하세요.");
        }
        this.name = name.strip();
        this.price = price;
        this.selling = selling;
        this.catalogRevision = Math.incrementExact(catalogRevision);
    }

    public void decreaseStock(int quantity) {
        if (quantity < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "주문 수량은 1 이상이어야 합니다.");
        }
        if (stock < quantity) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "상품의 재고가 부족합니다.");
        }
        stock -= quantity;
    }

    public void adjustStock(int delta) {
        long next = (long) stock + delta;
        if (delta == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "조정 수량은 0일 수 없습니다.");
        }
        if (next < 0 || next > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STOCK_ADJUSTMENT", "조정 후 재고는 0~2147483647개여야 합니다.");
        }
        stock = (int) next;
    }

    public void restoreStock(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("복구할 수량은 1 이상이어야 합니다.");
        }
        try {
            stock = Math.addExact(stock, quantity);
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "STOCK_CAPACITY_EXCEEDED", "재고 저장 범위를 초과하여 취소할 수 없습니다.");
        }
    }
}
