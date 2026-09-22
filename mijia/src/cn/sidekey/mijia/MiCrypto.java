// SPDX-License-Identifier: GPL-3.0-or-later
// 根据 mijia-api miutils.py 移植；原始加密实现 Copyright (C) 2020 Sammy Svensson, MIT。
package cn.sidekey.mijia;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class MiCrypto {
    static String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    static byte[] decode(String text) { return Base64.getDecoder().decode(text); }
    static String nonce() {
        byte[] bytes = new byte[12]; new SecureRandom().nextBytes(bytes);
        ByteBuffer.wrap(bytes).putInt(8, (int) (System.currentTimeMillis() / 60000));
        return b64(bytes);
    }
    static String signedNonce(String secret, String nonce) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(decode(secret)); hash.update(decode(nonce)); return b64(hash.digest());
    }
    static String signature(String uri, String signed, Map<String, String> params) throws Exception {
        StringBuilder text = new StringBuilder("POST&").append(uri);
        for (Map.Entry<String, String> entry : params.entrySet()) text.append('&').append(entry.getKey()).append('=').append(entry.getValue());
        text.append('&').append(signed);
        return b64(MessageDigest.getInstance("SHA-1").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }
    static byte[] rc4(String password, byte[] input) {
        byte[] key = decode(password), output = new byte[input.length];
        if (key.length < 1 || key.length > 256) throw new IllegalArgumentException("key length");
        int[] state = new int[256]; for (int i = 0; i < 256; i++) state[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + state[i] + (key[i % key.length] & 255)) & 255;
            int value = state[i]; state[i] = state[j]; state[j] = value;
        }
        int i = 0; j = 0;
        for (int pos = -1024; pos < input.length; pos++) {
            i = (i + 1) & 255; j = (j + state[i]) & 255;
            int value = state[i]; state[i] = state[j]; state[j] = value;
            int stream = state[(state[i] + state[j]) & 255];
            if (pos >= 0) output[pos] = (byte) ((input[pos] & 255) ^ stream);
        }
        return output;
    }
    static Map<String, String> params(String uri, String data, String secret, String nonce) throws Exception {
        String signed = signedNonce(secret, nonce);
        Map<String, String> params = new LinkedHashMap<>(); params.put("data", data);
        params.put("rc4_hash__", signature(uri, signed, params));
        for (Map.Entry<String, String> entry : params.entrySet()) entry.setValue(b64(rc4(signed, entry.getValue().getBytes(StandardCharsets.UTF_8))));
        params.put("signature", signature(uri, signed, params));
        params.put("ssecurity", secret); params.put("_nonce", nonce);
        return params;
    }
}
