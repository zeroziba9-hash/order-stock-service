package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.monitoring.OrderMetrics;
import com.zeroziba.orderstock.product.ProductRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {
    public record Request(@NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String quoteId) { }
    public record Item(long productId, String name, long price, int quantity, int stock, boolean selling, long revision) { }
    public record Quote(String quoteId, List<Item> items, String totalAmount, Instant expiresAt, boolean canOrder) { }
    public record Expected(long productId, int quantity, long price, long revision) { }
    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final OrderRequestRepository requests;
    private final OrderService orders;
    private final OrderMetrics metrics;
    private final Clock clock;

    public CheckoutService(JdbcTemplate jdbc, ProductRepository products, OrderRequestRepository requests,
            OrderService orders, OrderMetrics metrics, Clock clock) {
        this.jdbc=jdbc; this.products=products; this.requests=requests; this.orders=orders; this.metrics=metrics; this.clock=clock;
    }

    @Transactional
    public Quote quote(long userId, CartOrderRequest body) {
        var normalized=body.normalized();
        // Serialize this user's quota check. Quoting never allocates inventory.
        jdbc.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE",Long.class,userId);
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM checkout_quotes WHERE user_id=? AND expires_at>?",Integer.class,userId,now)>=100)
            throw error(HttpStatus.TOO_MANY_REQUESTS,"QUOTE_LIMIT","주문 확인 요청이 많아요. 10분 후 다시 시도하세요.");
        var snapshot=new ArrayList<Item>(); long total=0;
        for(var requested:normalized) {
            var product=products.findById(requested.productId()).orElseThrow(()->error(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","상품을 찾을 수 없어요. 장바구니에서 빼고 다시 확인하세요."));
            snapshot.add(new Item(product.getId(),product.getName(),product.getPrice(),requested.quantity(),product.getStock(),product.isSelling(),product.getCatalogRevision()));
            try { total=Math.addExact(total,Math.multiplyExact(product.getPrice(),(long)requested.quantity())); }
            catch(ArithmeticException overflow) { throw error(HttpStatus.BAD_REQUEST,"INVALID_ORDER_AMOUNT","주문 금액이 허용 범위를 초과합니다."); }
        }
        boolean available=snapshot.stream().allMatch(item->item.selling() && item.stock()>=item.quantity());
        var expires=now.plusMinutes(10); String id=null;
        if(available) {
            id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO checkout_quotes(id,user_id,expires_at) VALUES (?,?,?)",id,userId,expires);
            for(var item:snapshot)jdbc.update("INSERT INTO checkout_quote_items(quote_id,product_id,quantity,unit_price,catalog_revision) VALUES (?,?,?,?,?)",id,item.productId(),item.quantity(),item.price(),item.revision());
        }
        return new Quote(id,List.copyOf(snapshot),Long.toString(total),expires.toInstant(ZoneOffset.UTC),available);
    }

    @Transactional
    public IdempotentOrderService.CreationResult checkout(long userId,String key,Request body) {
        if(key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))throw error(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY","유효한 요청 키가 필요합니다.");
        if(body==null || body.quoteId()==null || !body.quoteId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","주문 확인 정보를 다시 받아 주세요.");
        String hash=CartOrderRequest.hash("quote:"+body.quoteId());
        var stored=requests.reserveQuoteAndLock(userId,key,hash);
        if(!stored.fingerprint().equals(hash))throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED","다른 주문 확인에 사용한 요청 키입니다.");
        // A committed result remains replayable after expiry, catalog changes and quote cleanup.
        if(stored.orderId()!=null) {
            metrics.afterCommit(OrderMetrics.Outcome.REPLAYED);
            return new IdempotentOrderService.CreationResult(orders.findById(userId,stored.orderId()),true);
        }
        var quotes=jdbc.query("SELECT expires_at,order_id FROM checkout_quotes WHERE id=? AND user_id=? FOR UPDATE",
                (row,index)->new Object[]{row.getObject("expires_at",LocalDateTime.class),row.getObject("order_id",Long.class)},body.quoteId(),userId);
        if(quotes.isEmpty())throw error(HttpStatus.NOT_FOUND,"QUOTE_NOT_FOUND","주문 확인 정보를 찾을 수 없어요. 다시 확인하세요.");
        if(quotes.get(0)[1]!=null)throw error(HttpStatus.CONFLICT,"QUOTE_ALREADY_USED","이미 주문한 확인 정보예요. 내 주문에서 확인하세요.");
        var expires=(LocalDateTime)quotes.get(0)[0];
        if(!expires.toInstant(ZoneOffset.UTC).isAfter(clock.instant()))throw error(HttpStatus.CONFLICT,"QUOTE_EXPIRED","확인 시간이 지났어요. 최신 가격을 다시 확인하세요.");
        var expected=jdbc.query("SELECT product_id,quantity,unit_price,catalog_revision FROM checkout_quote_items WHERE quote_id=? ORDER BY product_id",
                (row,index)->new Expected(row.getLong(1),row.getInt(2),row.getLong(3),row.getLong(4)),body.quoteId());
        var order=orders.createConfirmed(userId,expected,expires.toInstant(ZoneOffset.UTC));
        requests.complete(userId,key,order.orderId());
        jdbc.update("UPDATE checkout_quotes SET order_id=? WHERE id=?",order.orderId(),body.quoteId());
        return new IdempotentOrderService.CreationResult(order,false);
    }

    @Scheduled(initialDelayString="${app.checkout.cleanup-interval-ms:3600000}",fixedDelayString="${app.checkout.cleanup-interval-ms:3600000}")
    public void cleanup() {
        // Bounded work; committed order request links retain replay results independently.
        jdbc.update("DELETE FROM checkout_quotes WHERE expires_at<? ORDER BY expires_at LIMIT 1000",
                LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).minusDays(1));
    }
    private static ApiException error(HttpStatus status,String code,String message){return new ApiException(status,code,message);}
}
