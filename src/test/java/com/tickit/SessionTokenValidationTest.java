package com.tickit;

import com.tickit.config.ClerkTokenValidators;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionTokenValidationTest {
    static KeyPair keys;
    static NimbusJwtDecoder decoder;
    static final String ISSUER = "https://issuer.tickit.test";
    static final String ORIGIN = "https://tickit.site";
    @BeforeAll static void setup() throws Exception {
        keys = generate();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(ClerkTokenValidators.create(ISSUER, List.of(ORIGIN)));
    }
    static KeyPair generate() throws Exception {var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair();}
    static String token(KeyPair key, String issuer, String origin, Instant expiry, String status) {
        var jwk = new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey) key.getPublic()).privateKey((RSAPrivateKey) key.getPrivate()).keyID("test-key").build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("user_valid")
                .issuedAt(expiry.minusSeconds(300)).expiresAt(expiry).claim("azp", origin).claim("sts", status).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
    @Test void validSignedSessionIsAccepted() {assertEquals("user_valid", decoder.decode(token(keys,ISSUER,ORIGIN,Instant.now().plusSeconds(120),"active")).getSubject());}
    @Test void wrongSignatureIsRejected() throws Exception {assertThrows(JwtException.class, () -> decoder.decode(token(generate(),ISSUER,ORIGIN,Instant.now().plusSeconds(120),"active")));}
    @Test void wrongIssuerIsRejected() {assertThrows(JwtException.class, () -> decoder.decode(token(keys,"https://other.test",ORIGIN,Instant.now().plusSeconds(120),"active")));}
    @Test void expiredSessionIsRejected() {assertThrows(JwtException.class, () -> decoder.decode(token(keys,ISSUER,ORIGIN,Instant.now().minusSeconds(300),"active")));}
    @Test void untrustedOriginIsRejected() {assertThrows(JwtException.class, () -> decoder.decode(token(keys,ISSUER,"https://other.test",Instant.now().plusSeconds(120),"active")));}
    @Test void pendingSessionIsRejected() {assertThrows(JwtException.class, () -> decoder.decode(token(keys,ISSUER,ORIGIN,Instant.now().plusSeconds(120),"pending")));}
}
