package com.zeroziba.orderstock.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.zeroziba.orderstock.*;
import com.zeroziba.orderstock.auth.*;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.inventory.InventoryLedger;
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
class CheckoutIntegrationTests {
    @Autowired CheckoutService checkout; @Autowired CartService carts; @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc; @Autowired ProductRepository products; @Autowired MockMvc mvc;
    @MockitoSpyBean InventoryLedger ledger;
    long owner,other; Product first,second;
    @BeforeEach void setup(){
        clean(); owner=TestAccounts.create(jdbc,"checkout@example.test");other=TestAccounts.create(jdbc,"checkout-other@example.test");
        first=products.saveAndFlush(new Product("First",1000,100));second=products.saveAndFlush(new Product("Second",2000,100));
    }
    @AfterEach void clean(){
        TestDatabase.cleanInventory(jdbc);
        for(String table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users"))jdbc.update("DELETE FROM "+table);
    }
    List<CreateOrderRequest> items(){return List.of(new CreateOrderRequest(first.getId(),2),new CreateOrderRequest(second.getId(),3));}
    CheckoutService.Quote quote(){return checkout.quote(owner,new CartOrderRequest(items()));}
    CheckoutService.Request request(String id){return new CheckoutService.Request(id);}
    int stock(Product p){return products.findById(p.getId()).orElseThrow().getStock();}
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String code){assertThatThrownBy(call).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);}
    void unchanged(){assertThat(stock(first)).isEqualTo(100);assertThat(stock(second)).isEqualTo(100);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_requests",Integer.class)).isZero();}
    @Test void quotingUsesCurrentPricesWithoutAllocatingStock(){
        var quote=quote();assertThat(quote.canOrder()).isTrue();assertThat(quote.totalAmount()).isEqualTo("8000");assertThat(quote.items()).hasSize(2);
        assertThat(Duration.between(Instant.now(),quote.expiresAt()).toSeconds()).isBetween(590L,600L);unchanged();
    }
    @Test void previewReportsAllUnavailableProductsAndDoesNotSaveAnOrderableQuote(){
        jdbc.update("UPDATE products SET selling=FALSE WHERE id=?",first.getId());jdbc.update("UPDATE products SET stock=0 WHERE id=?",second.getId());
        var quote=quote();assertThat(quote.canOrder()).isFalse();assertThat(quote.quoteId()).isNull();assertThat(quote.items().get(0).selling()).isFalse();assertThat(quote.items().get(1).stock()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM checkout_quotes",Integer.class)).isZero();
    }
    @Test void quoteAmountIsExactBeyondJavascriptSafeIntegers(){
        jdbc.update("UPDATE products SET price=1000000000000,stock=100000 WHERE id=?",first.getId());
        var quote=checkout.quote(owner,new CartOrderRequest(List.of(new CreateOrderRequest(first.getId(),100000))));
        assertThat(quote.totalAmount()).isEqualTo("100000000000000000");
    }
    @Test void secondProductPriceChangeRollsBackBothStocksAndKey(){
        var quote=quote();jdbc.update("UPDATE products SET price=2001,catalog_revision=catalog_revision+1 WHERE id=?",second.getId());
        code(()->checkout.checkout(owner,"price",request(quote.quoteId())),"QUOTE_CHANGED");unchanged();
    }
    @Test void samePriceWithNewCatalogRevisionStillNeedsConfirmation(){
        var quote=quote();jdbc.update("UPDATE products SET name='New name',catalog_revision=catalog_revision+1 WHERE id=?",second.getId());
        code(()->checkout.checkout(owner,"rename",request(quote.quoteId())),"QUOTE_CHANGED");unchanged();
    }
    @Test void stockMayChangeButConfirmedPriceRemainsUsable(){
        var quote=quote();jdbc.update("UPDATE products SET stock=99 WHERE id=?",second.getId());
        var result=checkout.checkout(owner,"stock-only",request(quote.quoteId()));assertThat(result.order().totalAmount()).isEqualTo(8000);assertThat(stock(second)).isEqualTo(96);
    }
    @Test void insufficientStockAfterQuoteRollsBackFirstProduct(){
        var quote=quote();jdbc.update("UPDATE products SET stock=1 WHERE id=?",second.getId());
        code(()->checkout.checkout(owner,"sold",request(quote.quoteId())),"INSUFFICIENT_STOCK");assertThat(stock(first)).isEqualTo(100);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_requests",Integer.class)).isZero();
    }
    @Test void sellingStoppedAfterQuoteRejectsCheckout(){
        var quote=quote();jdbc.update("UPDATE products SET selling=FALSE,catalog_revision=catalog_revision+1 WHERE id=?",second.getId());
        code(()->checkout.checkout(owner,"stop",request(quote.quoteId())),"PRODUCT_NOT_SELLING");unchanged();
    }
    @Test void expiredQuoteDoesNotReserveKeyOrInventory(){
        var quote=quote();jdbc.update("UPDATE checkout_quotes SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",quote.quoteId());
        code(()->checkout.checkout(owner,"expired",request(quote.quoteId())),"QUOTE_EXPIRED");unchanged();
    }
    @Test void expiryIsCheckedAfterInventoryLocksToo(){
        var expected=List.of(new CheckoutService.Expected(first.getId(),2,1000,0),new CheckoutService.Expected(second.getId(),3,2000,0));
        code(()->orders.createConfirmed(owner,expected,Instant.now().minusSeconds(1)),"QUOTE_EXPIRED");unchanged();
    }
    @Test void quoteCannotBeUsedByAnotherAccount(){
        var quote=quote();code(()->checkout.checkout(other,"other",request(quote.quoteId())),"QUOTE_NOT_FOUND");unchanged();
    }
    @Test void replaySurvivesExpiryPriceChangesAndQuoteDeletion(){
        var quote=quote();var first=checkout.checkout(owner,"replay",request(quote.quoteId()));
        jdbc.update("UPDATE products SET price=9,catalog_revision=catalog_revision+1");jdbc.update("DELETE FROM checkout_quotes");
        var replay=checkout.checkout(owner,"replay",request(quote.quoteId()));assertThat(replay.replayed()).isTrue();assertThat(replay.order()).isEqualTo(first.order());
        assertThat(stock(this.first)).isEqualTo(98);assertThat(stock(second)).isEqualTo(97);
    }
    @Test void oneQuoteCannotCreateOrdersWithDifferentKeys(){
        var quote=quote();checkout.checkout(owner,"first",request(quote.quoteId()));
        code(()->checkout.checkout(owner,"second",request(quote.quoteId())),"QUOTE_ALREADY_USED");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Integer.class)).isEqualTo(1);
    }
    @Test void sameKeyCannotBeReusedForAnotherQuote(){
        var first=quote();var second=quote();checkout.checkout(owner,"same",request(first.quoteId()));
        code(()->checkout.checkout(owner,"same",request(second.quoteId())),"IDEMPOTENCY_KEY_REUSED");
    }
    @Test void concurrentSameQuoteAndKeyCreatesOneOrder()throws Exception{
        var quote=quote();var pool=Executors.newFixedThreadPool(8);
        try{var jobs=new ArrayList<Callable<Long>>();for(int i=0;i<16;i++)jobs.add(()->checkout.checkout(owner,"concurrent",request(quote.quoteId())).order().orderId());
            var ids=new HashSet<Long>();for(var task:pool.invokeAll(jobs))ids.add(task.get(30,TimeUnit.SECONDS));assertThat(ids).hasSize(1);assertThat(stock(first)).isEqualTo(98);
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentDifferentKeysStillConsumeQuoteOnlyOnce()throws Exception{
        var quote=quote();var pool=Executors.newFixedThreadPool(8);
        try{var jobs=new ArrayList<Callable<Boolean>>();for(int i=0;i<12;i++){int index=i;jobs.add(()->{try{checkout.checkout(owner,"different-"+index,request(quote.quoteId()));return true;}catch(ApiException e){assertThat(e.getCode()).isEqualTo("QUOTE_ALREADY_USED");return false;}});}
            int successes=0;for(var task:pool.invokeAll(jobs))if(task.get(30,TimeUnit.SECONDS))successes++;assertThat(successes).isEqualTo(1);assertThat(stock(first)).isEqualTo(98);
        }finally{pool.shutdownNow();}
    }
    @Test void failureAfterLedgerWriteLeavesQuoteReusable(){
        var quote=quote();InventoryLedger target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(ledger);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("deliberate checkout failure");}).when(target).record(any(),anyInt(),eq(InventoryLedger.Type.ORDER),anyString(),any(),anyString());
        try{assertThatThrownBy(()->checkout.checkout(owner,"rollback",request(quote.quoteId()))).isInstanceOf(IllegalStateException.class);}finally{reset(target);}
        unchanged();assertThat(jdbc.queryForObject("SELECT order_id FROM checkout_quotes WHERE id=?",Long.class,quote.quoteId())).isNull();
        assertThat(checkout.checkout(owner,"rollback",request(quote.quoteId())).replayed()).isFalse();
    }
    @Test void quoteCleanupPreservesRecentQuotes(){
        var old=quote();var recent=quote();jdbc.update("UPDATE checkout_quotes SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 2 DAY WHERE id=?",old.quoteId());checkout.cleanup();
        assertThat(jdbc.queryForList("SELECT id FROM checkout_quotes",String.class)).containsExactly(recent.quoteId());
    }
    @Test void quoteQuotaIsBounded(){
        for(int i=0;i<100;i++)jdbc.update("INSERT INTO checkout_quotes(id,user_id,expires_at) VALUES (?,?,UTC_TIMESTAMP(6)+INTERVAL 5 MINUTE)",UUID.randomUUID().toString(),owner);
        code(this::quote,"QUOTE_LIMIT");unchanged();
    }
    @Test void cartPersistsWithRevisionAndOwnerIsolation(){
        assertThat(carts.get(owner).items()).isEmpty();var result=carts.update(owner,new CartService.Update(0L,items()));assertThat(result.revision()).isEqualTo(1);
        assertThat(carts.get(owner)).isEqualTo(result);assertThat(carts.get(other).items()).isEmpty();assertThat(stock(first)).isEqualTo(100);
    }
    @Test void staleCartWriteDoesNotOverwriteNewerDevice(){
        var saved=carts.update(owner,new CartService.Update(0L,items()));
        code(()->carts.update(owner,new CartService.Update(0L,List.of())),"CART_CHANGED");assertThat(carts.get(owner)).isEqualTo(saved);
    }
    @Test void simultaneousInitialCartWritesHaveOneWinner()throws Exception{
        var pool=Executors.newFixedThreadPool(2);
        try{var jobs=List.<Callable<Boolean>>of(()->saveInitial(first.getId()),()->saveInitial(second.getId()));int successes=0;for(var result:pool.invokeAll(jobs))if(result.get())successes++;assertThat(successes).isEqualTo(1);assertThat(carts.get(owner).items()).hasSize(1);assertThat(carts.get(owner).revision()).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    boolean saveInitial(long id){try{carts.update(owner,new CartService.Update(0L,List.of(new CreateOrderRequest(id,1))));return true;}catch(ApiException e){assertThat(e.getCode()).isEqualTo("CART_CHANGED");return false;}}
    @Test void cartRefreshShowsLatestPricesAndStoppedItems(){
        carts.update(owner,new CartService.Update(0L,items()));jdbc.update("UPDATE products SET price=1111,selling=FALSE WHERE id=?",first.getId());
        var saved=carts.get(owner);assertThat(saved.items().get(0).price()).isEqualTo(1111);assertThat(saved.items().get(0).selling()).isFalse();assertThat(saved.revision()).isEqualTo(1);
    }
    @Test void missingOrDuplicateCartItemsRollBackWholeSave(){
        carts.update(owner,new CartService.Update(0L,items()));
        code(()->carts.update(owner,new CartService.Update(1L,List.of(new CreateOrderRequest(Long.MAX_VALUE,1)))),"PRODUCT_NOT_FOUND");
        code(()->carts.update(owner,new CartService.Update(1L,List.of(items().get(0),items().get(0)))),"INVALID_REQUEST");assertThat(carts.get(owner).items()).hasSize(2);assertThat(carts.get(owner).revision()).isEqualTo(1);
    }
    @Test void newApisRequireLoginCsrfAndValidFields()throws Exception{
        var user=new AuthenticatedUser(owner,"checkout@example.test",null);
        mvc.perform(get("/cart")).andExpect(status().isUnauthorized());
        mvc.perform(put("/cart").with(user(user)).contentType("application/json").content("{\"revision\":0,\"items\":[]}")).andExpect(status().isForbidden());
        mvc.perform(put("/cart").with(user(user)).with(csrf()).contentType("application/json").content("{\"revision\":-1,\"items\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/orders/cart/quote").with(user(user)).with(csrf()).contentType("application/json").content("{\"items\":[]}")).andExpect(status().isBadRequest());
        var quote=quote();var body="{\"quoteId\":\""+quote.quoteId()+"\"}";
        mvc.perform(post("/orders/cart/checkout").with(user(user)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/orders/cart/checkout").with(user(user)).with(csrf()).header("Idempotency-Key","http-checkout").contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(8000));
        mvc.perform(post("/orders/cart/checkout").with(user(user)).with(csrf()).header("Idempotency-Key","http-checkout").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed","true"));
    }
}
