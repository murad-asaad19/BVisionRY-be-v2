package com.bvisionry.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;

/**
 * Registers a Jackson module that HTML-encodes all String values in JSON responses
 * to prevent reflected XSS via API.
 */
@Configuration
public class XssProtectionConfig {

    @Bean
    public SimpleModule xssProtectionModule() {
        SimpleModule module = new SimpleModule("XssProtection");
        module.addSerializer(String.class, new XssStringSerializer());
        return module;
    }

    static class XssStringSerializer extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(HtmlUtils.htmlEscape(value));
        }
    }
}
