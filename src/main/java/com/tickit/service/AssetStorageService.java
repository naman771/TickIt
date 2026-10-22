package com.tickit.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.exception.SdkException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class AssetStorageService {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String PREFIX = "workspace/";
    private final ObjectProvider<S3Client> clients;
    private final String bucket;

    public AssetStorageService(ObjectProvider<S3Client> clients, @Value("${app.storage.bucket:assets}") String bucket) {
        this.clients = clients;
        this.bucket = bucket;
    }

    public boolean configured() { return clients.getIfAvailable() != null; }

    public boolean healthy() {
        if (!configured()) return false;
        try { client().headBucket(b -> b.bucket(bucket)); return true; }
        catch (SdkException ignored) { return false; }
    }

    public List<Asset> list() {
        try {
            return client().listObjectsV2(b -> b.bucket(bucket).prefix(PREFIX).maxKeys(100))
                    .contents().stream().map(o -> new Asset(o.key().substring(PREFIX.length()), o.size())).toList();
        } catch (SdkException ignored) { throw unavailable(); }
    }

    public Asset upload(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a file between 1 byte and 5 MB");
        }
        String id = UUID.randomUUID().toString();
        try {
            client().putObject(b -> b.bucket(bucket).key(PREFIX + id)
                    .contentType("application/octet-stream").cacheControl("private, no-store"), RequestBody.fromBytes(bytes));
            return new Asset(id, bytes.length);
        } catch (SdkException ignored) { throw unavailable(); }
    }

    public byte[] download(String id) {
        String key = key(id);
        try (var stream = client().getObject(b -> b.bucket(bucket).key(key))) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            return bytes;
        } catch (NoSuchKeyException ignored) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
        } catch (SdkException | IOException ignored) { throw unavailable(); }
    }

    public void delete(String id) {
        String key = key(id);
        try { client().deleteObject(b -> b.bucket(bucket).key(key)); }
        catch (SdkException ignored) { throw unavailable(); }
    }

    private S3Client client() {
        S3Client client = clients.getIfAvailable();
        if (client == null) throw unavailable();
        return client;
    }

    private static String key(String id) {
        if (!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid asset ID");
        }
        return PREFIX + id;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Asset storage is unavailable");
    }

    public record Asset(String id, long size) { }
}
