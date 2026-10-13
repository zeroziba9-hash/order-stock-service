package com.zeroziba.orderstock.product;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.TestDatabase;
import com.zeroziba.orderstock.auth.*;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.inventory.*;
import com.zeroziba.orderstock.order.*;
import com.zeroziba.orderstock.payment.*;
import com.zeroziba.orderstock.refund.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class CatalogLedgerIntegrationTests {
    @Autowired CatalogService catalog;
    @Autowired InventoryService inventory;
    @Autowired OrderService orders;
    @Autowired com.zeroziba.orderstock.order.IdempotentOrderService idempotentOrders;
    @Autowired PaymentService payments;
    @Autowired RefundService refunds;
    @Autowired OrderExpirationProcessor expiration;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoSpyBean InventoryLedger ledger;
    long admin,customer,product;
    @BeforeEach void prepare() {
        clean(); admin=TestAccounts.create(jdbc,"catalog-admin@example.test"); customer=TestAccounts.create(jdbc,"catalog-customer@example.test");
        jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id=?",admin);
        product=catalog.create(admin,"seed",new CatalogService.Create("테스트 상품",1000L)).productId();
        inventory.adjust(admin,product,"stock",new AdjustInventoryRequest(10,"초기 입고"));
    }
    @AfterEach void clean() {
        TestDatabase.cleanInventory(jdbc);
        for(var table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users")) jdbc.update("DELETE FROM "+table);
    }
    @Test void createStartsAtZeroAndReplayReturnsSameIdAfterEdits() {
        var first=catalog.create(admin,"new",new CatalogService.Create(" 새 상품 ",2000L));
        assertThat(catalog.list(admin).stream().filter(p->p.id()==first.productId()).findFirst().orElseThrow().stock()).isZero();
        catalog.edit(admin,first.productId(),new CatalogService.Edit("변경 상품",3000L,false,0L));
        var replay=catalog.create(admin,"new",new CatalogService.Create("새 상품",2000L));
        assertThat(replay.replayed()).isTrue(); assertThat(replay.productId()).isEqualTo(first.productId());
        assertThrows(ApiException.class,()->catalog.create(admin,"new",new CatalogService.Create("다른 상품",2000L)));
        assertThat(ledger.history(admin,first.productId(),null).movements()).hasSize(1);
    }
    @Test void concurrentRegistrationReservesOneProduct() throws Exception {
        var pool=Executors.newFixedThreadPool(6);
        try {
            var tasks=java.util.stream.IntStream.range(0,6).<Callable<Long>>mapToObj(i->()->catalog.create(admin,"concurrent",new CatalogService.Create("한 상품",100L)).productId()).toList();
            var ids=new java.util.HashSet<Long>(); for(var future:pool.invokeAll(tasks)) ids.add(future.get(15,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        } finally {pool.shutdownNow();}
    }
    @Test void staleEditsAreRejectedAndStockChangesDoNotInvalidateCatalogRevision() {
        inventory.adjust(admin,product,"extra",new AdjustInventoryRequest(1,"추가 입고"));
        catalog.edit(admin,product,new CatalogService.Edit("수정 상품",2000L,true,0L));
        var error=assertThrows(ApiException.class,()->catalog.edit(admin,product,new CatalogService.Edit("덮어쓰기",3000L,true,0L)));
        assertThat(error.getCode()).isEqualTo("CATALOG_CHANGED");
        assertThat(catalog.list(admin).get(0).stock()).isEqualTo(11);
    }
    @Test void saleStopHidesCatalogAndBlocksNewOrdersButAllowsOldCancellation() throws Exception {
        var order=orders.create(customer,new CreateOrderRequest(product,2));
        catalog.edit(admin,product,new CatalogService.Edit("이름 수정",9000L,false,0L));
        mvc.perform(get("/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(assertThrows(ApiException.class,()->orders.create(customer,new CreateOrderRequest(product,1))).getCode()).isEqualTo("PRODUCT_NOT_SELLING");
        assertThat(orders.findById(customer,order.orderId()).totalAmount()).isEqualTo(2000);
        assertThat(orders.findById(customer,order.orderId()).items().get(0).productName()).isEqualTo("테스트 상품");
        orders.cancel(customer,order.orderId()); assertThat(ledger.history(admin,product,null).currentStock()).isEqualTo(10);
        catalog.edit(admin,product,new CatalogService.Edit("다시 판매",9000L,true,1L));
        assertThat(orders.create(customer,new CreateOrderRequest(product,1)).totalAmount()).isEqualTo(9000);
    }
    @Test void orderCancelRefundExpiryAndManualChangesReconcileExactlyOnce() {
        var cancelled=orders.create(customer,new CreateOrderRequest(product,2));
        orders.cancel(customer,cancelled.orderId()); orders.cancel(customer,cancelled.orderId());
        var refunded=orders.create(customer,new CreateOrderRequest(product,1));
        payments.pay(customer,refunded.orderId(),"pay",new MockPaymentRequest(MockPaymentRequest.Result.SUCCESS));
        catalog.edit(admin,product,new CatalogService.Edit("판매 중지 상품",1000L,false,0L));
        refunds.refund(customer,refunded.orderId(),"refund",new MockRefundRequest(MockRefundRequest.Result.SUCCESS));
        refunds.refund(customer,refunded.orderId(),"refund",new MockRefundRequest(MockRefundRequest.Result.SUCCESS));
        catalog.edit(admin,product,new CatalogService.Edit("상품",1000L,true,1L));
        var expired=orders.create(customer,new CreateOrderRequest(product,3));
        var future=LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
        assertThat(expiration.expire(expired.orderId(),future)).isTrue(); assertThat(expiration.expire(expired.orderId(),future)).isFalse();
        var history=ledger.history(admin,product,null);
        assertThat(history.consistent()).isTrue(); assertThat(history.currentStock()).isEqualTo(10);
        assertThat(history.movements()).hasSize(8);
        assertThat(history.movements().stream().map(InventoryLedger.Movement::type)).contains("BASELINE","MANUAL","ORDER","CANCEL","REFUND","EXPIRE");
    }
    @Test void failedRefundDoesNotWriteMovement() {
        var order=orders.create(customer,new CreateOrderRequest(product,1));
        payments.pay(customer,order.orderId(),"pay",new MockPaymentRequest(MockPaymentRequest.Result.SUCCESS));
        refunds.refund(customer,order.orderId(),"failed",new MockRefundRequest(MockRefundRequest.Result.FAILURE));
        assertThat(ledger.history(admin,product,null).movements()).hasSize(3);
    }
    @Test void failureAfterLedgerInsertRollsBackOrderAndStockAndLedger() {
        InventoryLedger target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(ledger);
        doAnswer(call->{call.callRealMethod(); throw new DataIntegrityViolationException("Forced failure after ledger SQL");})
                .when(target).record(any(),anyInt(),eq(InventoryLedger.Type.ORDER),anyString(),any(),anyString());
        assertThrows(DataIntegrityViolationException.class,()->orders.create(customer,new CreateOrderRequest(product,1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Long.class)).isZero();
        assertThat(ledger.history(admin,product,null).movements()).hasSize(2);
        assertThat(ledger.history(admin,product,null).currentStock()).isEqualTo(10);
    }
    @Test void ledgerCursorUsesFullBalanceAndDetectsOutOfBandStockChange() {
        for(int i=0;i<22;i++) inventory.adjust(admin,product,"page-"+i,new AdjustInventoryRequest(1,"입고"));
        var first=ledger.history(admin,product,null); var second=ledger.history(admin,product,first.nextBeforeId());
        assertThat(first.movements()).hasSize(20); assertThat(second.movements()).hasSize(4);
        assertThat(second.recordedStock()).isEqualTo(32); assertThat(second.consistent()).isTrue();
        jdbc.update("UPDATE products SET stock=stock+1 WHERE id=?",product);
        assertThat(ledger.history(admin,product,null).consistent()).isFalse();
    }
    @Test void adminPermissionsAndCsrfApplyToNewRoutes() throws Exception {
        var normal=new AuthenticatedUser(customer,"customer@example.test",null);
        for(var route:List.of("/admin/products","/admin/inventory-movements?productId="+product,"/catalog.html")) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
            mvc.perform(get(route).with(user(normal))).andExpect(status().isForbidden());
        }
        var principal=new AuthenticatedUser(admin,"admin@example.test",null,UserRole.ADMIN);
        mvc.perform(post("/admin/products").with(user(principal)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"상품\",\"price\":100}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_users SET user_role='USER' WHERE id=?",admin);
        mvc.perform(get("/admin/products").with(user(principal))).andExpect(status().isForbidden());
        assertThrows(ApiException.class,()->catalog.edit(admin,product,new CatalogService.Edit("상품",100L,true,0L)));
    }
    @Test void invalidCatalogAndCursorDoNotChangeState() throws Exception {
        var principal=new AuthenticatedUser(admin,"admin@example.test",null,UserRole.ADMIN);
        for(var body:List.of("{}","{\"name\":\" \" ,\"price\":100}","{\"name\":\"상품\",\"price\":-1}","{\"name\":\"상품\",\"price\":1.5}"))
            mvc.perform(post("/admin/products").with(user(principal)).with(csrf()).header("Idempotency-Key","bad").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertThrows(ApiException.class,()->ledger.history(admin,product,0L));
        assertThat(catalog.list(admin)).hasSize(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CANCEL","REFUND","EXPIRE"})
    void restorationLedgerFailureRollsBackStatusAndStock(String type) {
        var order=orders.create(customer,new CreateOrderRequest(product,1));
        if(type.equals("REFUND")) payments.pay(customer,order.orderId(),"pay",new MockPaymentRequest(MockPaymentRequest.Result.SUCCESS));
        InventoryLedger target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(ledger);
        doAnswer(call->{call.callRealMethod(); throw new DataIntegrityViolationException("Forced restoration ledger failure");})
                .when(target).record(any(),anyInt(),eq(InventoryLedger.Type.valueOf(type)),anyString(),any(),anyString());
        assertThrows(DataIntegrityViolationException.class,()->{
            switch(type) {
                case "CANCEL" -> orders.cancel(customer,order.orderId());
                case "REFUND" -> refunds.refund(customer,order.orderId(),"refund",new MockRefundRequest(MockRefundRequest.Result.SUCCESS));
                default -> expiration.expire(order.orderId(),LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
            }
        });
        assertThat(orders.findById(customer,order.orderId()).status()).isEqualTo(type.equals("REFUND")?"PAID":"CREATED");
        assertThat(ledger.history(admin,product,null).currentStock()).isEqualTo(9);
        assertThat(ledger.history(admin,product,null).movements()).hasSize(3);
        assertThat(ledger.history(admin,product,null).consistent()).isTrue();
    }
    @Test void experimentalAllocatorRejectsUnknownAndProductionModes() {
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("app.inventory.allocation","unknown");
        assertThrows(IllegalStateException.class,()->new StockAllocator(null,null,null,env));
        env.setProperty("app.inventory.allocation","conditional");
        assertThrows(IllegalStateException.class,()->new StockAllocator(null,null,null,env));
        env.setActiveProfiles("benchmark"); assertThatCode(()->new StockAllocator(null,null,null,env)).doesNotThrowAnyException();
        env.setActiveProfiles("prod","benchmark"); assertThrows(IllegalStateException.class,()->new StockAllocator(null,null,null,env));
    }
    @Test void distributedConcurrentOrdersDoNotRelockExistingBaselineRows() throws Exception {
        var ids=new java.util.ArrayList<Long>();
        for(int i=0;i<8;i++) {
            long id=catalog.create(admin,"distributed-product-"+i,new CatalogService.Create("분산 상품 "+i,1000L)).productId();
            inventory.adjust(admin,id,"distributed-stock-"+i,new AdjustInventoryRequest(16,"분산 주문 입고"));
            ids.add(id);
        }
        var start=new java.util.concurrent.CyclicBarrier(8);
        var pool=Executors.newFixedThreadPool(8);
        try {
            var tasks=java.util.stream.IntStream.range(0,8).<Callable<Integer>>mapToObj(worker->()->{
                start.await(10,TimeUnit.SECONDS);
                for(int i=0;i<8;i++) idempotentOrders.create(customer,"distributed-"+worker+"-"+i,new CreateOrderRequest(ids.get(worker),1));
                return 8;
            }).toList();
            for(var future:pool.invokeAll(tasks)) assertThat(future.get(30,TimeUnit.SECONDS)).isEqualTo(8);
        } finally {pool.shutdownNow();}
        for(long id:ids) {
            var history=ledger.history(admin,id,null);
            assertThat(history.consistent()).isTrue(); assertThat(history.currentStock()).isEqualTo(8);
            assertThat(history.movements().stream().filter(m->m.type().equals("BASELINE"))).hasSize(1);
            assertThat(history.movements()).hasSize(10);
        }
    }
}
