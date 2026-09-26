package com.hmall.item.es;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.common.utils.CollUtils;
import com.hmall.item.domain.po.Item;
import com.hmall.item.domain.po.ItemDoc;
import com.hmall.item.service.IItemService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@SpringBootTest(properties = "spring.profiles.active=local")
public class DocumentTest {

    private RestHighLevelClient client;

    @Autowired
    private IItemService itemService;

    @BeforeEach
    void setUp(){
        client = new RestHighLevelClient(RestClient.builder(
                HttpHost.create("http://192.168.75.128:9200")
        ));
    }

    @AfterEach
    void tearDown(){
        try {
            client.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testAddDocument() throws IOException {
        Item item = itemService.getById(100002644680L);
        ItemDoc itemDoc = BeanUtil.copyProperties(item, ItemDoc.class);
        String doc = JSONUtil.toJsonStr(itemDoc);

        IndexRequest request = new IndexRequest("items").id(itemDoc.getId());
        request.source(doc, XContentType.JSON);
        client.index(request, RequestOptions.DEFAULT);
    }

    @Test
    void testGetDocumentById() throws IOException {
        GetRequest request = new GetRequest("items").id("100002644680");
        GetResponse response = client.get(request, RequestOptions.DEFAULT);
        String json = response.getSourceAsString();
        ItemDoc itemDoc = JSONUtil.toBean(json, ItemDoc.class);
        System.out.println("itemDoc = " + itemDoc);
    }

    @Test
    void testDeleteDocument() throws IOException {
        DeleteRequest request = new DeleteRequest("items").id("100002644680");
        client.delete(request, RequestOptions.DEFAULT);
    }

    @Test
    void testUpdateDocument() throws IOException {
        UpdateRequest request = new UpdateRequest("items", "100002644680");
        request.doc(
                "price", 58800,
                "commentCount", 1
        );
        client.update(request, RequestOptions.DEFAULT);
    }

    @Test
    void testLoadItemDocs() throws IOException {
        int pageNo = 1;
        int size = 1000;
        while(true){
            Page<Item> page = itemService.lambdaQuery().eq(Item::getStatus, 1).page(new Page<Item>(pageNo, size));

            List<Item> items = page.getRecords();
            if(CollUtils.isEmpty(items)){
                return;
            }

            log.info("加载第{}页数据，共{}条", pageNo, items.size());

            BulkRequest request = new BulkRequest("items");

            for (Item item : items) {
                ItemDoc itemDoc = BeanUtil.copyProperties(item, ItemDoc.class);
                request.add(new IndexRequest("items")
                                .id(itemDoc.getId())
                                .source(JSONUtil.toJsonStr(itemDoc), XContentType.JSON));
            }

            client.bulk(request, RequestOptions.DEFAULT);

            pageNo ++;
        }
    }

    @Test
    void testMatchAll() throws IOException {
        SearchRequest request = new SearchRequest("items");
        request.source().query(QueryBuilders.matchAllQuery());
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        handleResponse(response);
    }

    @Test
    void testMatch() throws IOException {
        SearchRequest request = new SearchRequest("items");
        request.source().query(QueryBuilders.matchQuery("name", "脱脂牛奶"));
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        handleResponse(response);
    }

    @Test
    void testMultiMatch() throws IOException {
        SearchRequest request = new SearchRequest("items");
        request.source().query(QueryBuilders.multiMatchQuery("脱脂牛奶", "name", "category"));
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        handleResponse(response);
    }

    @Test
    void testBool() throws IOException {
        SearchRequest request = new SearchRequest("items");
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        bool.must(QueryBuilders.matchQuery("name", "脱脂牛奶"));
        bool.filter(QueryBuilders.termQuery("brand", "德亚"));
        request.source().query(bool);
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        handleResponse(response);
    }

    @Test
    void testPageAndSort() throws IOException {
        int pageNo = 1, pageSize = 5;
        SearchRequest request = new SearchRequest("items");
        request.source().query(QueryBuilders.matchQuery("name", "脱脂牛奶"));
        request.source().sort("price", SortOrder.ASC);
        request.source().from((pageNo - 1) * pageSize).size(pageSize);
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        handleResponse(response);
    }

    private void handleResponse(SearchResponse response){
        SearchHits searchHits = response.getHits();
        long total = searchHits.getTotalHits().value;
        System.out.println("共搜索到" + total + "条数据");
        SearchHit[] hits = searchHits.getHits();
        for (SearchHit hit : hits) {
            String source = hit.getSourceAsString();
            ItemDoc item = JSONUtil.toBean(source, ItemDoc.class);
            System.out.println(item);
        }
    }

    @Test
    void testHighlight() throws IOException {
        // 1.创建Request
        SearchRequest request = new SearchRequest("items");
        // 2.组织请求参数
        // 2.1.query条件
        request.source().query(QueryBuilders.matchQuery("name", "脱脂牛奶"));
        // 2.2.高亮条件
        request.source().highlighter(
                SearchSourceBuilder.highlight()
                        .field("name")
                        .preTags("<em>")
                        .postTags("</em>")
        );
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析响应
        handleResponse2(response);
    }

    private void handleResponse2(SearchResponse response) {
        SearchHits searchHits = response.getHits();
        // 1.获取总条数
        long total = searchHits.getTotalHits().value;
        System.out.println("共搜索到" + total + "条数据");
        // 2.遍历结果数组
        SearchHit[] hits = searchHits.getHits();
        for (SearchHit hit : hits) {
            // 3.得到_source，也就是原始json文档
            String source = hit.getSourceAsString();
            // 4.反序列化
            ItemDoc item = JSONUtil.toBean(source, ItemDoc.class);
            // 5.获取高亮结果
            Map<String, HighlightField> hfs = hit.getHighlightFields();
            if (CollUtils.isNotEmpty(hfs)) {
                // 5.1.有高亮结果，获取name的高亮结果
                HighlightField hf = hfs.get("name");
                if (hf != null) {
                    // 5.2.获取第一个高亮结果片段，就是商品名称的高亮值
                    String hfName = hf.getFragments()[0].string();
                    item.setName(hfName);
                }
            }
            System.out.println(item);
        }
    }
}
