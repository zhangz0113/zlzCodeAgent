package com.hmall.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(PriceFilterTestConfiguration.class)
@WebAppConfiguration
class HmallStockFilterAcceptanceTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    @Test void S1_onlyPositiveStock() throws Exception {
        matches(query("onlyInStock", "true"), 102, 103, 105);
    }
    @Test void S2_falseCompatible() throws Exception {
        matches(query("onlyInStock", "false"), 101, 102, 103, 104, 105);
    }
    @Test void S3_absentCompatible() throws Exception {
        matches(query(), 101, 102, 103, 104, 105);
    }
    @Test void S4_combinedFilters() throws Exception {
        matches(query("onlyInStock", "true", "key", "手机", "brand", "华星", "category", "手机", "minPrice", "1000", "maxPrice", "2000"), 102);
    }
    @Test void S5_filteredPagination() throws Exception {
        JsonNode first = query("onlyInStock", "true", "pageSize", "2", "pageNo", "1");
        JsonNode second = query("onlyInStock", "true", "pageSize", "2", "pageNo", "2");
        assertEquals(Arrays.asList(105L, 103L), ids(first));
        assertEquals(Arrays.asList(102L), ids(second));
        page(first, 3, 2); page(second, 3, 2);
    }
    @Test void S6_explicitOrder() throws Exception {
        JsonNode result = query("onlyInStock", "true", "sortBy", "id", "isAsc", "true", "pageSize", "2");
        assertEquals(Arrays.asList(102L, 103L), ids(result)); page(result, 3, 2);
    }

    protected JsonNode query(String... args) throws Exception {
        MockHttpServletRequestBuilder request = get("/search/list");
        for (int i = 0; i < args.length; i += 2) request.param(args[i], args[i + 1]);
        JsonNode result = mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertTrue(result.get("total").isTextual()); assertTrue(result.get("pages").isTextual());
        assertTrue(result.get("list").isArray());
        for (JsonNode item : result.get("list")) { assertTrue(item.get("id").isTextual()); assertEquals(1, item.get("status").asInt()); }
        return result;
    }
    protected List<Long> ids(JsonNode result) {
        List<Long> values = new ArrayList<>(); result.get("list").forEach(item -> values.add(item.get("id").asLong())); return values;
    }
    protected void page(JsonNode result, long total, long pages) {
        assertEquals(total, result.get("total").asLong()); assertEquals(pages, result.get("pages").asLong());
    }
    protected void matches(JsonNode result, long... expected) {
        List<Long> actual = ids(result); Collections.sort(actual);
        List<Long> wanted = new ArrayList<>(); for (long id : expected) wanted.add(id);
        assertEquals(wanted, actual); page(result, expected.length, 1);
    }
}
