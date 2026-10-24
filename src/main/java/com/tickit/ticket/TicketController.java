package com.tickit.ticket;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class TicketController {
  private final TicketService tickets;
  private final TicketAccess access;
  private final java.util.concurrent.Semaphore decoder = new java.util.concurrent.Semaphore(1);

  public record ScanInput(@NotBlank @Size(max = 100) String credential) {}

  @GetMapping("/api/tickets/{id}")
  public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tickets.detail(id));
  }

  @GetMapping(value = "/api/tickets/{id}/qr", produces = "image/png")
  public ResponseEntity<byte[]> qr(@PathVariable UUID id) throws Exception {
    var data = tickets.detail(id);
    var value = (String) data.get("credential");
    if (value == null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket is not valid for entry");
    var matrix =
        new QRCodeWriter()
            .encode(value, BarcodeFormat.QR_CODE, 360, 360, Map.of(EncodeHintType.MARGIN, 4));
    var image = new BufferedImage(360, 360, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 360; y++)
      for (int x = 0; x < 360; x++) image.setRGB(x, y, matrix.get(x, y) ? 0x112c25 : 0xffffff);
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "PNG", out);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition", "inline; filename=\"tickit-" + id + ".png\"")
        .body(out.toByteArray());
  }

  @PostMapping("/api/organizer/events/{event}/check-in")
  public TicketService.Scan scan(@PathVariable long event, @Valid @RequestBody ScanInput input) {
    return tickets.scan(event, input.credential());
  }

  /** Same decoder handles an uploaded ticket image and bounded frames from mobile cameras. */
  @PostMapping(
      value = "/api/organizer/events/{event}/check-in/image",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public TicketService.Scan image(@PathVariable long event, @RequestPart("file") MultipartFile file)
      throws Exception {
    access.scanner(event);
    if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a QR image up to 2 MB");
    // Bound simultaneous raster allocations on the small free-host heap.
    if (!decoder.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Scanner is busy. Retry this ticket.");
    try {
      // Inspect dimensions before allocating the raster, so a compressed image cannot exhaust
      // memory.
      try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext())
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a PNG or JPEG QR image");
        var reader = readers.next();
        try {
          reader.setInput(stream, true, true);
          String format = reader.getFormatName();
          int w = reader.getWidth(0), h = reader.getHeight(0);
          if (!(format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"))
              || w < 1
              || h < 1
              || w > 2048
              || h > 2048)
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Use a PNG or JPEG image no larger than 2048 × 2048");
          var image = reader.read(0);
          int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
          try {
            var result =
                new MultiFormatReader()
                    .decode(
                        new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels))),
                        Map.of(
                            DecodeHintType.POSSIBLE_FORMATS,
                            List.of(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER,
                            true));
            return tickets.scan(event, result.getText());
          } catch (NotFoundException ex) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "No readable QR ticket found");
          }
        } finally {
          reader.dispose();
        }
      }
    } catch (java.io.IOException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The QR image could not be read");
    } finally {
      decoder.release();
    }
  }
}
