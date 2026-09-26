package com.hmall.evaluation;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmall.common.config.JsonConfig;
import com.hmall.common.config.MyBatisConfig;
import com.hmall.item.controller.SearchController;
import com.hmall.item.service.impl.ItemServiceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;

import javax.sql.DataSource;

/*
 * Background: this case evaluates real search SQL without registration/configuration servers.
 * Intent: explicitly assemble the existing search, pagination and JSON behavior with a local database.
 * Constraint: do not mock the query or scan the complete application; either hides defects or starts unrelated services.
 */
@SpringBootConfiguration
@Import({SearchController.class, ItemServiceImpl.class, MyBatisConfig.class, JsonConfig.class})
@ImportAutoConfiguration({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class})
@MapperScan("com.hmall.item.mapper")
public class PriceFilterTestConfiguration {
    @Bean
    public DataSource dataSource() {
        return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("pricefilter;MODE=MySQL;DB_CLOSE_DELAY=-1")
                .setScriptEncoding("UTF-8")
                .addScript("schema.sql").addScript("data.sql").build();
    }

    @Bean
    public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
            DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPlugins(interceptor);
        return factory.getObject();
    }
}
