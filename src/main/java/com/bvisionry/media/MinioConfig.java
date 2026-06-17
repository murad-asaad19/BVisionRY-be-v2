package com.bvisionry.media;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;

/**
 * Registers two {@link MinioClient} beans:
 *
 * <ul>
 *   <li>{@code minioInternal} — uses {@code bvisionry.minio.internal-endpoint}; performs
 *       all server-side I/O operations (putObject, bucketExists, makeBucket).</li>
 *   <li>{@code minioPublic}   — uses {@code bvisionry.minio.public-endpoint}; used
 *       <em>only</em> to compute presigned GET URLs the browser can reach. Because
 *       {@code getPresignedObjectUrl} is computed locally (HMAC signature), the
 *       public host never needs to be reachable at bean-creation time.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MinioConfig {

    @Bean
    @Qualifier("minioInternal")
    public MinioClient minioInternal(MediaProperties props) {
        return MinioClient.builder()
                .endpoint(props.getInternalEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .region(props.getRegion())
                .build();
    }

    @Bean
    @Qualifier("minioPublic")
    public MinioClient minioPublic(MediaProperties props) {
        // region is set explicitly so getPresignedObjectUrl signs locally and
        // never makes a GetBucketLocation call to the (browser-facing, often
        // backend-unreachable) public endpoint.
        return MinioClient.builder()
                .endpoint(props.getPublicEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .region(props.getRegion())
                .build();
    }
}
