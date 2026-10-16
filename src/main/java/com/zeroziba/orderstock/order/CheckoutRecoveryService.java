package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.common.ApiException;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutRecoveryService {
    public enum Kind { cart,direct }
    public record Register(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String key,
                           @NotNull Kind kind,@PositiveOrZero Long cartRevision) { }
    public record Item(long id,String name,long price,int quantity) { }
    public record Intent(String quoteId,String key,Kind kind,Long cartRevision,Instant expiresAt,Long orderId,List<Item> items) { }
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public CheckoutRecoveryService(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    @Transactional
    public Intent register(long user,String quoteId,Register body) {
        if(body==null||body.key()==null||!body.key().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")||body.kind()==null
            ||(body.cartRevision()!=null&&body.cartRevision()<0)||(body.kind()==Kind.cart&&body.cartRevision()==null))throw invalid();
        var intent=read(user,quoteId,true);
        // Once registered, every tab receives the original request key and cart version.
        if(intent.key()!=null)return intent;
        if(intent.orderId()!=null||!intent.expiresAt().isAfter(clock.instant()))throw new ApiException(HttpStatus.CONFLICT,"QUOTE_EXPIRED","확인 시간이 지났어요. 최신 가격을 다시 확인하세요.");
        jdbc.update("UPDATE checkout_quotes SET request_key=?,checkout_kind=?,cart_revision=? WHERE id=? AND user_id=?",
            body.key(),body.kind().name(),body.kind()==Kind.cart?body.cartRevision():null,quoteId,user);
        return read(user,quoteId,false);
    }
    @Transactional(readOnly=true)
    public List<Intent> list(long user) {
        var ids=jdbc.queryForList("SELECT id FROM checkout_quotes WHERE user_id=? AND request_key IS NOT NULL AND acknowledged=FALSE AND expires_at>? ORDER BY expires_at DESC,id DESC LIMIT 100",
            String.class,user,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).minusDays(1));
        return ids.stream().map(id->read(user,id,false)).toList();
    }
    @Transactional
    public void acknowledge(long user,String quoteId) {
        var intent=read(user,quoteId,true);
        // The quote lock serializes closing with checkout. Checkout refuses a closed, unused quote.
        jdbc.update("UPDATE checkout_quotes SET acknowledged=TRUE WHERE id=? AND user_id=?",quoteId,user);
    }
    private Intent read(long user,String id,boolean lock) {
        if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();
        var rows=jdbc.query("SELECT * FROM checkout_quotes WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),
            (row,index)->new Intent(id,row.getString("request_key"),row.getString("checkout_kind")==null?null:Kind.valueOf(row.getString("checkout_kind")),row.getObject("cart_revision",Long.class),row.getObject("expires_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),row.getObject("order_id",Long.class),List.of()),id,user);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"QUOTE_NOT_FOUND","주문 확인 정보를 찾을 수 없어요.");
        var intent=rows.get(0);
        var items=jdbc.query("SELECT i.product_id,COALESCE(p.name,'판매 종료 상품'),i.unit_price,i.quantity FROM checkout_quote_items i LEFT JOIN products p ON p.id=i.product_id WHERE i.quote_id=? ORDER BY i.product_id",
            (row,index)->new Item(row.getLong(1),row.getString(2),row.getLong(3),row.getInt(4)),id);
        return new Intent(id,intent.key(),intent.kind(),intent.cartRevision(),intent.expiresAt(),intent.orderId(),items);
    }
    private ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","주문 복구 정보와 요청 키를 확인하세요.");}
}
