package com.zeroziba.orderstock.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.zeroziba.orderstock.*;
import com.zeroziba.orderstock.auth.*;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.order.*;
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
class ProductContentIntegrationTests {
    @Autowired ProductContentService content; @Autowired ProductRepository products; @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired CatalogService catalog; @Autowired CheckoutService checkout; @Autowired MockMvc mvc;
    long admin,owner; Product product,other;
    @BeforeEach void setup(){
        clean();admin=TestAccounts.create(jdbc,"content-admin@example.test");owner=TestAccounts.create(jdbc,"content-user@example.test");
        jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id=?",admin);
        product=products.saveAndFlush(new Product("Content product",1000,100));other=products.saveAndFlush(new Product("Other product",2000,10));
    }
    @AfterEach void clean(){
        TestDatabase.cleanInventory(jdbc);
        for(var table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users"))jdbc.update("DELETE FROM "+table);
    }
    AuthenticatedUser principal(){return new AuthenticatedUser(admin,"content-admin@example.test",null,UserRole.ADMIN);}
    ProductContentService.Update update(String text,long revision){return new ProductContentService.Update(text,"specification",revision);}
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String code){assertThatThrownBy(call).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);}
    @Test void publicDetailWorksForGuestsAndDefaultsToEmptyUnregisteredContent()throws Exception{
        mvc.perform(get("/products/"+product.getId())).andExpect(status().isOk()).andExpect(jsonPath("$.name").value(product.getName()))
            .andExpect(jsonPath("$.description").value("")).andExpect(jsonPath("$.specification").value(""));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_content",Integer.class)).isZero();
    }
    @Test void nonexistentInvalidAndStoppedProductsAreNotPublic()throws Exception{
        mvc.perform(get("/products/0")).andExpect(status().isBadRequest());mvc.perform(get("/products/9223372036854775807")).andExpect(status().isNotFound());
        jdbc.update("UPDATE products SET selling=FALSE WHERE id=?",product.getId());
        mvc.perform(get("/products/"+product.getId())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }
    @Test void savedContentPreservesLiteralMarkupAndInnerNewlines(){
        var saved=content.update(admin,product.getId(),new ProductContentService.Update("  <script>text</script>\nSecond line  ","  Spec\nTwo  ",0L));
        assertThat(saved.description()).isEqualTo("<script>text</script>\nSecond line");assertThat(saved.specification()).isEqualTo("Spec\nTwo");assertThat(saved.revision()).isEqualTo(1);
        assertThat(content.detail(product.getId()).description()).isEqualTo(saved.description());
        assertThat(products.findById(product.getId()).orElseThrow().getCatalogRevision()).isZero();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(100);
    }
    @Test void staleContentCannotOverwriteANewerRevision(){
        var saved=content.update(admin,product.getId(),update("Newer",0));
        code(()->content.update(admin,product.getId(),update("Stale",0)),"CONTENT_CHANGED");
        assertThat(content.get(admin,product.getId())).isEqualTo(saved);assertThat(content.history(admin,product.getId(),null).changes()).hasSize(1);
    }
    @Test void noOpAndWhitespaceOnlyEditsDoNotCreateHistory(){
        content.update(admin,product.getId(),new ProductContentService.Update("  ","\n",0L));
        var saved=content.update(admin,product.getId(),update("Text",0));
        assertThat(content.update(admin,product.getId(),new ProductContentService.Update(" Text "," specification ",1L))).isEqualTo(saved);
        assertThat(content.history(admin,product.getId(),null).changes()).hasSize(1);
    }
    @Test void simultaneousFirstEditorsHaveOneWinner()throws Exception{
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{var tasks=new ArrayList<Callable<Boolean>>();for(int i=0;i<2;i++){int index=i;tasks.add(()->{start.await();try{content.update(admin,product.getId(),update("Editor "+index,0));return true;}catch(ApiException error){assertThat(error.getCode()).isEqualTo("CONTENT_CHANGED");return false;}});}
            var futures=tasks.stream().map(pool::submit).toList();start.countDown();int successes=0;for(var future:futures)if(future.get(30,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);assertThat(content.history(admin,product.getId(),null).changes()).hasSize(1);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void auditInsertionFailureRollsBackContentAndRevision(){
        content.update(admin,product.getId(),update("Original",0));
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Deliberate failure after audit SQL");})
            .when(jdbc).update(startsWith("INSERT INTO product_content_changes"),any(Object[].class));
        try{assertThatThrownBy(()->content.update(admin,product.getId(),update("Lost",1))).isInstanceOf(IllegalStateException.class);}
        finally{reset(jdbc);}
        assertThat(content.get(admin,product.getId()).description()).isEqualTo("Original");assertThat(content.get(admin,product.getId()).revision()).isEqualTo(1);
        assertThat(content.history(admin,product.getId(),null).changes()).hasSize(1);
        assertThat(content.update(admin,product.getId(),update("Retry",1)).revision()).isEqualTo(2);
    }
    @Test void historyHasBoundedPagesAndProductIsolation(){
        for(int i=0;i<21;i++)content.update(admin,product.getId(),update("Revision "+i,i));
        content.update(admin,other.getId(),update("Other",0));
        var first=content.history(admin,product.getId(),null);var second=content.history(admin,product.getId(),first.nextBeforeId());
        assertThat(first.changes()).hasSize(20);assertThat(second.changes()).hasSize(1);assertThat(second.nextBeforeId()).isNull();
        assertThat(first.changes().get(0).after().revision()).isEqualTo(21);assertThat(second.changes().get(0).before().revision()).isZero();
    }
    @Test void currentDatabaseRoleIsRequiredForReadWriteAndHistory()throws Exception{
        code(()->content.get(owner,product.getId()),"ADMIN_REQUIRED");code(()->content.update(owner,product.getId(),update("Bad",0)),"ADMIN_REQUIRED");
        jdbc.update("UPDATE app_users SET user_role='USER' WHERE id=?",admin);
        mvc.perform(get("/admin/products/"+product.getId()+"/content").with(user(principal()))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/products/"+product.getId()+"/content/changes").with(user(principal()))).andExpect(status().isForbidden());
    }
    @Test void apiValidatesCsrfTypesLengthsAndRequiredFields()throws Exception{
        var path="/admin/products/"+product.getId()+"/content";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(put(path).with(user(principal())).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        for(var body:List.of("{}","{\"description\":null,\"specification\":\"\",\"revision\":0}","{\"description\":\"\",\"specification\":\"\",\"revision\":-1}","{\"description\":\""+"x".repeat(2001)+"\",\"specification\":\"\",\"revision\":0}","{\"description\":\"\",\"specification\":\""+"x".repeat(1001)+"\",\"revision\":0}"))
            mvc.perform(put(path).with(user(principal())).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(get(path+"/changes?beforeId=0").with(user(principal()))).andExpect(status().isBadRequest());
    }
    @Test void contentAndCatalogEditsHaveIndependentRevisions(){
        catalog.edit(admin,product.getId(),new CatalogService.Edit("Renamed",1111L,true,0L));
        content.update(admin,product.getId(),update("Details",0));
        var detail=content.detail(product.getId());assertThat(detail.name()).isEqualTo("Renamed");assertThat(detail.price()).isEqualTo(1111);assertThat(detail.description()).isEqualTo("Details");
    }
    @Test void descriptionChangeDoesNotInvalidateConfirmedPriceOrReserveStock(){
        var quote=checkout.quote(owner,new CartOrderRequest(List.of(new CreateOrderRequest(product.getId(),2))));
        content.update(admin,product.getId(),update("Updated description",0));
        var order=checkout.checkout(owner,"content-quote",new CheckoutService.Request(quote.quoteId()));
        assertThat(order.order().totalAmount()).isEqualTo(2000);assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(98);
    }
}
