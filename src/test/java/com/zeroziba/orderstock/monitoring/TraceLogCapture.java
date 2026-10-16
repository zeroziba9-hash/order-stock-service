package com.zeroziba.orderstock.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.zeroziba.orderstock.common.ApiExceptionHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

final class TraceLogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final List<Logger> loggers = List.of(
            (Logger) LoggerFactory.getLogger(RequestTracingFilter.class),
            (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class));
    private final Object completion = new Object();

    TraceLogCapture() { start(); loggers.forEach(logger -> logger.addAppender(this)); }
    @Override protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        synchronized(completion) { events.add(event); completion.notifyAll(); }
    }
    List<ILoggingEvent> awaitCompletion(String requestId) throws InterruptedException {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        synchronized(completion) {
            while(events.stream().noneMatch(event -> requestId.equals(event.getMDCPropertyMap().get("requestId"))
                    && event.getFormattedMessage().startsWith("request_completed "))) {
                long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(completion,remaining);
            }
            return events.stream().filter(event -> requestId.equals(event.getMDCPropertyMap().get("requestId"))).toList();
        }
    }
    @Override public void close() { loggers.forEach(logger -> logger.detachAppender(this)); stop(); }
}
