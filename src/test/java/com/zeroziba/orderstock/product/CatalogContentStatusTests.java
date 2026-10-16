package com.zeroziba.orderstock.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.zeroziba.orderstock.*;
import com.zeroziba.orderstock.auth.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class CatalogContentStatusTests {
    @Autowired JdbcTemplate jdbc;@Autowired ProductRepository products;@Autowired ProductContentService contents;@Autowired MockMvc mvc;
    long admin;Product product;
    @BeforeEach void setup(){clean();admin=TestAccounts.create(jdbc,"content-status@example.test");jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id=?",admin);product=products.saveAndFlush(new Product("Empty demo",1000,0));}
    @AfterEach void clean(){TestDatabase.cleanInventory(jdbc);for(var table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users"))jdbc.update("DELETE FROM "+table);}
    AuthenticatedUser principal(){return new AuthenticatedUser(admin,"content-status@example.test",null,UserRole.ADMIN);}
    @Test void absentRowsReportEmptyAndTrimmedContentReportsPresence()throws Exception{
        var url="/admin/products/content-status?ids="+product.getId();
        mvc.perform(get(url).with(user(principal()))).andExpect(status().isOk()).andExpect(jsonPath("$[0].image").value(false)).andExpect(jsonPath("$[0].description").value(false)).andExpect(jsonPath("$[0].specification").value(false));
        contents.update(admin,product.getId(),new ProductContentService.Update("등록한 설명"," \n ",0L));
        jdbc.update("UPDATE products SET selling=FALSE WHERE id=?",product.getId());
        mvc.perform(get(url).with(user(principal()))).andExpect(status().isOk()).andExpect(jsonPath("$[0].description").value(true)).andExpect(jsonPath("$[0].specification").value(false));
    }
    @Test void authenticationAndCurrentDatabaseRoleAreRequired()throws Exception{
        var url="/admin/products/content-status?ids="+product.getId();mvc.perform(get(url)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE app_users SET user_role='USER' WHERE id=?",admin);mvc.perform(get(url).with(user(principal()))).andExpect(status().isForbidden());
    }
    @Test void duplicateInvalidAndOversizedIdBatchesAreRejected()throws Exception{
        for(var ids:List.of("0","-1","1,1",String.join(",",java.util.stream.LongStream.rangeClosed(1,21).mapToObj(String::valueOf).toList())))
            mvc.perform(get("/admin/products/content-status?ids="+ids).with(user(principal()))).andExpect(status().isBadRequest());
    }
    @Test void disabledEmailRecoveryDoesNotAcceptRequests()throws Exception{
        mvc.perform(get("/auth/password-reset/config")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/auth/password-reset/request").with(csrf()).contentType("application/json").content("{\"email\":\"content-status@example.test\"}")).andExpect(status().isServiceUnavailable());
    }
    @Test void exactAdminProductLookupRetainsStoppedProductsAndChecksCurrentRole()throws Exception{
        jdbc.update("UPDATE products SET selling=FALSE WHERE id=?",product.getId());
        mvc.perform(get("/admin/products/"+product.getId()).with(user(principal()))).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Empty demo")).andExpect(jsonPath("$.selling").value(false));
        mvc.perform(get("/admin/products/9223372036854775807").with(user(principal()))).andExpect(status().isNotFound());
        jdbc.update("UPDATE app_users SET user_role='USER' WHERE id=?",admin);
        mvc.perform(get("/admin/products/"+product.getId()).with(user(principal()))).andExpect(status().isForbidden());
    }
}
