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

    TraceLogCapture() { start(); loggers.forEach(logger -> logger.addAppender(this)); }
    @Override protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        events.add(event);
    }
    @Override public void close() { loggers.forEach(logger -> logger.detachAppender(this)); stop(); }
}
