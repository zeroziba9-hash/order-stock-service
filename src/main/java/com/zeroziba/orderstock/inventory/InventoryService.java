package com.zeroziba.orderstock.inventory;

import com.zeroziba.orderstock.auth.UserRepository;
import com.zeroziba.orderstock.auth.UserRole;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.product.ProductRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
    public record Result(InventoryAdjustment adjustment, boolean replayed) { }
    public record History(List<InventoryAdjustment> adjustments, Long nextBeforeId) { }
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private final UserRepository users;
    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final Clock clock;
    private final InventoryLedger ledger;

    public InventoryService(UserRepository users, ProductRepository products, InventoryRepository inventory, Clock clock, InventoryLedger ledger) {
        this.users = users;
        this.products = products;
        this.inventory = inventory;
        this.clock = clock;
        this.ledger=ledger;
    }

    @Transactional
    public Result adjust(long adminId, long productId, String key, AdjustInventoryRequest request) {
        requireAdmin(adminId);
        if (productId < 1 || request.delta() == null || request.delta() == 0 || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "상품·조정 수량·변경 사유를 확인하세요.");
        }
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "유효한 Idempotency-Key가 필요합니다.");
        }
        String reason = request.reason().strip();
        var reservation = inventory.reserve(adminId, key, productId, request.delta(), reason);
        if (reservation.productId() != productId || reservation.delta() != request.delta() || !reservation.reason().equals(reason)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 재고 조정에 사용한 요청 키입니다.");
        }
        if (reservation.adjustmentId() != null) {
            return new Result(inventory.find(reservation.adjustmentId()), true);
        }
        var product = products.findByIdForUpdate(productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."));
        int before = product.getStock();
        product.adjustStock(request.delta());
        products.flush();
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var adjustment = inventory.insert(adminId, productId, product.getName(), request.delta(), before, product.getStock(), reason, now);
        ledger.record(product,before,InventoryLedger.Type.MANUAL,Long.toString(adjustment.adjustmentId()),adminId,reason);
        inventory.complete(adminId, key, adjustment.adjustmentId());
        return new Result(adjustment, false);
    }

    @Transactional(readOnly = true)
    public History history(long adminId, Long productId, Long beforeId) {
        requireAdmin(adminId);
        if ((productId != null && productId < 1) || (beforeId != null && beforeId < 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "상품 ID와 이전 이력 ID는 1 이상이어야 합니다.");
        }
        var rows = inventory.recent(productId, beforeId);
        var page = rows.stream().limit(20).toList();
        return new History(page, rows.size() > 20 ? page.get(19).adjustmentId() : null);
    }

    private void requireAdmin(long adminId) {
        // A revoked admin's old session must not retain inventory permissions.
        if (users.findById(adminId).map(user -> user.getRole() != UserRole.ADMIN).orElse(true)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "현재 관리자 권한이 필요합니다.");
        }
    }
}
