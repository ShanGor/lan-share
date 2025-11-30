package io.github.shangor.lan.transfer.core.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileMetadata {
    private final String relativePath;
    private final long size;
    private final String md5Hex;

    public FileMetadata(String relativePath, long size, String md5Hex) {
        this.relativePath = relativePath;
        this.size = size;
        this.md5Hex = md5Hex;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public long getSize() {
        return size;
    }

    public String getMd5Hex() {
        return md5Hex;
    }

    public static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    public static String md5Hex(String input) {
        return md5Hex(input.getBytes(StandardCharsets.UTF_8));
    }
}
