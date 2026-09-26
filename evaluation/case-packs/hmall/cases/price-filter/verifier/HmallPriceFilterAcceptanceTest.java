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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(PriceFilterTestConfiguration.class)
@WebAppConfiguration
class HmallPriceFilterAcceptanceTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    @Test void V1_lowerOnly() throws Exception {
        matches(query("minPrice", "2000"), 102, 103, 104, 105);
    }
    @Test void V2_upperOnly() throws Exception {
        matches(query("maxPrice", "2000"), 101, 102, 104, 105);
    }
    @Test void V3_inclusiveBounds() throws Exception {
        matches(query("minPrice", "1000", "maxPrice", "2000"), 101, 102, 104, 105);
    }
    @Test void V4_noPriceLimit() throws Exception {
        matches(query(), 101, 102, 103, 104, 105);
    }
    @Test void V5_keyword() throws Exception {
        matches(query("key", "耳机", "maxPrice", "2000"), 105);
    }
    @Test void V6_brand() throws Exception {
        matches(query("brand", "远山", "minPrice", "2000"), 104);
    }
    @Test void V7_category() throws Exception {
        matches(query("category", "手机", "maxPrice", "2000"), 101, 102, 104);
    }
    @Test void V8_combined() throws Exception {
        matches(query("key", "手机", "brand", "华星", "category", "手机", "minPrice", "1000", "maxPrice", "2000"), 101, 102);
    }
    @Test void V9_defaultPagination() throws Exception {
        JsonNode first = query("minPrice", "2000", "pageSize", "2", "pageNo", "1");
        JsonNode second = query("minPrice", "2000", "pageSize", "2", "pageNo", "2");
        assertEquals(Arrays.asList(105L, 104L), ids(first));
        assertEquals(Arrays.asList(103L, 102L), ids(second));
        for (JsonNode page : Arrays.asList(first, second)) {
            assertEquals(4, page.get("total").asLong());
            assertEquals(2, page.get("pages").asLong());
        }
    }
    @Test void V10_explicitOrder() throws Exception {
        JsonNode result = query("minPrice", "2000", "sortBy", "id", "isAsc", "true", "pageSize", "2", "pageNo", "1");
        assertEquals(Arrays.asList(102L, 103L), ids(result));
        assertEquals(4, result.get("total").asLong());
        assertEquals(2, result.get("pages").asLong());
    }

    private JsonNode query(String... parameters) throws Exception {
        MockHttpServletRequestBuilder request = get("/search/list");
        for (int i = 0; i < parameters.length; i += 2) request.param(parameters[i], parameters[i + 1]);
        byte[] content = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode result = mapper.readTree(content);
        assertTrue(result.get("total").isTextual(), "Preserve hmall Long serialization");
        assertTrue(result.get("pages").isTextual(), "Preserve hmall Long serialization");
        assertTrue(result.get("list").isArray());
        for (JsonNode item : result.get("list")) {
            assertTrue(item.get("id").isTextual());
            assertEquals(1, item.get("status").asInt());
        }
        return result;
    }

    private List<Long> ids(JsonNode result) {
        List<Long> ids = new ArrayList<>();
        result.get("list").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private void matches(JsonNode result, long... expected) {
        List<Long> actual = ids(result);
        Collections.sort(actual);
        List<Long> wanted = new ArrayList<>();
        for (long id : expected) wanted.add(id);
        assertEquals(wanted, actual);
        assertEquals(expected.length, result.get("total").asLong());
        assertEquals(1, result.get("pages").asLong());
    }
}
