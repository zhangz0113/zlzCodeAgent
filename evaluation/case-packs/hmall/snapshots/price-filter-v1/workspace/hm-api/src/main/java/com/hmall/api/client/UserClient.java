package com.hmall.api.client;

import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.api.dto.LoginFormDTO;
import com.hmall.api.vo.UserLoginVO;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "user-service", configuration = DefaultFeignConfig.class)
public interface UserClient {

    @PostMapping("/users/login")
    public UserLoginVO login(@RequestBody @Validated LoginFormDTO loginFormDTO);


    @ApiImplicitParams({
            @ApiImplicitParam(name = "pw", value = "支付密码"),
            @ApiImplicitParam(name = "amount", value = "支付金额")
    })
    @PutMapping("/users/money/deduct")
    public void deductMoney(@RequestParam("pw") String pw, @RequestParam("amount") Integer amount);
}
