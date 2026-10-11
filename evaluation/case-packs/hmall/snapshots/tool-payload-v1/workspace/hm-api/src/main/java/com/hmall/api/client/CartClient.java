package com.hmall.api.client;

import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.api.dto.CartFormDTO;
import com.hmall.api.po.Cart;
import com.hmall.api.vo.CartVO;
import feign.Param;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@FeignClient(value = "cart-service", configuration = DefaultFeignConfig.class)
public interface CartClient {

    @PostMapping("/carts")
    public void addItem2Cart(@Valid @RequestBody CartFormDTO cartFormDTO);

    @PutMapping("/carts")
    public void updateCart(@RequestBody Cart cart);

    @DeleteMapping("/carts/{id}")
    public void deleteCartItem(@Param("购物车条目id")@PathVariable("id") Long id);

    @GetMapping("/carts")
    public List<CartVO> queryMyCarts();

    @DeleteMapping("/carts")
    public void deleteCartItemByIds(@RequestParam("ids") Collection<Long> ids);

}
