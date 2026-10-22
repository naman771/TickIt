package com.tickit.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import java.net.URI;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true")
public class NeonStorageConfig {
    @Bean(destroyMethod = "close")
    S3Client neonStorage(Environment env) {
        URI endpoint = URI.create(required(env, "AWS_ENDPOINT_URL_S3"));
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null) {
            throw new IllegalArgumentException("Storage endpoint must be an HTTPS service URL");
        }
        return S3Client.builder().endpointOverride(endpoint)
                .region(Region.of(required(env, "AWS_REGION")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required(env, "AWS_ACCESS_KEY_ID"), required(env, "AWS_SECRET_ACCESS_KEY"))))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(20)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false).build())
                .build();
    }

    private static String required(Environment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required for storage");
        return value;
    }
}
