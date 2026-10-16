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
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ProductImageIntegrationTests {
    @Autowired ProductImageService images; @Autowired ProductRepository products; @MockitoSpyBean JdbcTemplate jdbc; @Autowired MockMvc mvc;
    long admin,owner;Product product;
    @BeforeEach void setup(){clean();admin=TestAccounts.create(jdbc,"image-admin@example.test");owner=TestAccounts.create(jdbc,"image-user@example.test");jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id=?",admin);product=products.saveAndFlush(new Product("Image product",1000,20));}
    @AfterEach void clean(){TestDatabase.cleanInventory(jdbc);for(var table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users"))jdbc.update("DELETE FROM "+table);}
    AuthenticatedUser principal(){return new AuthenticatedUser(admin,"image-admin@example.test",null,UserRole.ADMIN);}
    MockMultipartFile png(int width,int height)throws Exception{var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB),"png",output);return new MockMultipartFile("file","../../image.png","image/png",output.toByteArray());}
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String code){assertThatThrownBy(call).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);}
    @Test void uploadNormalizesToJpegResizesAndExposesVersionedPublicUrls()throws Exception{
        var info=images.upload(admin,product.getId(),0,png(2000,1000));assertThat(info.width()).isEqualTo(1600);assertThat(info.height()).isEqualTo(800);
        var image=images.image(product.getId(),1L,null);assertThat(image.bytes()).startsWith((byte)0xff,(byte)0xd8);
        assertThat(ImageIO.read(new ByteArrayInputStream(image.bytes())).getRGB(0,0)&0xffffff).isEqualTo(0xffffff);
        mvc.perform(get("/products/"+product.getId())).andExpect(jsonPath("$.imageUrl").value(info.imageUrl()));
        mvc.perform(get("/products/search")).andExpect(jsonPath("$.products[0].imageUrl").value(info.imageUrl()));
        mvc.perform(get(info.imageUrl())).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Cache-Control","no-cache"));
        assertThat(products.findById(product.getId()).orElseThrow().getCatalogRevision()).isZero();
    }
    @Test void sameContentDoesNotIncrementRevisionAndStaleReplacementIsRejected()throws Exception{
        var file=png(10,10);var before=images.upload(admin,product.getId(),0,file);
        assertThat(images.upload(admin,product.getId(),1,file)).isEqualTo(before);
        code(()->images.upload(admin,product.getId(),0,png(20,10)),"IMAGE_CHANGED");assertThat(images.history(admin,product.getId(),null).changes()).hasSize(1);
    }
    @Test void deleteRetainsRevisionAndAuditButNotImageBytes()throws Exception{
        images.upload(admin,product.getId(),0,png(10,10));var deleted=images.delete(admin,product.getId(),1);
        assertThat(deleted.imageUrl()).isNull();assertThat(deleted.revision()).isEqualTo(2);code(()->images.image(product.getId(),1L,null),"IMAGE_NOT_FOUND");
        assertThat(images.delete(admin,product.getId(),2)).isEqualTo(deleted);assertThat(images.history(admin,product.getId(),null).changes()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT image_data IS NULL FROM product_images WHERE product_id=?",Boolean.class,product.getId())).isTrue();
    }
    @Test void wrongMimeFakeImagesOversizedFilesAndExcessiveDimensionsAreRejected()throws Exception{
        code(()->images.upload(admin,product.getId(),0,new MockMultipartFile("file","x.svg","image/svg+xml","<svg/>".getBytes())),"INVALID_IMAGE");
        code(()->images.upload(admin,product.getId(),0,new MockMultipartFile("file","fake.png","image/png","not an image".getBytes())),"INVALID_IMAGE");
        code(()->images.upload(admin,product.getId(),0,new MockMultipartFile("file","x.png",null,new byte[]{1})),"INVALID_IMAGE");
        code(()->images.upload(admin,product.getId(),0,new MockMultipartFile("file","x.png","image/png",new byte[ProductImageService.MAX_BYTES+1])),"IMAGE_TOO_LARGE");
        code(()->images.upload(admin,product.getId(),0,png(8001,1)),"INVALID_IMAGE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_images",Integer.class)).isZero();
    }
    @Test void stoppedProductsAreHiddenPubliclyButVisibleToCurrentAdmins()throws Exception{
        images.upload(admin,product.getId(),0,png(10,10));jdbc.update("UPDATE products SET selling=FALSE WHERE id=?",product.getId());
        mvc.perform(get("/products/"+product.getId()+"/image")).andExpect(status().isNotFound());
        mvc.perform(get("/admin/products/"+product.getId()+"/image/file").with(user(principal()))).andExpect(status().isOk());
        jdbc.update("UPDATE app_users SET user_role='USER' WHERE id=?",admin);code(()->images.delete(admin,product.getId(),1),"ADMIN_REQUIRED");
    }
    @Test void csrfRoleAndRevisionProtectUploadsAndDeletion()throws Exception{
        var url="/admin/products/"+product.getId()+"/image";
        mvc.perform(multipart(url).file(png(10,10)).param("revision","0")).andExpect(status().isForbidden());
        mvc.perform(multipart(url).file(png(10,10)).param("revision","0").with(user(principal()))).andExpect(status().isForbidden());
        mvc.perform(multipart(url).file(png(10,10)).param("revision","0").with(user(principal())).with(csrf())).andExpect(status().isOk());
        mvc.perform(delete(url).param("revision","-1").with(user(principal())).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete(url).param("revision","1").with(user(principal()))).andExpect(status().isForbidden());
        mvc.perform(delete(url).param("revision","1").with(user(principal())).with(csrf())).andExpect(status().isOk());
    }
    @Test void concurrentFirstUploadHasOneWinner()throws Exception{
        var file=png(10,10);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();try{images.upload(admin,product.getId(),0,file);return true;}catch(ApiException error){assertThat(error.getCode()).isEqualTo("IMAGE_CHANGED");return false;}}));start.countDown();int wins=0;for(var task:tasks)if(task.get(15,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);assertThat(images.history(admin,product.getId(),null).changes()).hasSize(1);}
        finally{pool.shutdownNow();}
    }
    @Test void auditFailureRollsBackBytesRevisionAndAudit()throws Exception{
        var before=images.upload(admin,product.getId(),0,png(10,10));var bytes=images.image(product.getId(),1L,null).bytes();
        doAnswer(call->{Object value=call.callRealMethod();if(((String)call.getArgument(0)).startsWith("INSERT INTO product_image_changes"))throw new IllegalStateException("Injected failure");return value;}).when(jdbc).update(anyString(),any(Object[].class));
        try{assertThatThrownBy(()->images.upload(admin,product.getId(),1,png(20,20))).isInstanceOf(IllegalStateException.class);}
        finally{reset(jdbc);}
        assertThat(images.info(admin,product.getId())).isEqualTo(before);assertThat(images.image(product.getId(),1L,null).bytes()).isEqualTo(bytes);assertThat(images.history(admin,product.getId(),null).changes()).hasSize(1);
    }
    @Test void phoneExifOrientationIsAppliedAndOriginalMetadataIsRemoved()throws Exception{
        var jpeg=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(30,10,BufferedImage.TYPE_INT_RGB),"jpeg",jpeg);var original=jpeg.toByteArray();
        byte[] exif={ (byte)255,(byte)225,0,34,'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0 };
        var combined=new ByteArrayOutputStream();combined.write(original,0,2);combined.write(exif);combined.write(original,2,original.length-2);
        var info=images.upload(admin,product.getId(),0,new MockMultipartFile("file","phone.jpg","image/jpeg",combined.toByteArray()));
        assertThat(info.width()).isEqualTo(10);assertThat(info.height()).isEqualTo(30);
        assertThat(new String(images.image(product.getId(),1L,null).bytes(),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }
    @Test void oldRevisionCannotReturnNewBytesAndHistoryUsesCursor()throws Exception{
        for(int i=0;i<22;i++)images.upload(admin,product.getId(),i,png(10+i,10));
        code(()->images.image(product.getId(),1L,null),"IMAGE_NOT_FOUND");var history=images.history(admin,product.getId(),null);
        assertThat(history.changes()).hasSize(20);assertThat(images.history(admin,product.getId(),history.nextBeforeId()).changes()).hasSize(2);
    }
}
