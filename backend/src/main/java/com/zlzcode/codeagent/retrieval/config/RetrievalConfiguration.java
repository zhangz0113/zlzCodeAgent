package com.zlzcode.codeagent.retrieval.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalConfiguration {
    @Bean
    com.zlzcode.codeagent.tool.registry.ToolRegistration repositorySearchRegistration(
            com.zlzcode.codeagent.retrieval.definition.RepositorySearchToolDefinition definition,
            com.zlzcode.codeagent.retrieval.handler.RepositorySearchToolHandler handler, RetrievalProperties properties) {
        return new com.zlzcode.codeagent.tool.registry.ToolRegistration(definition, handler, properties.enabled());
    }
    @Bean(destroyMethod = "dispose")
    Scheduler retrievalScheduler() { return Schedulers.newBoundedElastic(4, 64, "retrieval"); }
    @Bean
    WebClient retrievalQdrantWebClient(WebClient.Builder builder) {
        return builder.codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
    }
}
