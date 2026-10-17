package com.zeroziba.orderstock.payment.toss;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.TestDatabase;
import com.zeroziba.orderstock.auth.AuthenticatedUser;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.order.*;
import com.zeroziba.orderstock.payment.*;
import com.zeroziba.orderstock.product.*;
import com.zeroziba.orderstock.refund.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP against a disposable loopback provider, with real MySQL transactions. */
@SpringBootTest(properties={"app.toss.enabled=true","app.toss.client-key=test_ck_fixture123","app.toss.secret-key=test_sk_fixture123",
        "app.toss.origin=http://localhost:18083","app.toss.stub-enabled=true","app.toss.recovery-initial-delay=86400000"})
@AutoConfigureMockMvc
@ActiveProfiles({"test","pg-stub"})
class TossIntegrationTests {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final AtomicReference<String> mode=new AtomicReference<>("ok"), remote=new AtomicReference<>("READY");
    static final AtomicInteger confirms=new AtomicInteger(), cancels=new AtomicInteger(), lookups=new AtomicInteger();
    static final Set<String> confirmKeys=ConcurrentHashMap.newKeySet(), cancelKeys=ConcurrentHashMap.newKeySet();
    static volatile String merchant, paymentKey="pk_fixture", receivedAuth;
    static volatile CountDownLatch entered, release;
    static final HttpServer PG=start();
    static HttpServer start() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;}));
            server.createContext("/v1/payments",exchange->{
                try {
                    receivedAuth=exchange.getRequestHeaders().getFirst("Authorization");
                    if (!Objects.equals(receivedAuth,"Basic "+Base64.getEncoder().encodeToString("test_sk_fixture123:".getBytes(StandardCharsets.UTF_8)))) throw new IllegalStateException("Authentication required");
                    String path=exchange.getRequestURI().getPath(); boolean confirm=path.endsWith("/confirm"), cancel=path.endsWith("/cancel");
                    if (confirm || cancel) {
                        var payload=JSON.readTree(exchange.getRequestBody().readAllBytes());
                        String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
                        if (key==null) throw new IllegalStateException("Missing idempotency");
                        (confirm?confirmKeys:cancelKeys).add(key); (confirm?confirms:cancels).incrementAndGet();
                        if (confirm && (!merchant.equals(payload.path("orderId").asString()) || payload.path("amount").asLong()!=30000)) throw new IllegalStateException("Bad approval");
                        if (mode.get().equals("decline") && confirm) { reply(exchange,400,"{\"code\":\"REJECT_CARD_PAYMENT\"}");return; }
                        if (mode.get().equals("unavailable")) { reply(exchange,503,"{}");return; }
                        remote.set(confirm?"DONE":"CANCELED");
                        if (entered!=null) { entered.countDown();release.await(8,TimeUnit.SECONDS); }
                    } else lookups.incrementAndGet();
                    if (mode.get().equals("hidden") || mode.get().equals("unavailable")) { reply(exchange,503,"{}");return; }
                    if (remote.get().equals("READY")) { reply(exchange,404,"{\"code\":\"NOT_FOUND_PAYMENT\"}");return; }
                    var body=new LinkedHashMap<String,Object>();body.put("orderId",mode.get().equals("wrong-order")?"another":merchant);
                    body.put("paymentKey",paymentKey);body.put("currency","KRW");body.put("type","NORMAL");body.put("method","카드");
                    body.put("totalAmount",mode.get().equals("wrong-amount")?1:30000);body.put("balanceAmount",remote.get().equals("CANCELED")?0:30000);
                    body.put("status",mode.get().equals("partial")?"PARTIAL_CANCELED":remote.get());body.put("approvedAt","2026-10-18T09:00:00+09:00");
                    if (Set.of("ABORTED","EXPIRED").contains(remote.get())) {body.put("method",null);body.put("approvedAt",null);}
                    body.put("cancels",List.of(Map.of("transactionKey","tx_fixture","cancelAmount",30000,"cancelStatus","DONE","canceledAt","2026-10-18T09:01:00+09:00")));
                    reply(exchange,200,JSON.writeValueAsString(body));
                } catch (Exception error) { try { reply(exchange,500,"{}"); } catch(Exception ignored) {} }
            });server.start();return server;
        } catch(Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    static void reply(com.sun.net.httpserver.HttpExchange exchange,int status,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    @DynamicPropertySource static void pg(DynamicPropertyRegistry registry) { registry.add("app.toss.stub-url",()->"http://127.0.0.1:"+PG.getAddress().getPort()); }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired OrderService orders;
    @Autowired OrderExpirationProcessor expiration;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentService mockPayments;
    @Autowired RefundService mockRefunds;
    @Autowired TossTransactions transactions;
    @Autowired TossPaymentService service;
    @Autowired TossStore store;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @MockitoSpyBean PaymentRepository payments;
    @MockitoSpyBean RefundRepository refunds;
    long owner,other,product,order;
    @BeforeEach void prepare() {
        clean();reset(payments,refunds);paymentKey="pk_fixture";mode.set("ok");remote.set("READY");confirms.set(0);cancels.set(0);lookups.set(0);confirmKeys.clear();cancelKeys.clear();entered=null;release=null;
        owner=TestAccounts.create(jdbc,"pg@example.test");other=TestAccounts.create(jdbc,"other.pg@example.test");
        product=products.saveAndFlush(new Product("PG 검증 상품",15000,10)).getId();order=orders.create(owner,new CreateOrderRequest(product,2)).orderId();
    }
    @AfterEach void clean() {
        TestDatabase.cleanInventory(jdbc);
        for(var table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users"))jdbc.update("DELETE FROM "+table);
    }
    String checkout() { merchant=transactions.checkout(owner,order,UUID.randomUUID().toString()).payment().checkoutId();return merchant; }
    TossView confirm() { return service.confirm(owner,merchant,paymentKey,30000); }
    void due() { jdbc.update("UPDATE toss_payments SET next_check_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",merchant); }
    int stock() { return products.findById(product).orElseThrow().getStock(); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    @Test void approvalAndRefundAreIdempotentAndRestoreStockOnce() {
        checkout();assertThat(confirm().status()).isEqualTo("PAID");assertThat(confirm().status()).isEqualTo("PAID");
        assertThat(confirms.get()).isEqualTo(1);assertThat(count("payment_attempts")).isEqualTo(1);assertThat(stock()).isEqualTo(8);
        assertThat(orders.findById(owner,order).status()).isEqualTo("PAID");
        assertThat(service.cancel(owner,merchant).status()).isEqualTo("CANCELED");assertThat(service.cancel(owner,merchant).status()).isEqualTo("CANCELED");
        assertThat(cancels.get()).isEqualTo(1);assertThat(count("refund_attempts")).isEqualTo(1);assertThat(stock()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE movement_type='REFUND'",Long.class)).isEqualTo(1);
    }
    @Test void amountAndPaymentKeyCannotBeChanged() {
        checkout();assertThatThrownBy(()->service.confirm(owner,merchant,paymentKey,1)).isInstanceOf(ApiException.class);assertThat(confirms.get()).isZero();
        confirm();assertThatThrownBy(()->service.confirm(owner,merchant,"other_key",30000)).isInstanceOf(ApiException.class);assertThat(confirms.get()).isEqualTo(1);
    }
    @Test void lostApprovalResponseHoldsReservationThenRecoversByLookup() {
        checkout();mode.set("hidden");assertThat(confirm().pending()).isTrue();assertThat(remote.get()).isEqualTo("DONE");
        assertThatThrownBy(()->orders.cancel(owner,order)).isInstanceOf(ApiException.class);
        assertThat(expiration.expire(order,LocalDateTime.now(ZoneOffset.UTC).plusDays(1))).isFalse();
        assertThatThrownBy(()->mockPayments.pay(owner,order,"mock",new MockPaymentRequest(MockPaymentRequest.Result.SUCCESS))).isInstanceOf(ApiException.class);
        mode.set("ok");due();assertThat(service.reconcile(owner,merchant).status()).isEqualTo("PAID");assertThat(confirms.get()).isEqualTo(1);assertThat(stock()).isEqualTo(8);
    }
    @Test void crashBeforeHttpUsesTheSameDurableApprovalKey() {
        checkout();var claim=transactions.confirm(owner,merchant,paymentKey,30000);assertThat(confirms.get()).isZero();
        due();assertThat(service.reconcile(owner,merchant).status()).isEqualTo("PAID");assertThat(confirmKeys).containsExactly(claim.row().confirmKey());
    }
    @Test void unavailableProviderRetriesWithTheOriginalIdempotencyKey() {
        checkout();mode.set("unavailable");assertThat(confirm().pending()).isTrue();mode.set("ok");due();
        assertThat(service.reconcile(owner,merchant).status()).isEqualTo("PAID");assertThat(confirms.get()).isEqualTo(2);assertThat(confirmKeys).hasSize(1);
    }
    @Test void dbFailureAfterRemoteApprovalIsRecoveredWithoutChargingAgain() {
        checkout();
        doAnswer(call->{entityManager.persist(call.getArgument(0));entityManager.flush();throw new IllegalStateException("Injected DB failure");}).when(payments).saveAndFlush(any(PaymentAttempt.class));
        assertThrows(IllegalStateException.class,this::confirm);assertThat(count("payment_attempts")).isZero();assertThat(orders.findById(owner,order).status()).isEqualTo("CREATED");
        reset(payments);due();assertThat(service.reconcile(owner,merchant).status()).isEqualTo("PAID");assertThat(confirms.get()).isEqualTo(1);assertThat(count("payment_attempts")).isEqualTo(1);
    }
    @Test void lostRefundResponseNeverRestocksBeforeVerification() {
        checkout();confirm();mode.set("hidden");assertThat(service.cancel(owner,merchant).status()).isEqualTo("CANCELING");assertThat(stock()).isEqualTo(8);
        mode.set("ok");due();assertThat(service.reconcile(owner,merchant).status()).isEqualTo("CANCELED");assertThat(stock()).isEqualTo(10);assertThat(cancels.get()).isEqualTo(1);
    }
    @Test void dbFailureAfterRemoteRefundRollsBackStockAndLedgerThenRecovers() {
        checkout();confirm();long movements=count("inventory_movements");
        doAnswer(call->{entityManager.persist(call.getArgument(0));entityManager.flush();throw new IllegalStateException("Injected refund DB failure");}).when(refunds).saveAndFlush(any(RefundAttempt.class));
        assertThrows(IllegalStateException.class,()->service.cancel(owner,merchant));
        assertThat(stock()).isEqualTo(8);assertThat(count("refund_attempts")).isZero();assertThat(count("inventory_movements")).isEqualTo(movements);assertThat(orders.findById(owner,order).status()).isEqualTo("PAID");
        reset(refunds);due();assertThat(service.reconcile(owner,merchant).status()).isEqualTo("CANCELED");assertThat(stock()).isEqualTo(10);assertThat(cancels.get()).isEqualTo(1);
    }
    @Test void startedBeforeDeadlineCanFinishAfterDeadlineWithoutReleasingReservation() {
        checkout();var claim=transactions.confirm(owner,merchant,paymentKey,30000);
        assertThat(expiration.expire(order,LocalDateTime.now(ZoneOffset.UTC).plusDays(1))).isFalse();
        assertThat(transactions.apply(claim,new TossApiClient.Result(TossApiClient.Outcome.APPROVED,LocalDateTime.now(ZoneOffset.UTC).plusDays(1))).status()).isEqualTo("PAID");assertThat(stock()).isEqualTo(8);
    }
    @Test void dashboardRefundBeforeLocalApprovalCommitIsReconstructed() {
        checkout();mode.set("hidden");confirm();remote.set("CANCELED");mode.set("ok");due();
        assertThat(service.reconcile(owner,merchant).status()).isEqualTo("CANCELED");assertThat(count("payment_attempts")).isEqualTo(1);assertThat(count("refund_attempts")).isEqualTo(1);assertThat(stock()).isEqualTo(10);
    }
    @Test void paidOrderPeriodicLookupDetectsDashboardRefund() {
        checkout();confirm();remote.set("CANCELED");due();assertThat(service.reconcile(owner,merchant).status()).isEqualTo("CANCELED");assertThat(stock()).isEqualTo(10);assertThat(cancels.get()).isZero();
    }
    @Test void partialRefundRequiresReviewWithoutFullRestock() {
        checkout();confirm();mode.set("partial");due();var view=service.reconcile(owner,merchant);
        assertThat(view.reviewRequired()).isTrue();assertThat(stock()).isEqualTo(8);assertThatThrownBy(()->service.cancel(owner,merchant)).isInstanceOf(ApiException.class);assertThat(cancels.get()).isZero();
    }
    @Test void providerMetadataMismatchNeverMarksOrderPaid() {
        checkout();mode.set("wrong-amount");assertThat(confirm().reviewRequired()).isTrue();assertThat(orders.findById(owner,order).status()).isEqualTo("CREATED");
        mode.set("unavailable");due();service.reconcile(owner,merchant);assertThat(confirms.get()).isEqualTo(1);assertThat(stock()).isEqualTo(8);
    }
    @Test void wrongMerchantIdRequiresReview() { checkout();mode.set("wrong-order");assertThat(confirm().reviewRequired()).isTrue();assertThat(count("payment_attempts")).isZero(); }
    @Test void restoredSnapshotFindsRemoteApprovalByMerchantIdWithoutMutatingProvider() {
        checkout();jdbc.update("UPDATE toss_payments SET status='UNKNOWN',outbound_disabled=TRUE WHERE id=?",merchant);remote.set("DONE");due();
        assertThat(service.reconcile(owner,merchant).status()).isEqualTo("PAID");assertThat(confirms.get()).isZero();assertThat(store.find(merchant).orElseThrow().paymentKey()).isEqualTo(paymentKey);
        assertThat(service.cancel(owner,merchant).status()).isEqualTo("CANCELED");assertThat(stock()).isEqualTo(10);
    }
    @Test void restoredMissingRemoteResultStaysQueryOnly() {
        checkout();jdbc.update("UPDATE toss_payments SET status='UNKNOWN',outbound_disabled=TRUE WHERE id=?",merchant);due();
        assertThat(service.reconcile(owner,merchant).pending()).isTrue();assertThat(confirms.get()).isZero();assertThat(stock()).isEqualTo(8);
    }
    @Test void protectedPendingOrderDoesNotStarveTheExpirationBatch() {
        checkout();transactions.confirm(owner,merchant,paymentKey,30000);
        long ordinary=orders.create(owner,new CreateOrderRequest(product,2)).orderId();
        var future=Clock.offset(Clock.systemUTC(),Duration.ofDays(1));
        var worker=new OrderExpirationService(orderRepository,expiration,new OrderExpirationProperties(false,Duration.ofMinutes(30),Duration.ofMinutes(1),1),future);
        assertThat(worker.expireDueOrders()).isEqualTo(1);assertThat(orders.findById(owner,ordinary).status()).isEqualTo("EXPIRED");
        assertThat(orders.findById(owner,order).status()).isEqualTo("CREATED");assertThat(stock()).isEqualTo(8);
    }
    @Test void mutationIsNotRetriedBeyondTwentyFourHours() {
        checkout();transactions.confirm(owner,merchant,paymentKey,30000);jdbc.update("UPDATE toss_payments SET confirm_started_at=UTC_TIMESTAMP(6)-INTERVAL 25 HOUR WHERE id=?",merchant);due();
        assertThat(service.reconcile(owner,merchant).pending()).isTrue();assertThat(confirms.get()).isZero();assertThat(lookups.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"ABORTED","EXPIRED"})
    void terminalFailureWithoutMethodClosesReservationGuard(String status) {
        checkout();transactions.confirm(owner,merchant,paymentKey,30000);remote.set(status);due();
        assertThat(service.reconcile(owner,merchant).status()).isEqualTo("FAILED");assertThat(confirms.get()).isZero();
        assertThat(expiration.expire(order,LocalDateTime.now(ZoneOffset.UTC).plusDays(1))).isTrue();assertThat(stock()).isEqualTo(10);
    }
    @Test void declineAllowsNewCheckoutWithMonotonicLatestSelection() {
        checkout();String old=merchant;mode.set("decline");assertThat(confirm().status()).isEqualTo("FAILED");
        checkout();assertThat(merchant).isNotEqualTo(old);assertThat(transactions.states(owner,List.of(order))).singleElement().extracting(TossView::checkoutId).isEqualTo(merchant);
        paymentKey="pk_fresh";mode.set("ok");assertThat(confirm().status()).isEqualTo("PAID");assertThat(transactions.viewById(owner,old).status()).isEqualTo("FAILED");
    }
    @Test void unusedCheckoutCanBeCancelledOrExpired() {
        checkout();orders.cancel(owner,order);assertThat(store.find(merchant).orElseThrow().status()).isEqualTo("FAILED");assertThat(stock()).isEqualTo(10);assertThat(confirms.get()).isZero();
    }
    @Test void unusedCheckoutExpiresAndLateCallbackCannotApprove() {
        checkout();assertThat(expiration.expire(order,LocalDateTime.now(ZoneOffset.UTC).plusDays(1))).isTrue();
        assertThat(confirm().status()).isEqualTo("FAILED");assertThat(confirms.get()).isZero();assertThat(stock()).isEqualTo(10);
    }
    @Test void mockRefundCannotBypassGateway() {
        checkout();confirm();assertThatThrownBy(()->mockRefunds.refund(owner,order,"mock",new MockRefundRequest(MockRefundRequest.Result.SUCCESS))).isInstanceOf(ApiException.class);assertThat(stock()).isEqualTo(8);
    }
    @Test void concurrentCallbacksUseOneHttpMutationAndDoNotHoldOrderLock() throws Exception {
        checkout();entered=new CountDownLatch(1);release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(this::confirm);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(this::confirm);assertThat(second.get(2,TimeUnit.SECONDS).pending()).isTrue();
            assertThatThrownBy(()->orders.cancel(owner,order)).isInstanceOf(ApiException.class);
            release.countDown();assertThat(first.get(5,TimeUnit.SECONDS).status()).isEqualTo("PAID");assertThat(confirms.get()).isEqualTo(1);
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void staleClaimCannotOverwriteRecoveredOutcome() {
        checkout();var old=transactions.confirm(owner,merchant,paymentKey,30000);remote.set("DONE");due();service.reconcile(owner,merchant);
        assertThat(transactions.apply(old,new TossApiClient.Result(TossApiClient.Outcome.REJECTED,null)).status()).isEqualTo("PAID");
    }
    @Test void ownershipCsrfValidationAndPublicConfig() throws Exception {
        checkout();var me=new AuthenticatedUser(owner,"pg@example.test","");var stranger=new AuthenticatedUser(other,"other.pg@example.test","");
        mvc.perform(get("/payments/toss/config")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.secretKey").doesNotExist());
        mvc.perform(post("/payments/toss/confirm").with(user(me)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/payments/toss/confirm").with(user(me)).with(csrf()).contentType("application/json").content("{\"orderId\":\"invalid\",\"paymentKey\":\"x\",\"amount\":1}")).andExpect(status().isBadRequest());
        mvc.perform(get("/payments/toss/"+merchant).with(user(stranger))).andExpect(status().isNotFound());
        mvc.perform(get("/payments/toss/"+merchant)).andExpect(status().isUnauthorized());
        mvc.perform(get("/payments/toss/states").param("ids",String.valueOf(order)).with(user(stranger))).andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThatThrownBy(()->service.confirm(other,merchant,paymentKey,30000)).isInstanceOf(ApiException.class);assertThat(confirms.get()).isZero();
    }
    @Test void forgedWebhookOnlySchedulesLookupAndBoundsBody() throws Exception {
        checkout();confirm();String body=JSON.writeValueAsString(Map.of("eventType","PAYMENT_STATUS_CHANGED","data",Map.of("orderId",merchant,"paymentKey",paymentKey,"status","CANCELED")));
        mvc.perform(post("/webhooks/toss/payment").contentType("application/json").content(body)).andExpect(status().isNoContent());
        assertThat(orders.findById(owner,order).status()).isEqualTo("PAID");assertThat(stock()).isEqualTo(8);assertThat(cancels.get()).isZero();
        mvc.perform(post("/webhooks/toss/payment").contentType("application/json").content("null")).andExpect(status().isBadRequest());
        mvc.perform(post("/webhooks/toss/payment").contentType("application/json").content("x".repeat(16385))).andExpect(status().isPayloadTooLarge());
    }
}
