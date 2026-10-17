package com.zeroziba.orderstock.payment.toss;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.inventory.InventoryLedger;
import com.zeroziba.orderstock.order.*;
import com.zeroziba.orderstock.payment.*;
import com.zeroziba.orderstock.product.ProductRepository;
import com.zeroziba.orderstock.refund.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TossTransactions {
    record Claim(TossStore.Row row, String action) { }
    public record Checkout(TossView payment, String clientKey, String customerKey, String orderName, String successUrl, String failUrl) { }
    private final TossStore store;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final ProductRepository products;
    private final InventoryLedger ledger;
    private final Clock clock;
    private final TossSettings settings;
    public TossTransactions(TossStore store, OrderRepository orders, PaymentRepository payments, RefundRepository refunds,
                            ProductRepository products, InventoryLedger ledger, Clock clock, TossSettings settings) {
        this.store=store; this.orders=orders; this.payments=payments; this.refunds=refunds;
        this.products=products; this.ledger=ledger; this.clock=clock; this.settings=settings;
    }
    LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private String uuid() { return UUID.randomUUID().toString(); }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"PAYMENT_STATE_CONFLICT",message); }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"ORDER_NOT_FOUND","주문 또는 결제 정보를 찾을 수 없어요."); }
    private PurchaseOrder lock(long order, Long user) {
        return (user == null ? orders.findByIdForExpiration(order) : orders.findByIdForUpdate(order,user)).orElseThrow(this::missing);
    }
    private TossStore.Row require(String id) { return store.find(id).orElseThrow(this::missing); }

    @Transactional
    public Checkout checkout(long user, long orderId, String key) {
        settings.requireEnabled();
        if (key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY","유효한 결제 요청 키가 필요해요.");
        var order=lock(orderId,user); order.requirePayable(now());
        if (order.getTotalAmount()<1 || order.getTotalAmount()>9007199254740991L)
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_AMOUNT","카드 테스트 결제에 사용할 수 없는 주문 금액이에요.");
        TossStore.Row row=store.request(orderId,key).orElseGet(()->store.active(orderId).orElse(null));
        if (row==null) {
            String id="srp_"+uuid().replace("-","");
            store.jdbc().update("INSERT INTO toss_payments (id,order_id,request_key,amount,customer_key,status,confirm_key,refund_key,created_at) VALUES (?,?,?,?,?,'READY',?,?,?)",
                    id,orderId,key,order.getTotalAmount(),uuid(),uuid(),uuid(),now());
            row=require(id);
        }
        if (row.outboundDisabled()) throw conflict("복원된 결제는 결과 확인이 먼저 필요해요.");
        if (!row.status().equals("READY")) throw conflict("이전 결제 결과를 먼저 확인해 주세요.");
        String name=order.getItems().get(0).getProductName()+(order.getItems().size()>1?" 외 "+(order.getItems().size()-1)+"건":"");
        name=name.substring(0,name.offsetByCodePoints(0,Math.min(100,name.codePointCount(0,name.length()))));
        return new Checkout(TossView.from(row),settings.clientKey,row.customerKey(),name,
                settings.origin+"/payment-result.html?checkout="+row.id(),settings.origin+"/payment-result.html?checkout="+row.id()+"&failed=1");
    }

    @Transactional
    Claim confirm(long user, String id, String paymentKey, long amount) {
        settings.requireEnabled(); var initial=require(id); var order=lock(initial.orderId(),user); var row=require(id);
        if (row.amount()!=amount) throw new ApiException(HttpStatus.BAD_REQUEST,"PAYMENT_AMOUNT_MISMATCH","주문 금액이 일치하지 않아요.");
        if (row.paymentKey()!=null && !row.paymentKey().equals(paymentKey)) throw conflict("같은 결제의 인증 정보를 바꿀 수 없어요.");
        if (row.outboundDisabled()) throw conflict("복원된 결제는 결과 확인이 먼저 필요해요.");
        if (!row.status().equals("READY")) return null;
        order.requirePayable(now());
        String claim=uuid();
        try {
            store.jdbc().update("UPDATE toss_payments SET payment_key=?,status='CONFIRMING',confirm_started_at=?,next_check_at=?,claim_id=? WHERE id=?",
                    paymentKey,now(),now().plusSeconds(30),claim,id);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) { throw conflict("이미 다른 요청에 사용된 결제 인증 정보예요."); }
        return new Claim(require(id),"CONFIRM");
    }

    @Transactional
    Claim cancel(long user,String id) {
        settings.requireEnabled(); var initial=require(id); var order=lock(initial.orderId(),user); var row=require(id);
        if (row.status().equals("CANCELED")) return null;
        if (row.outboundDisabled()) throw conflict("복원 또는 결제 정보 확인이 필요해요. 결과 확인을 먼저 이용하세요.");
        if (row.pending()) return null;
        if (!row.status().equals("PAID")) throw conflict("카드 테스트 결제가 완료된 주문만 환불할 수 있어요.");
        order.requireRefundable();
        store.jdbc().update("UPDATE toss_payments SET status='CANCELING',refund_started_at=?,next_check_at=?,claim_id=?,attempts=0 WHERE id=?",
                now(),now().plusSeconds(30),uuid(),id);
        return new Claim(require(id),"CANCEL");
    }

    @Transactional
    Claim claim(String id,Long user) {
        settings.requireEnabled(); var initial=require(id); lock(initial.orderId(),user); var row=require(id);
        if (row.nextCheckAt()==null || row.nextCheckAt().isAfter(now()) || (row.paymentKey()==null && !row.outboundDisabled())) return null;
        store.jdbc().update("UPDATE toss_payments SET claim_id=?,next_check_at=? WHERE id=?",uuid(),now().plusSeconds(30),id);
        return new Claim(require(id),"LOOKUP");
    }

    @Transactional(readOnly=true)
    public TossView view(long user,long order) {
        orders.findByIdAndUserId(order,user).orElseThrow(this::missing);
        return store.latest(order).map(TossView::from).orElseGet(()->TossView.none(order));
    }
    @Transactional(readOnly=true)
    public TossView viewById(long user,String id) { var row=require(id); orders.findByIdAndUserId(row.orderId(),user).orElseThrow(this::missing); return TossView.from(row); }
    @Transactional(readOnly=true)
    public List<TossView> states(long user,List<Long> ids) {
        if (ids.isEmpty() || ids.size()>20 || ids.stream().anyMatch(id->id==null || id<1))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","주문은 1~20개씩 확인하세요.");
        var placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var values=new ArrayList<Object>();values.add(user);values.addAll(ids);
        return store.jdbc().query("SELECT p.id,p.order_id,p.status,p.amount,p.outbound_disabled FROM toss_payments p JOIN purchase_orders o ON o.id=p.order_id "
                +"WHERE o.user_id=? AND o.id IN ("+placeholders+") AND NOT EXISTS (SELECT 1 FROM toss_payments n WHERE n.order_id=p.order_id "
                +"AND n.sequence>p.sequence)",(r,index)->new TossView(r.getString("id"),r.getLong("order_id"),r.getString("status"),r.getLong("amount"),
                Set.of("CONFIRMING","UNKNOWN","CANCELING").contains(r.getString("status")),r.getBoolean("outbound_disabled")),values.toArray());
    }

    @Transactional
    TossView apply(Claim claim,TossApiClient.Result result) {
        var row=claim.row(); var order=lock(row.orderId(),null); var current=require(row.id());
        if (!Objects.equals(current.claimId(),row.claimId())) return TossView.from(current);
        var outcome=result.outcome();
        if (current.paymentKey()==null && current.outboundDisabled() && result.paymentKey()!=null
                && (outcome==TossApiClient.Outcome.APPROVED || outcome==TossApiClient.Outcome.CANCELED)) {
            store.jdbc().update("UPDATE toss_payments SET payment_key=? WHERE id=?",result.paymentKey(),row.id());current=require(row.id());
        }
        if (outcome==TossApiClient.Outcome.APPROVED && (current.status().equals("CONFIRMING") || current.status().equals("UNKNOWN"))) {
            if (!current.outboundDisabled() && (current.confirmStartedAt()==null || !current.confirmStartedAt().isBefore(order.getExpiresAt()))) throw conflict("결제 시작 기한을 확인할 수 없어요.");
            order.markGatewayPaid(result.at());
            var payment=payments.saveAndFlush(PaymentAttempt.approved(order.getId(),"toss:"+row.confirmKey(),row.amount(),result.at()));
            store.jdbc().update("UPDATE toss_payments SET status='PAID',payment_id=?,attempts=0 WHERE id=?",payment.getId(),row.id());
        } else if (outcome==TossApiClient.Outcome.CANCELED) {
            if (current.status().equals("CONFIRMING") || current.status().equals("UNKNOWN")) {
                // A dashboard cancellation can be observed before the local approval commit.
                order.markGatewayPaid(result.approvedAt());
                var payment=payments.saveAndFlush(PaymentAttempt.approved(order.getId(),"toss:"+row.confirmKey(),row.amount(),result.approvedAt()));
                store.jdbc().update("UPDATE toss_payments SET payment_id=? WHERE id=?",payment.getId(),row.id());
                current=require(row.id());
            }
            if (current.paymentId()==null) throw conflict("원 결제 이력을 확인할 수 없어요.");
            var original=payments.findById(current.paymentId()).orElseThrow(this::missing);
            if (original.getOrderId()!=order.getId() || original.getAmount()!=row.amount() || original.getStatus()!=PaymentAttempt.Status.SUCCEEDED) throw conflict("원 결제 정보가 일치하지 않아요.");
            order.markRefunded(result.at());
            for (var item:order.getItems().stream().sorted(Comparator.comparing(OrderItem::getProductId)).toList()) {
                var product=products.findByIdForUpdate(item.getProductId()).orElseThrow(this::missing);int before=product.getStock();
                product.restoreStock(item.getQuantity());ledger.record(product,before,InventoryLedger.Type.REFUND,order.getId().toString(),null,"카드 테스트 전액 환불 재고 복구");
            }
            refunds.saveAndFlush(RefundAttempt.approved(order.getId(),current.paymentId(),"toss:"+row.refundKey(),row.amount(),result.at()));
            store.jdbc().update("UPDATE toss_payments SET status='CANCELED' WHERE id=?",row.id());
        } else if (outcome==TossApiClient.Outcome.REJECTED && (current.status().equals("CONFIRMING") || current.status().equals("UNKNOWN"))) {
            store.jdbc().update("UPDATE toss_payments SET status='FAILED' WHERE id=?",row.id());
        } else if (outcome==TossApiClient.Outcome.REVIEW || (outcome==TossApiClient.Outcome.REJECTED && Set.of("PAID","CANCELING").contains(current.status()))) {
            store.jdbc().update("UPDATE toss_payments SET outbound_disabled=TRUE WHERE id=?",row.id());
        } else if (current.status().equals("CONFIRMING")) store.jdbc().update("UPDATE toss_payments SET status='UNKNOWN' WHERE id=?",row.id());
        var updated=require(row.id());
        if ((outcome==TossApiClient.Outcome.APPROVED && updated.status().equals("PAID"))
                || (outcome==TossApiClient.Outcome.CANCELED && updated.status().equals("CANCELED")))
            store.jdbc().update("UPDATE toss_payments SET outbound_disabled=FALSE WHERE id=?",row.id());
        int attempts=outcome==TossApiClient.Outcome.APPROVED && updated.status().equals("PAID")?0:Math.min(20,updated.attempts()+1);
        LocalDateTime next=Set.of("FAILED","CANCELED").contains(updated.status())?null:now().plusSeconds(updated.status().equals("PAID")?300:Math.min(300,5L<<Math.min(attempts,6)));
        store.jdbc().update("UPDATE toss_payments SET claim_id=NULL,next_check_at=?,attempts=? WHERE id=?",next,attempts,row.id());
        return TossView.from(require(row.id()));
    }

    @Transactional
    public void hint(String id,String key) {
        var initial=store.find(id); if (initial.isEmpty()) return;
        lock(initial.get().orderId(),null); var row=require(id);
        if (key==null || !key.equals(row.paymentKey()) || row.nextCheckAt()==null || row.claimId()!=null) return;
        store.jdbc().update("UPDATE toss_payments SET next_check_at=LEAST(next_check_at,?),last_hint_at=? WHERE id=? AND (last_hint_at IS NULL OR last_hint_at<?)",
                now().plusSeconds(5),now(),id,now().minusSeconds(30));
    }
}
