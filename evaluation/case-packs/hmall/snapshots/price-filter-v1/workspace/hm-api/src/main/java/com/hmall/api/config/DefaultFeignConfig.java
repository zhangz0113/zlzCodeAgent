package com.hmall.api.config;

import com.hmall.api.client.fallback.ItemClientFallBack;
import com.hmall.common.utils.UserContext;
import feign.Logger;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;

public class DefaultFeignConfig {

    @Bean
    public Logger.Level feignLogLevel(){
        return Logger.Level.NONE;
    }

    @Bean
    public RequestInterceptor userInfoRequestInterceptor(){
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate requestTemplate) {
                // 获取登录用户
                Long userId = UserContext.getUser();
                if(userId == null){
                    return;
                }
                requestTemplate.header("user-info", userId.toString());
            }
        };
    }

    @Bean
    public ItemClientFallBack itemClientFallBack(){
        return new ItemClientFallBack();
    }
}
