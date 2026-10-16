package com.zeroziba.orderstock.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.zeroziba.orderstock.*;
import com.zeroziba.orderstock.common.ApiException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.*;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;

@SpringBootTest(properties={"app.password-reset.enabled=true","app.password-reset.base-url=http://localhost:18083","app.password-reset.from=no-reply@stockroom.test","spring.mail.host=localhost","management.health.mail.enabled=false"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE) @ActiveProfiles("test") @ExtendWith(RestDocumentationExtension.class)
class PasswordResetIntegrationTests {
    @Autowired PasswordResetService resets;@Autowired UserRepository users;@Autowired PasswordEncoder passwords;@MockitoSpyBean JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @MockitoBean JavaMailSender sender;@MockitoBean Clock clock;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("passwordResetMailExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;
    final AtomicReference<Instant> now=new AtomicReference<>();long owner;
    @BeforeEach void setup(){clean();reset(sender,clock);now.set(Instant.parse("2026-10-16T00:00:00Z"));when(clock.instant()).thenAnswer(call->now.get());when(clock.getZone()).thenReturn(ZoneOffset.UTC);owner=TestAccounts.create(jdbc,"recovery@example.test");}
    @AfterEach void clean(){awaitMail();TestDatabase.cleanInventory(jdbc);for(var table:List.of("refund_attempts","payment_attempts","order_requests","order_items","purchase_orders","products","app_users"))jdbc.update("DELETE FROM "+table);}
    void awaitMail(){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(executor.getActiveCount()>0||!executor.getThreadPoolExecutor().getQueue().isEmpty()){if(System.nanoTime()>deadline)throw new AssertionError("Mail worker did not finish");java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));}}
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String code){assertThatThrownBy(call).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);}
    void advance(long seconds){now.updateAndGet(value->value.plusSeconds(seconds));}
    void session(){jdbc.update("INSERT INTO app_sessions(PRIMARY_ID,SESSION_ID,CREATION_TIME,LAST_ACCESS_TIME,MAX_INACTIVE_INTERVAL,EXPIRY_TIME,PRINCIPAL_NAME) VALUES (UUID(),UUID(),0,0,1800,9999999999999,?)","recovery@example.test");}
    @Test void issueStoresOnlyDigestAndDoesNotChangeThePassword(){var issue=resets.issue(" RECOVERY@EXAMPLE.TEST ");assertThat(issue).isNotNull();assertThat(issue.token()).matches("[A-Za-z0-9_-]{43}");String stored=jdbc.queryForObject("SELECT token_hash FROM password_resets WHERE user_id=?",String.class,owner);assertThat(stored).hasSize(64).isNotEqualTo(issue.token());assertThat(users.findById(owner).orElseThrow().getAuthVersion()).isZero();}
    @Test void confirmationConsumesTokenRevokesEverySessionAndOldPrincipals(){session();session();var issue=resets.issue("recovery@example.test");resets.confirm(issue.token(),"Replacement-password");assertThat(passwords.matches("Replacement-password",users.findById(owner).orElseThrow().getPasswordHash())).isTrue();assertThat(users.findById(owner).orElseThrow().getAuthVersion()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_sessions",Integer.class)).isZero();code(()->resets.confirm(issue.token(),"Another-password"),"RESET_INVALID");}
    @Test void expiryIsEnforcedAtItsExactDeadline(){var issue=resets.issue("recovery@example.test");advance(900);code(()->resets.confirm(issue.token(),"Replacement-password"),"RESET_INVALID");assertThat(users.findById(owner).orElseThrow().getAuthVersion()).isZero();}
    @Test void resendInvalidatesOldTokenAndPreservesCooldown(){var first=resets.issue("recovery@example.test");assertThat(resets.issue("recovery@example.test")).isNull();advance(60);var second=resets.issue("recovery@example.test");assertThat(second.token()).isNotEqualTo(first.token());code(()->resets.confirm(first.token(),"Replacement-password"),"RESET_INVALID");resets.confirm(second.token(),"Replacement-password");assertThat(resets.issue("recovery@example.test")).isNull();}
    @Test void noMoreThanFiveMessagesPerAccountPerHourEvenAfterTokenConsumption(){for(int i=0;i<5;i++){var issue=resets.issue("recovery@example.test");assertThat(issue).isNotNull();resets.confirm(issue.token(),"Replacement-password");advance(60);}assertThat(resets.issue("recovery@example.test")).isNull();advance(3300);assertThat(resets.issue("recovery@example.test")).isNotNull();}
    @Test void ordinaryPasswordChangeInvalidatesAnIssuedLink(){var issue=resets.issue("recovery@example.test");jdbc.update("UPDATE app_users SET auth_version=auth_version+1 WHERE id=?",owner);code(()->resets.confirm(issue.token(),"Replacement-password"),"RESET_INVALID");}
    @Test void competingConfirmationsHaveOneWinner()throws Exception{var issue=resets.issue("recovery@example.test");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();try{resets.confirm(issue.token(),"Replacement-password");return true;}catch(ApiException error){assertThat(error.getCode()).isEqualTo("RESET_INVALID");return false;}}));start.countDown();int successes=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))successes++;assertThat(successes).isEqualTo(1);assertThat(users.findById(owner).orElseThrow().getAuthVersion()).isEqualTo(1);}finally{pool.shutdownNow();}}
    @Test void failedSessionDeletionRollsBackHashVersionTokenAndSessions(){session();var issue=resets.issue("recovery@example.test");var before=users.findById(owner).orElseThrow().getPasswordHash();doAnswer(call->{var value=call.callRealMethod();if(((String)call.getArgument(0)).startsWith("DELETE FROM app_sessions WHERE"))throw new IllegalStateException("Injected failure");return value;}).when(jdbc).update(anyString(),any(Object[].class));try{assertThatThrownBy(()->resets.confirm(issue.token(),"Replacement-password")).isInstanceOf(IllegalStateException.class);}finally{reset(jdbc);}assertThat(users.findById(owner).orElseThrow().getPasswordHash()).isEqualTo(before);assertThat(users.findById(owner).orElseThrow().getAuthVersion()).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_sessions",Integer.class)).isEqualTo(1);resets.confirm(issue.token(),"Replacement-password");}
    @Test void unknownAndKnownEmailsReceiveIdenticalReceiptsAndNoRawToken()throws Exception{String first=mvc.perform(post("/auth/password-reset/request").with(csrf()).contentType("application/json").content("{\"email\":\"recovery@example.test\"}")).andExpect(status().isAccepted()).andExpect(jsonPath("$.token").doesNotExist()).andReturn().getResponse().getContentAsString();String second=mvc.perform(post("/auth/password-reset/request").with(csrf()).contentType("application/json").content("{\"email\":\"unknown@example.test\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();assertThat(second).isEqualTo(first);verify(sender,timeout(5000).times(1)).send(any(SimpleMailMessage.class));}
    @Test void mailFailureRevokesTheUncertainLinkWithoutRevealingAccountPresence()throws Exception{doThrow(new MailSendException("Synthetic SMTP failure")).when(sender).send(any(SimpleMailMessage.class));mvc.perform(post("/auth/password-reset/request").with(csrf()).contentType("application/json").content("{\"email\":\"recovery@example.test\"}")).andExpect(status().isAccepted());awaitMail();assertThat(jdbc.queryForObject("SELECT token_hash IS NULL FROM password_resets WHERE user_id=?",Boolean.class,owner)).isTrue();}
    @Test void csrfMalformedTokenAndUtf8PasswordBoundsAreEnforced()throws Exception{mvc.perform(post("/auth/password-reset/request").contentType("application/json").content("{\"email\":\"recovery@example.test\"}")).andExpect(status().isForbidden());code(()->resets.confirm("bad","Replacement-password"),"RESET_INVALID");var issue=resets.issue("recovery@example.test");code(()->resets.confirm(issue.token(),"한".repeat(25)),"INVALID_REQUEST");resets.confirm(issue.token(),"한".repeat(24));}
    @Test void realResetContractsAreDocumentedWithoutMailTokens(WebApplicationContext context,RestDocumentationContextProvider docs)throws Exception{
        var config=documentationConfiguration(docs);config.operationPreprocessors().withRequestDefaults(new com.zeroziba.orderstock.docs.DocumentationSanitizer(),prettyPrint()).withResponseDefaults(new com.zeroziba.orderstock.docs.DocumentationSanitizer(),prettyPrint());
        var documented=MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).apply(config).build();
        documented.perform(get("/auth/password-reset/config")).andExpect(status().isOk()).andDo(document("password-reset-config",responseFields(fieldWithPath("enabled").description("이메일 복구 요청 사용 가능 여부"))));
        documented.perform(post("/auth/password-reset/request").with(csrf().asHeader()).contentType("application/json").content("{\"email\":\"recovery@example.test\"}")).andExpect(status().isAccepted()).andDo(document("password-reset-request",requestFields(fieldWithPath("email").description("가입 이메일. 계정 존재·한도 여부와 같은 응답")),responseFields(fieldWithPath("message").description("일반 안내. 토큰·메일 주소 없음"))));
        var captured=org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender,timeout(5000)).send(captured.capture());String token=captured.getValue().getText().split("#token=")[1].substring(0,43);
        documented.perform(post("/auth/password-reset/confirm").with(csrf().asHeader()).contentType("application/json").content("{\"token\":\""+token+"\",\"newPassword\":\"Replacement-password\"}")).andExpect(status().isNoContent()).andDo(document("password-reset-confirm",requestFields(fieldWithPath("token").description("메일 링크의 단회 토큰. 문서에서는 마스킹"),fieldWithPath("newPassword").description("8자 이상, UTF-8 72바이트 이하"))));
    }
}
