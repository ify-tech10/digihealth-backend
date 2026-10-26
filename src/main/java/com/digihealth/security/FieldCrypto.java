package com.digihealth.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.digihealth.config.AppProperties;

/*
 * Encrypts sensitive identifiers (BVN, NIN, bank account numbers) before they
 * reach the database, with AES-256-GCM and the DATA_ENCRYPTION_KEY.
 * Stored form: base64( 12-byte IV + ciphertext + tag ).
 */
@Component
public class FieldCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public FieldCrypto(AppProperties props) {
        String raw = props.security() == null ? null : props.security().dataKey();
        byte[] bytes;
        try {
            bytes = raw == null ? new byte[0] : Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            bytes = new byte[0];
        }
        if (bytes.length != 32) {
            throw new IllegalStateException(
                "DATA_ENCRYPTION_KEY must be base64 of 32 random bytes. Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed - was DATA_ENCRYPTION_KEY changed?", e);
        }
    }
}
