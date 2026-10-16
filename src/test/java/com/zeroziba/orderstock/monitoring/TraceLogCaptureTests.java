package com.zeroziba.orderstock.monitoring;

import static org.assertj.core.api.Assertions.*;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TraceLogCaptureTests {
    @Test void waitsForThisRequestsCompletionEvenWhenTheResponseAndAnErrorLogAlreadyExist()throws Exception{
        String id=UUID.randomUUID().toString();var pool=Executors.newSingleThreadExecutor();var started=new CountDownLatch(1);
        try(var logs=new TraceLogCapture()){
            var captured=pool.submit(()->{started.countDown();return logs.awaitCompletion(id);});assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            append(logs,id,"unexpected_failure type=Example");append(logs,UUID.randomUUID().toString(),"request_completed status=200");
            assertThat(captured.isDone()).isFalse();
            append(logs,id,"request_completed status=500");
            assertThat(captured.get(2,TimeUnit.SECONDS)).extracting(event->event.getFormattedMessage()).containsExactly("unexpected_failure type=Example","request_completed status=500");
        }finally{pool.shutdownNow();}
    }
    private void append(TraceLogCapture logs,String id,String message){
        var event=new LoggingEvent();event.setLevel(Level.INFO);event.setLoggerName(RequestTracingFilter.class.getName());event.setMessage(message);event.setMDCPropertyMap(Map.of("requestId",id));logs.doAppend(event);
    }
}
