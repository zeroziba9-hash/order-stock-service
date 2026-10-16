package com.zeroziba.orderstock.order;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.zeroziba.orderstock.*;
import com.zeroziba.orderstock.auth.*;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.product.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class CheckoutRecoveryIntegrationTests {
    @Autowired CheckoutRecoveryService recovery;@Autowired CheckoutService checkout;@Autowired ProductRepository products;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    long owner,other;Product product;
    @BeforeEach void setup(){clean();owner=TestAccounts.create(jdbc,"recover@example.test");other=TestAccounts.create(jdbc,"other-recover@example.test");product=products.saveAndFlush(new Product("Recovery product",1000,100));}
    @AfterEach void clean(){TestDatabase.cleanInventory(jdbc);for(var table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users"))jdbc.update("DELETE FROM "+table);}
    String quote(){return checkout.quote(owner,new CartOrderRequest(List.of(new CreateOrderRequest(product.getId(),2)))).quoteId();}
    CheckoutRecoveryService.Register body(String key){return new CheckoutRecoveryService.Register(key,CheckoutRecoveryService.Kind.cart,3L);}
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String code){assertThatThrownBy(call).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);}
    @Test void registrationPersistsOriginalKeyAndDoesNotReserveStock(){
        var id=quote();assertThat(recovery.list(owner)).isEmpty();var first=recovery.register(owner,id,body("original"));
        assertThat(recovery.register(owner,id,body("different"))).isEqualTo(first);assertThat(recovery.list(owner)).containsExactly(first);
        assertThat(first.items()).containsExactly(new CheckoutRecoveryService.Item(product.getId(),product.getName(),1000,2));
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(100);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Integer.class)).isZero();
    }
    @Test void completedOrderCanBeFoundWithoutClientStorageAndReplayed(){
        var id=quote();recovery.register(owner,id,body("recover"));var created=checkout.checkout(owner,"recover",new CheckoutService.Request(id));
        assertThat(recovery.list(owner).get(0).orderId()).isEqualTo(created.order().orderId());assertThat(checkout.checkout(owner,"recover",new CheckoutService.Request(id)).replayed()).isTrue();
        recovery.acknowledge(owner,id);assertThat(recovery.list(owner)).isEmpty();jdbc.update("DELETE FROM checkout_quotes WHERE id=?",id);
        assertThat(checkout.checkout(owner,"recover",new CheckoutService.Request(id)).order()).isEqualTo(created.order());assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(98);
    }
    @Test void wrongOwnerCannotListRegisterOrCloseAnIntent(){var id=quote();recovery.register(owner,id,body("owner"));assertThat(recovery.list(other)).isEmpty();code(()->recovery.register(other,id,body("other")),"QUOTE_NOT_FOUND");code(()->recovery.acknowledge(other,id),"QUOTE_NOT_FOUND");}
    @Test void closedUnusedIntentCannotCreateAnOrder(){var id=quote();recovery.register(owner,id,body("closed"));recovery.acknowledge(owner,id);code(()->checkout.checkout(owner,"closed",new CheckoutService.Request(id)),"CHECKOUT_CLOSED");assertThat(recovery.list(owner)).isEmpty();assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(100);}
    @Test void registeredIntentRejectsAnotherRequestKey(){var id=quote();recovery.register(owner,id,body("bound"));code(()->checkout.checkout(owner,"another",new CheckoutService.Request(id)),"CHECKOUT_KEY_CHANGED");assertThat(checkout.checkout(owner,"bound",new CheckoutService.Request(id)).replayed()).isFalse();}
    @Test void expiredIntentCannotBeNewlyRegisteredAndCanBeDismissed(){var id=quote();jdbc.update("UPDATE checkout_quotes SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);code(()->recovery.register(owner,id,body("expired")),"QUOTE_EXPIRED");recovery.acknowledge(owner,id);assertThat(recovery.list(owner)).isEmpty();}
    @Test void concurrentRegistrationReturnsOneCanonicalKey()throws Exception{
        var id=quote();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->{start.await();return recovery.register(owner,id,body("first"));});var second=pool.submit(()->{start.await();return recovery.register(owner,id,body("second"));});start.countDown();assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();}
    }
    @Test void closingAndCheckoutSerializeWithoutPartialAllocation()throws Exception{
        var id=quote();recovery.register(owner,id,body("race"));var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var creation=pool.submit(()->{start.await();try{return checkout.checkout(owner,"race",new CheckoutService.Request(id)).order().orderId();}catch(ApiException error){assertThat(error.getCode()).isEqualTo("CHECKOUT_CLOSED");return null;}});var close=pool.submit(()->{start.await();recovery.acknowledge(owner,id);return true;});start.countDown();Long order=creation.get(15,TimeUnit.SECONDS);close.get(15,TimeUnit.SECONDS);assertThat(recovery.list(owner)).isEmpty();assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(order==null?100:98);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Integer.class)).isEqualTo(order==null?0:1);}
        finally{pool.shutdownNow();}
    }
    @Test void recoveryRoutesRequireLoginCsrfAndValidPayload()throws Exception{
        var id=quote();var principal=new AuthenticatedUser(owner,"recover@example.test",null);var url="/orders/cart/quote/"+id+"/intent";
        mvc.perform(get("/orders/cart/recovery")).andExpect(status().isUnauthorized());
        mvc.perform(put(url).with(user(principal)).contentType("application/json").content("{\"key\":\"key\",\"kind\":\"cart\",\"cartRevision\":0}")).andExpect(status().isForbidden());
        mvc.perform(put(url).with(user(principal)).with(csrf()).contentType("application/json").content("{\"key\":\"key\",\"kind\":\"cart\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(url).with(user(principal)).with(csrf()).contentType("application/json").content("{\"key\":\"key\",\"kind\":\"direct\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.key").value("key"));
        mvc.perform(post("/orders/cart/quote/"+id+"/acknowledge").with(user(principal))).andExpect(status().isForbidden());
    }
}
