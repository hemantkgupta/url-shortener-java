package com.urlshortener.analyticsapi.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
public class ClickHouseConfig {

    @Value("${app.clickhouse.url}")
    private String url;

    @Value("${app.clickhouse.username:default}")
    private String username;

    @Value("${app.clickhouse.password:}")
    private String password;

    @Bean
    public DataSource clickHouseDataSource() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        return new ClickHouseDataSource(url, props);
    }

    @Bean
    public JdbcTemplate clickHouseJdbcTemplate(DataSource clickHouseDataSource) {
        return new JdbcTemplate(clickHouseDataSource);
    }
}
