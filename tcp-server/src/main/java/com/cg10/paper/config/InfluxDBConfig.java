package com.cg10.paper.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Data
@Configuration
@ConfigurationProperties(prefix = "influxdb")
public class InfluxDBConfig {

    private String url;
    private String token;
    private String org;
    private String bucket;
    private int batchSize = 500;
    private int flushIntervalMs = 500;

    @Bean(destroyMethod = "close")
    public InfluxDBClient influxDBClient() {
        return InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
    }

    @Bean(destroyMethod = "close")
    public WriteApi influxWriteApi(InfluxDBClient client) {
        WriteOptions options = WriteOptions.builder()
                .batchSize(batchSize)
                .flushInterval(flushIntervalMs)
                .jitterInterval(200)
                .retryInterval(1000)
                .maxRetries(5)
                .maxRetryDelay(5_000)
                .maxBufferSize(10_000)
                .batchActions(1000)
                .build();
        return client.getWriteApi(options);
    }
}
