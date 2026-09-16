package com.zlzcode.codeagent.context.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContextProperties.class)
public class ContextConfiguration {
}
