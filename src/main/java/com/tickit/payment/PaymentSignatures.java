package com.tickit.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PaymentSignatures {
  private PaymentSignatures() {}

  public static boolean valid(byte[] body, String supplied, String secret) {
    if (supplied == null || !supplied.matches("[0-9a-fA-F]{64}") || secret.isBlank()) return false;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(supplied));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
