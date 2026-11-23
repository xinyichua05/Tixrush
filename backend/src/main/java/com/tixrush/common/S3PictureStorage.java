package com.tixrush.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;

/**
 * PictureStorage on any S3-compatible service: we use Supabase Storage, but Cloudflare R2, Backblaze
 * B2 or AWS S3 itself only need different settings (tixrush.storage.* / the STORAGE_* env vars).
 *
 * Only created when STORAGE_BUCKET is set. The access keys are S3 keys that can only touch storage,
 * never the database.
 */
@Component
@ConditionalOnExpression("!'${tixrush.storage.bucket:}'.isBlank()")
public class S3PictureStorage implements PictureStorage {

    /** Each upload gets a brand-new key, so a file never changes: browsers/CDN may keep it for a year. */
    private static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;

    public S3PictureStorage(@Value("${tixrush.storage.endpoint}") String endpoint,
                            @Value("${tixrush.storage.region}") String region,
                            @Value("${tixrush.storage.access-key}") String accessKey,
                            @Value("${tixrush.storage.secret-key}") String secretKey,
                            @Value("${tixrush.storage.bucket}") String bucket,
                            @Value("${tixrush.storage.public-url}") String publicBaseUrl) {
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                // "path style": https://host/bucket/key instead of https://bucket.host/key (S3-compatible services need it)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // Newer AWS SDKs add extra checksums that some S3-compatible services don't understand.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    @Override
    public void put(String key, String contentType, byte[] data) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(key).contentType(contentType).cacheControl(CACHE_FOREVER).build(),
                RequestBody.fromBytes(data));
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + "/" + key;
    }
}
