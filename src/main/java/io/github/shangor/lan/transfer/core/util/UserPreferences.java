package io.github.shangor.lan.transfer.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class UserPreferences {
    private static final Logger log = Logger.getLogger(UserPreferences.class.getName());
    private static final Path BASE_DIR = Path.of(System.getProperty("user.home"), ".lan-transfer");
    private static final Path SENDER_FILE = BASE_DIR.resolve("sender.properties");
    private static final Path RECEIVER_FILE = BASE_DIR.resolve("receiver.properties");

    private UserPreferences() {
    }

    public record SenderSettings(String host, int port, String folder) {}
    public record ReceiverSettings(int port, String destination) {}

    public static SenderSettings loadSenderSettings() {
        Properties props = loadProps(SENDER_FILE);
        String host = props.getProperty("host", "");
        int port = parseInt(props.getProperty("port"), 9000);
        String folder = props.getProperty("folder", "");
        return new SenderSettings(host, port, folder);
    }

    public static void saveSenderSettings(SenderSettings settings) {
        Properties props = new Properties();
        props.setProperty("host", settings.host() != null ? settings.host() : "");
        props.setProperty("port", Integer.toString(settings.port()));
        props.setProperty("folder", settings.folder() != null ? settings.folder() : "");
        storeProps(SENDER_FILE, props);
    }

    public static ReceiverSettings loadReceiverSettings() {
        Properties props = loadProps(RECEIVER_FILE);
        int port = parseInt(props.getProperty("port"), 9000);
        String destination = props.getProperty("destination", "");
        return new ReceiverSettings(port, destination);
    }

    public static void saveReceiverSettings(ReceiverSettings settings) {
        Properties props = new Properties();
        props.setProperty("port", Integer.toString(settings.port()));
        props.setProperty("destination", settings.destination() != null ? settings.destination() : "");
        storeProps(RECEIVER_FILE, props);
    }

    private static Properties loadProps(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to load preferences from " + file, e);
            }
        }
        return props;
    }

    private static void storeProps(Path file, Properties props) {
        try {
            Files.createDirectories(BASE_DIR);
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Lan Transfer preferences");
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to store preferences to " + file, e);
        }
    }

    private static int parseInt(String text, int defaultValue) {
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
