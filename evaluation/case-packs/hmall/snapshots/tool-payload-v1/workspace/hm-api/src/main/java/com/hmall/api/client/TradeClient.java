package com.hmall.api.client;

import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.api.dto.OrderFormDTO;
import com.hmall.api.vo.OrderVO;
import feign.Param;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(value = "trade-service", configuration = DefaultFeignConfig.class)
public interface TradeClient {

    @GetMapping("/orders/{id}")
    public OrderVO queryOrderById(@Param("订单id")@PathVariable("id") Long orderId);

    @PostMapping("/orders")
    public Long createOrder(@RequestBody OrderFormDTO orderFormDTO);

    @ApiImplicitParam(name = "orderId", value = "订单id", paramType = "path")
    @PutMapping("/orders/{orderId}")
    public void markOrderPaySuccess(@PathVariable("orderId") Long orderId);
}
