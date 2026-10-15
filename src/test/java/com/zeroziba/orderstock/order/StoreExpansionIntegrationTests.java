package com.zeroziba.orderstock.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.zeroziba.orderstock.*;
import com.zeroziba.orderstock.auth.*;
import com.zeroziba.orderstock.inventory.*;
import com.zeroziba.orderstock.product.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class StoreExpansionIntegrationTests {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;
    @Autowired ProductRepository products; @Autowired ProductSearch search;
    @Autowired OrderService orders; @Autowired IdempotentOrderService idempotent;
    @Autowired CatalogService catalog; @Autowired CatalogChangeHistory changes;
    @Autowired InventoryService inventory;
    @Autowired OrderExpirationProcessor expiration;
    @Autowired com.zeroziba.orderstock.payment.PaymentService payments;
    @Autowired com.zeroziba.orderstock.refund.RefundService refunds;
    @MockitoSpyBean InventoryLedger ledger;
    long owner,admin,other; Product first,second;
    @BeforeEach void setup(){
        clean();owner=TestAccounts.create(jdbc,"expand@example.test");other=TestAccounts.create(jdbc,"other@example.test");admin=TestAccounts.create(jdbc,"admin@example.test");
        jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id=?",admin);
        first=products.saveAndFlush(new Product("Alpha 100%_!",1000,100));second=products.saveAndFlush(new Product("Beta",2000,100));
        // Existing test fixtures create products directly; give these two an explicit starting ledger.
        for(var p:List.of(first,second))jdbc.update("INSERT INTO inventory_movements(product_id,product_name,movement_type,event_reference,quantity_delta,before_stock,after_stock,reason,created_at) VALUES (?,?,'BASELINE','opening',100,0,100,'test baseline',UTC_TIMESTAMP(6))",p.getId(),p.getName());
    }
    @AfterEach void clean(){
        TestDatabase.cleanInventory(jdbc);
        for(String table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users"))jdbc.update("DELETE FROM "+table);
    }
    AuthenticatedUser principal(){return new AuthenticatedUser(owner,"expand@example.test",null);}
    AuthenticatedUser admin(){return new AuthenticatedUser(admin,"admin@example.test",null,UserRole.ADMIN);}
    CartOrderRequest cart(boolean reverse){var items=List.of(new CreateOrderRequest(first.getId(),2),new CreateOrderRequest(second.getId(),3));return new CartOrderRequest(reverse?List.of(items.get(1),items.get(0)):items);}
    int stock(Product p){return products.findById(p.getId()).orElseThrow().getStock();}
    @Test void publicSearchUsesLiteralWildcardsSellingAndAvailability(){
        assertThat(search.search("%_!",false,ProductSearch.Sort.ID_ASC,0).products()).extracting(ProductResponse::id).containsExactly(first.getId());
        jdbc.update("UPDATE products SET selling=FALSE WHERE id=?",first.getId());
        jdbc.update("UPDATE products SET stock=0 WHERE id=?",second.getId());
        assertThat(search.search("",true,ProductSearch.Sort.ID_ASC,0).products()).isEmpty();
        assertThat(search.search("",false,ProductSearch.Sort.ID_ASC,0).products()).extracting(ProductResponse::id).containsExactly(second.getId());
    }
    @Test void searchPagesHaveStablePriceTiesAndNoOverlap(){
        for(int i=0;i<23;i++)products.saveAndFlush(new Product("Page "+i,1000,1));
        var a=search.search("",false,ProductSearch.Sort.PRICE_ASC,0);var b=search.search("",false,ProductSearch.Sort.PRICE_ASC,1);
        assertThat(a.products()).hasSize(20);assertThat(a.hasMore()).isTrue();assertThat(b.products()).hasSize(5);assertThat(b.hasMore()).isFalse();
        assertThat(a.products()).extracting(ProductResponse::id).doesNotContainAnyElementsOf(b.products().stream().map(ProductResponse::id).toList());
        assertThat(search.search("",false,ProductSearch.Sort.PRICE_DESC,0).products().get(0).id()).isEqualTo(second.getId());
    }
    @Test void invalidSearchAndDateParametersAreRejected()throws Exception{
        for(String query:List.of("page=-1","page=10001","sort=SQL","q="+"a".repeat(101),"available=invalid"))mvc.perform(get("/products/search?"+query)).andExpect(status().isBadRequest());
        for(String query:List.of("status=UNKNOWN","from=2026-02-30","from=2026-10-16&to=2026-10-15"))mvc.perform(get("/orders?"+query).with(user(principal()))).andExpect(status().isBadRequest());
    }
    @Test void adminSearchFiltersLowStockAndChecksCurrentRole()throws Exception{
        jdbc.update("UPDATE products SET stock=5,selling=FALSE WHERE id=?",first.getId());
        assertThat(search.admin(admin,"",false,5,0).products()).extracting(CatalogService.Item::id).containsExactly(first.getId());
        mvc.perform(get("/admin/products/search").with(user(principal()))).andExpect(status().isForbidden());
        jdbc.update("UPDATE app_users SET user_role='USER' WHERE id=?",admin);
        mvc.perform(get("/admin/products/search").with(user(admin()))).andExpect(status().isForbidden());
    }
    @Test void orderFilterUsesOwnerStatusAndInclusiveKoreanDate()throws Exception{
        long included=orders.create(owner,new CreateOrderRequest(first.getId(),1)).orderId();
        long excluded=orders.create(owner,new CreateOrderRequest(first.getId(),1)).orderId();
        orders.create(other,new CreateOrderRequest(first.getId(),1));
        jdbc.update("UPDATE purchase_orders SET created_at='2026-10-15 14:59:59',expires_at='2026-10-15 16:00:00' WHERE id=?",included);
        jdbc.update("UPDATE purchase_orders SET created_at='2026-10-15 15:00:00',expires_at='2026-10-15 16:00:00' WHERE id=?",excluded);
        var page=orders.findRecent(owner,null,PurchaseOrder.Status.CREATED,LocalDate.of(2026,10,15),LocalDate.of(2026,10,15));
        assertThat(page.orders()).extracting(OrderResponse::orderId).containsExactly(included);
        orders.cancel(owner,included);
        assertThat(orders.findRecent(owner,null,PurchaseOrder.Status.CANCELLED,null,null).orders()).extracting(OrderResponse::orderId).containsExactly(included);
    }
    @Test void filteredOrderCursorKeepsFilterAcrossBoundary(){
        for(int i=0;i<22;i++)orders.create(owner,new CreateOrderRequest(first.getId(),1));
        var cancelled=orders.create(owner,new CreateOrderRequest(first.getId(),1));orders.cancel(owner,cancelled.orderId());
        var a=orders.findRecent(owner,null,PurchaseOrder.Status.CREATED,null,null);var b=orders.findRecent(owner,a.nextBeforeId(),PurchaseOrder.Status.CREATED,null,null);
        assertThat(a.orders()).hasSize(20);assertThat(b.orders()).hasSize(2);assertThat(b.nextBeforeId()).isNull();
    }
    @Test void historyDateFiltersDoNotChangeFullLedgerBalance(){
        inventory.adjust(admin,first.getId(),"date-adjust",new AdjustInventoryRequest(2,"date fixture"));
        var future=LocalDate.of(2090,1,1);
        var result=ledger.history(admin,first.getId(),null,future,future);
        assertThat(result.movements()).isEmpty();assertThat(result.currentStock()).isEqualTo(102);assertThat(result.recordedStock()).isEqualTo(102);assertThat(result.consistent()).isTrue();
        assertThat(inventory.history(admin,first.getId(),null,future,future).adjustments()).isEmpty();
        var current=catalog.list(admin).stream().filter(p->p.id()==first.getId()).findFirst().orElseThrow();
        catalog.edit(admin,first.getId(),new CatalogService.Edit("Changed",1001L,true,current.revision()));
        assertThat(changes.list(admin,first.getId(),null,future,future).changes()).isEmpty();
        assertThat(changes.list(admin,first.getId(),null).changes()).hasSize(1);
    }
    @Test void cartCreatesOneOrderWithServerPricesAndCanonicalReplay(){
        var a=idempotent.createCart(owner,"cart-key",cart(true));var b=idempotent.createCart(owner,"cart-key",cart(false));
        assertThat(a.replayed()).isFalse();assertThat(b.replayed()).isTrue();assertThat(b.order()).isEqualTo(a.order());
        assertThat(a.order().items()).hasSize(2);assertThat(a.order().totalAmount()).isEqualTo(8000);
        assertThat(stock(first)).isEqualTo(98);assertThat(stock(second)).isEqualTo(97);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_request_completions",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->idempotent.create(owner,"cart-key",new CreateOrderRequest(first.getId(),2))).isInstanceOf(com.zeroziba.orderstock.common.ApiException.class);
    }
    @Test void insufficientSecondProductRollsBackFirstAllocationAndReservation(){
        jdbc.update("UPDATE products SET stock=0 WHERE id=?",second.getId());
        assertThatThrownBy(()->idempotent.createCart(owner,"failed-cart",cart(false))).isInstanceOf(com.zeroziba.orderstock.common.ApiException.class);
        assertThat(stock(first)).isEqualTo(100);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_requests",Integer.class)).isZero();
    }
    @Test void failureAfterLedgerSqlRollsBackAllItemsOrderAndKey(){
        InventoryLedger target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(ledger);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("deliberate ledger failure");}).when(target).record(any(),anyInt(),eq(InventoryLedger.Type.ORDER),anyString(),any(),anyString());
        try{assertThatThrownBy(()->idempotent.createCart(owner,"rollback-cart",cart(false))).isInstanceOf(IllegalStateException.class);}
        finally{reset(target);}
        assertThat(stock(first)).isEqualTo(100);assertThat(stock(second)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_requests",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE movement_type='ORDER'",Integer.class)).isZero();
    }
    @Test void reversedConcurrentCartsDoNotDeadlockOrOversell()throws Exception{
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try{var tasks=new ArrayList<Future<Long>>();for(int i=0;i<20;i++){int index=i;tasks.add(pool.submit(()->{start.await();return idempotent.createCart(owner,"concurrent-"+index,cart(index%2==0)).order().orderId();}));}
            start.countDown();var ids=new HashSet<Long>();for(var task:tasks)ids.add(task.get(30,TimeUnit.SECONDS));assertThat(ids).hasSize(20);
            assertThat(stock(first)).isEqualTo(60);assertThat(stock(second)).isEqualTo(40);
            for(long id:ids)orders.cancel(owner,id);assertThat(stock(first)).isEqualTo(100);assertThat(stock(second)).isEqualTo(100);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void sameCartKeyConcurrentRequestsCreateOnlyOneOrder()throws Exception{
        var pool=Executors.newFixedThreadPool(8);
        try{var tasks=new ArrayList<Callable<Long>>();for(int i=0;i<20;i++)tasks.add(()->idempotent.createCart(owner,"same-cart",cart(true)).order().orderId());
            var ids=new HashSet<Long>();for(var result:pool.invokeAll(tasks))ids.add(result.get());assertThat(ids).hasSize(1);assertThat(stock(first)).isEqualTo(98);assertThat(stock(second)).isEqualTo(97);
        }finally{pool.shutdownNow();}
    }
    @Test void cartCancellationAndExpirationRestoreEveryItemOnlyOnce(){
        var a=idempotent.createCart(owner,"cancel-cart",cart(true)).order();orders.cancel(owner,a.orderId());orders.cancel(owner,a.orderId());
        var b=idempotent.createCart(owner,"expire-cart",cart(false)).order();
        assertThat(expiration.expire(b.orderId(),LocalDateTime.ofInstant(b.expiresAt(),ZoneOffset.UTC).plusSeconds(1))).isTrue();
        assertThat(expiration.expire(b.orderId(),LocalDateTime.ofInstant(b.expiresAt(),ZoneOffset.UTC).plusSeconds(2))).isFalse();
        assertThat(stock(first)).isEqualTo(100);assertThat(stock(second)).isEqualTo(100);
    }
    @Test void cartApiRequiresCsrfKeyAndValidUniqueItems()throws Exception{
        String body="{\"items\":[{\"productId\":"+first.getId()+",\"quantity\":1},{\"productId\":"+second.getId()+",\"quantity\":2}]}";
        mvc.perform(post("/orders/cart").with(user(principal())).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/orders/cart").with(user(principal())).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/orders/cart").with(user(principal())).with(csrf()).header("Idempotency-Key","http-cart").contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalAmount").value(5000));
        for(String invalid:List.of("{\"items\":[]}","{\"items\":[null]}","{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}"))mvc.perform(post("/orders/cart").with(user(principal())).with(csrf()).header("Idempotency-Key","invalid").contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
    }
    @Test void rawDatabaseOrderTimesAreUtcIndependentOfJvmTimezone(){
        var order=orders.create(owner,new CreateOrderRequest(first.getId(),1));
        assertThat(jdbc.queryForObject("SELECT created_at FROM purchase_orders WHERE id=?",LocalDateTime.class,order.orderId()))
            .isEqualTo(LocalDateTime.ofInstant(order.createdAt(),ZoneOffset.UTC));
    }
    @Test void cartFullRefundRestoresEveryItemAndOwnerHistory(){
        var order=idempotent.createCart(owner,"refund-cart",cart(false)).order();
        payments.pay(owner,order.orderId(),"cart-pay",new com.zeroziba.orderstock.payment.MockPaymentRequest(com.zeroziba.orderstock.payment.MockPaymentRequest.Result.SUCCESS));
        var a=refunds.refund(owner,order.orderId(),"cart-refund",new com.zeroziba.orderstock.refund.MockRefundRequest(com.zeroziba.orderstock.refund.MockRefundRequest.Result.SUCCESS));
        var b=refunds.refund(owner,order.orderId(),"cart-refund",new com.zeroziba.orderstock.refund.MockRefundRequest(com.zeroziba.orderstock.refund.MockRefundRequest.Result.SUCCESS));
        assertThat(b.replayed()).isTrue();assertThat(b.refund()).isEqualTo(a.refund());assertThat(stock(first)).isEqualTo(100);assertThat(stock(second)).isEqualTo(100);
        assertThatThrownBy(()->orders.findById(other,order.orderId())).isInstanceOf(com.zeroziba.orderstock.common.ApiException.class);
    }
    @Test void combinedAmountOverflowRollsBackEveryAllocation(){
        jdbc.update("UPDATE products SET price=?,stock=2 WHERE id IN (?,?)",Long.MAX_VALUE/2+1,first.getId(),second.getId());
        var request=new CartOrderRequest(List.of(new CreateOrderRequest(first.getId(),1),new CreateOrderRequest(second.getId(),1)));
        assertThatThrownBy(()->idempotent.createCart(owner,"overflow-cart",request)).isInstanceOf(com.zeroziba.orderstock.common.ApiException.class);
        assertThat(stock(first)).isEqualTo(2);assertThat(stock(second)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Integer.class)).isZero();
    }
}
