package com.zeroziba.orderstock.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.TestDatabase;
import com.zeroziba.orderstock.auth.AuthenticatedUser;
import com.zeroziba.orderstock.auth.UserRole;
import com.zeroziba.orderstock.common.ApiException;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class CatalogChangeHistoryIntegrationTests {
    @Autowired CatalogService catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean CatalogChangeHistory changes;
    long admin, otherAdmin, customer, product;

    @BeforeEach void prepare() {
        clean(); admin=TestAccounts.create(jdbc,"history-admin@example.test");
        otherAdmin=TestAccounts.create(jdbc,"history-other@example.test");
        customer=TestAccounts.create(jdbc,"history-customer@example.test");
        jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id IN (?,?)",admin,otherAdmin);
        product=catalog.create(admin,"seed",new CatalogService.Create("원래 상품",1000L)).productId();
    }
    @AfterEach void clean() {
        TestDatabase.cleanInventory(jdbc);
        for(var table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users"))
            jdbc.update("DELETE FROM "+table);
    }
    private CatalogService.Item edit(long actor,long revision,String name,long price,boolean selling) {
        return catalog.edit(actor,product,new CatalogService.Edit(name,price,selling,revision));
    }
    @Test void capturesActorUtcAndImmutableBeforeAfterSnapshots() {
        assertThat(changes.list(admin,product,null).changes()).isEmpty();
        var start=java.time.Instant.now().minusSeconds(1);
        edit(admin,0," 새 이름 ",2000,false);
        edit(otherAdmin,1,"다시 판매",3000,true);
        var rows=changes.list(admin,product,null).changes();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).actorId()).isEqualTo(otherAdmin);
        assertThat(rows.get(1).actorId()).isEqualTo(admin);
        assertThat(rows.get(1).before()).isEqualTo(new CatalogChangeHistory.Snapshot("원래 상품",1000,true,0));
        assertThat(rows.get(1).after()).isEqualTo(new CatalogChangeHistory.Snapshot("새 이름",2000,false,1));
        assertThat(rows.get(0).before()).isEqualTo(rows.get(1).after());
        assertThat(rows.get(1).createdAt()).isAfter(start).isBefore(java.time.Instant.now().plusSeconds(1));
    }
    @Test void noOpPreservesRevisionAndStaleOrInvalidEditsDoNotAddHistory() {
        assertThat(edit(admin,0," 원래 상품 ",1000,true).revision()).isZero();
        assertThat(changes.list(admin,product,null).changes()).isEmpty();
        edit(admin,0,"수정",2000,false);
        assertThat(assertThrows(ApiException.class,()->edit(admin,0,"수정",2000,false)).getCode()).isEqualTo("CATALOG_CHANGED");
        assertThrows(ApiException.class,()->edit(admin,1," ",2000,true));
        assertThat(changes.list(admin,product,null).changes()).hasSize(1);
    }
    @Test void historyInsertFailureRollsBackAlreadyFlushedCatalogChange() {
        CatalogChangeHistory target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(changes);
        doAnswer(call->{call.callRealMethod(); throw new DataIntegrityViolationException("Forced failure after audit insert");})
                .when(target).record(anyLong(),any(Product.class),any(CatalogChangeHistory.Snapshot.class));
        assertThrows(DataIntegrityViolationException.class,()->edit(admin,0,"실패",9000,false));
        assertThat(catalog.list(admin).get(0)).extracting(CatalogService.Item::name,CatalogService.Item::price,
                CatalogService.Item::selling,CatalogService.Item::revision).containsExactly("원래 상품",1000L,true,0L);
        assertThat(changes.list(admin,product,null).changes()).isEmpty();
    }
    @Test void enclosingTransactionRollbackRemovesBothEditAndHistory() {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            edit(admin,0,"롤백",2000,false);
            assertThat(changes.list(admin,product,null).changes()).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(catalog.list(admin).get(0).revision()).isZero();
        assertThat(changes.list(admin,product,null).changes()).isEmpty();
    }
    @Test void concurrentAdminsWithSameRevisionProduceOneChange() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var start=new CyclicBarrier(2);
        try {
            var tasks=List.<Callable<Boolean>>of(()->concurrentEdit(start,admin,"첫 수정"),
                    ()->concurrentEdit(start,otherAdmin,"다른 수정"));
            var results=pool.invokeAll(tasks,20,TimeUnit.SECONDS);
            assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
            assertThat(changes.list(admin,product,null).changes()).hasSize(1);
            assertThat(catalog.list(admin).get(0).revision()).isEqualTo(1);
        } finally {pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    private boolean concurrentEdit(CyclicBarrier start,long actor,String name) throws Exception {
        start.await(10,TimeUnit.SECONDS);
        try {edit(actor,0,name,2000,true);return true;}
        catch(ApiException error) {assertThat(error.getCode()).isEqualTo("CATALOG_CHANGED");return false;}
    }
    @Test void productCursorDoesNotDuplicateRowsAfterNewEdit() {
        long other=catalog.create(admin,"other",new CatalogService.Create("다른 상품",100L)).productId();
        catalog.edit(admin,other,new CatalogService.Edit("다른 변경",200L,true,0L));
        for(int i=0;i<22;i++) edit(admin,i,"이름 "+i,1000+i,true);
        var first=changes.list(admin,product,null);
        assertThat(first.changes()).hasSize(20); assertThat(first.nextBeforeId()).isNotNull();
        edit(admin,22,"추가 변경",9999,true);
        var second=changes.list(admin,product,first.nextBeforeId());
        assertThat(second.changes()).hasSize(2); assertThat(second.nextBeforeId()).isNull();
        assertThat(java.util.stream.Stream.concat(first.changes().stream(),second.changes().stream())
                .map(CatalogChangeHistory.Change::changeId).distinct()).hasSize(22);
        assertThat(second.changes()).allMatch(row->row.productId()==product);
    }
    @Test void endpointRequiresCurrentAdminRoleAndValidCursorAndProduct() throws Exception {
        String path="/admin/products/"+product+"/changes";
        var principal=new AuthenticatedUser(admin,"admin@example.test",null,UserRole.ADMIN);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user(new AuthenticatedUser(customer,"customer@example.test",null))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(user(principal))).andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(0));
        mvc.perform(get(path+"?beforeId=0").with(user(principal))).andExpect(status().isBadRequest());
        mvc.perform(get(path+"?beforeId=oops").with(user(principal))).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/products/0/changes").with(user(principal))).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/products/9223372036854775806/changes").with(user(principal))).andExpect(status().isNotFound());
        jdbc.update("UPDATE app_users SET user_role='USER' WHERE id=?",admin);
        mvc.perform(get(path).with(user(principal))).andExpect(status().isForbidden());
    }
    @Test void editHttpCannotSpoofActorAndStillRequiresCsrf() throws Exception {
        var principal=new AuthenticatedUser(admin,"admin@example.test",null,UserRole.ADMIN);
        String body="{\"name\":\"HTTP 수정\",\"price\":2000,\"selling\":false,\"revision\":0,\"actorId\":"+otherAdmin+"}";
        mvc.perform(put("/admin/products/"+product).with(user(principal)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/admin/products/"+product).with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(changes.list(admin,product,null).changes().get(0).actorId()).isEqualTo(admin);
    }
}
