package com.bvisionry.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Carries the {@link com.bvisionry.common.web.RequestCorrelationFilter} X-Request-Id
 * (and any future MDC keys) from the dispatching thread onto pooled async threads, so
 * the AI evaluation pipeline logs correlate with the originating request; clearing in
 * {@code finally} prevents bleed between pooled tasks.
 */
public final class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
