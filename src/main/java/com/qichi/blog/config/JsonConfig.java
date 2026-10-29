package com.qichi.blog.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Ids leave this API as JSON strings.
 *
 * <p>MyBatis-Plus hands out 19 digit snowflake ids and JSON.parse turns them into IEEE doubles:
 * 2102707479791927298 comes back as 2102707479791927300, so every id the browser echoes back —
 * a comment's parentId, a post id in an edit link — misses its row. Only the boxed Long is
 * rewritten; primitive long fields (counts, totals, page numbers) stay numbers.
 */
@Configuration
public class JsonConfig {

    @Bean
    public JsonMapperBuilderCustomizer longIdsAsStrings() {
        SimpleModule module = new SimpleModule("long-ids-as-strings");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        return builder -> builder.addModule(module);
    }
}
