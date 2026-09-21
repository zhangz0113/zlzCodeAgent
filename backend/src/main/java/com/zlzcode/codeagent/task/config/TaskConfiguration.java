package com.zlzcode.codeagent.task.config;

import com.zlzcode.codeagent.task.tool.TaskPlanUpdateDefinition;
import com.zlzcode.codeagent.task.tool.TaskPlanUpdateHandler;
import com.zlzcode.codeagent.tool.registry.ToolRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TaskConfiguration {

    @Bean
    ToolRegistration taskPlanUpdateRegistration(
            TaskPlanUpdateDefinition definition,
            TaskPlanUpdateHandler handler) {
        return new ToolRegistration(definition, handler, true);
    }
}
