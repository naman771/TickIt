package com.tickit.controller;

import com.tickit.service.AssetStorageService;
import com.tickit.service.DeveloperAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/developer/assets")
@RequiredArgsConstructor
public class AssetController {
    private final DeveloperAccess access;
    private final AssetStorageService storage;

    @GetMapping
    public List<AssetStorageService.Asset> list() {
        access.requireDeveloper();
        return storage.list();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AssetStorageService.Asset upload(@RequestParam("file") MultipartFile file) throws IOException {
        access.requireDeveloper();
        if (file.getSize() > AssetStorageService.MAX_BYTES) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        return storage.upload(file.getBytes());
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        access.requireDeveloper();
        byte[] bytes = storage.download(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".bin\"")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(bytes);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        access.requireDeveloper();
        storage.delete(id);
    }
}
