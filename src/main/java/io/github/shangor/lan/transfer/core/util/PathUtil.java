package io.github.shangor.lan.transfer.core.util;

/**
 * Utility class for cross-platform path handling in LAN transfer.
 * Normalizes path separators to ensure consistent directory structure
 * preservation across different operating systems.
 */
public class PathUtil {

    /**
     * Normalizes path separators to forward slashes (/) for platform-independent transmission.
     * This converts Windows backslashes (\) to forward slashes and normalizes multiple separators.
     *
     * @param path The input path with any separator format
     * @return Path with normalized forward slash separators
     */
    public static String normalizePathSeparators(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        // Replace all backslashes with forward slashes
        String normalized = path.replace('\\', '/');

        // Normalize multiple consecutive forward slashes to a single one
        normalized = normalized.replaceAll("/+", "/");

        // Remove leading slash if present (since we're dealing with relative paths)
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return normalized;
    }

    /**
     * Converts a normalized path (with forward slashes) to the current platform's path format.
     * On Windows, converts forward slashes to backslashes.
     * On Unix-like systems, keeps forward slashes.
     *
     * @param normalizedPath The path with normalized forward slash separators
     * @return Path formatted for the current platform
     */
    public static String toPlatformPath(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return normalizedPath;
        }

        // Check if we're on Windows
        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = osName.contains("win");

        if (isWindows) {
            // Convert forward slashes to backslashes on Windows
            return normalizedPath.replace('/', '\\');
        } else {
            // Keep forward slashes on Unix-like systems
            return normalizedPath;
        }
    }
}