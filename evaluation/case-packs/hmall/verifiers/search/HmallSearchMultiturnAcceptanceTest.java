package com.hmall.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Background: the last turn must retain the stock requirement introduced before the sales requirement.
 * Intent: inherit the same stock behavior checks and add combined sales checks, avoiding a second definition of compatibility.
 * Constraint: the runner must execute inherited checks and the original price suite; testing only the newest parameter hides forgetting.
 */
class HmallSearchMultiturnAcceptanceTest extends HmallStockFilterAcceptanceTest {
    @Test void M1_inclusiveSold() throws Exception {
        matches(query("minSold", "10"), 102, 103, 104, 105);
    }
    @Test void M2_bothParameters() throws Exception {
        matches(query("onlyInStock", "true", "minSold", "20"), 103);
    }
    @Test void M3_soldWithoutStock() throws Exception {
        matches(query("minSold", "20"), 103, 104);
    }
    @Test void M4_zeroSold() throws Exception {
        matches(query("minSold", "0", "onlyInStock", "false"), 101, 102, 103, 104, 105);
    }
    @Test void M5_combinedOriginalFilters() throws Exception {
        matches(query("minSold", "10", "onlyInStock", "false", "brand", "华星", "minPrice", "1000", "maxPrice", "2000"), 102, 105);
    }
    @Test void M6_salesPagination() throws Exception {
        JsonNode first = query("minSold", "10", "pageSize", "2", "pageNo", "1");
        JsonNode second = query("minSold", "10", "pageSize", "2", "pageNo", "2");
        assertEquals(Arrays.asList(105L, 104L), ids(first)); assertEquals(Arrays.asList(103L, 102L), ids(second));
        page(first, 4, 2); page(second, 4, 2);
    }
    @Test void M7_combinedExplicitOrder() throws Exception {
        JsonNode result = query("minSold", "10", "onlyInStock", "true", "sortBy", "id", "isAsc", "true", "pageSize", "2");
        assertEquals(Arrays.asList(102L, 103L), ids(result)); page(result, 3, 2);
    }
}
