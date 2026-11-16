package ai.voicecraft.audio;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/**
 * Magazyn S3 (AWS lub zgodny: Cloudflare R2, Backblaze B2, Hetzner, MinIO — przez endpoint + pathStyle).
 *
 * <p>Cloudflare R2: region {@code auto}, endpoint {@code https://<ID_KONTA>.r2.cloudflarestorage.com}
 * (bucket w jurysdykcji UE: {@code https://<ID_KONTA>.eu.r2.cloudflarestorage.com}). Sumy kontrolne
 * żądań są wysyłane tylko tam, gdzie wymaga ich protokół — dostawcy zgodni z S3 nie zawsze obsługują
 * domyślne sumy CRC nowszych wersji AWS SDK.
 */
public class S3AudioStorage implements AudioStorage {
    private static final Logger log = LoggerFactory.getLogger(S3AudioStorage.class);
    private static final String PREFIX = "audio/";

    private final S3Client s3;
    private final String bucket;

    public S3AudioStorage(AppProperties.Storage.S3 cfg) {
        boolean r2 = cfg.endpoint() != null && cfg.endpoint().contains(".r2.cloudflarestorage.com");
        // R2 przyjmuje tylko region „auto” — zwykła nazwa regionu AWS dawałaby błędny podpis żądań
        String region = r2 && (cfg.region() == null || cfg.region().isBlank() || cfg.region().startsWith("eu-") || cfg.region().startsWith("us-"))
                ? "auto" : cfg.region();
        var builder = S3Client.builder()
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .forcePathStyle(cfg.pathStyle())
                .requestChecksumCalculation(software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED);
        if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) builder.endpointOverride(URI.create(cfg.endpoint()));
        if (cfg.accessKey() != null && !cfg.accessKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        this.s3 = builder.build();
        this.bucket = cfg.bucket();
        ensureBucket();
        log.info("Magazyn S3 gotowy: bucket {}, region {}{}", bucket, region, r2 ? " (Cloudflare R2)" : "");
    }

    private void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            log.warn("Bucket {} nie istnieje — tworzę go.", bucket);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } else if (e.statusCode() == 403) {
                // Klucz ograniczony do obiektów jednego bucketa (zalecane w R2) nie może sprawdzić bucketa —
                // zakładamy, że istnieje; ewentualny błąd wyjdzie przy pierwszym zapisie
                log.warn("Brak uprawnień do sprawdzenia bucketa {} (403) — zakładam, że istnieje.", bucket);
            } else {
                throw e;
            }
        }
    }

    @Override
    public String save(String prefix, byte[] data) {
        String key = AudioStorage.newKey(prefix);
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(PREFIX + key).contentType("audio/wav").build(),
                RequestBody.fromBytes(data));
        return key;
    }

    @Override
    public byte[] read(String key) {
        if (!AudioStorage.validKey(key)) throw new IllegalArgumentException("Niepoprawny klucz pliku.");
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(PREFIX + key).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw ApiException.notFound("Plik audio");
        }
    }

    @Override
    public boolean exists(String key) {
        if (!AudioStorage.validKey(key)) return false;
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(PREFIX + key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    @Override
    public void forEachObject(java.util.function.Consumer<StoredObject> action) {
        var req = ListObjectsV2Request.builder().bucket(bucket).prefix(PREFIX).build();
        for (S3Object o : s3.listObjectsV2Paginator(req).contents()) {
            String key = o.key().substring(PREFIX.length());
            if (AudioStorage.validKey(key)) action.accept(new StoredObject(key, o.lastModified(), o.size()));
        }
    }

    @Override
    public void delete(String key) {
        if (AudioStorage.validKey(key)) s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(PREFIX + key).build());
    }
}
