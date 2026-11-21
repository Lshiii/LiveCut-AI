package io.livecut.video;

import java.util.Locale;
import java.util.regex.Pattern;

/** One portable MP4 basename for the original media download and its OTIO reference. */
public final class SourceMediaFilename {
    private static final Pattern UNSAFE_NAME = Pattern.compile("[\\\\/<>:\"|?*%\\p{C}]");
    private static final Pattern DEVICE_NAME = Pattern.compile("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])\\..*");

    private SourceMediaFilename() {}

    public static String portable(String name, String hash) {
        if (name == null || name.length() < 5 || name.length() > 255 || name.startsWith(".")
                || !name.equals(name.strip()) || !name.toLowerCase(Locale.ROOT).endsWith(".mp4")
                || UNSAFE_NAME.matcher(name).find() || DEVICE_NAME.matcher(name).matches()) {
            return "source-" + hash + ".mp4";
        }
        return name;
    }
}
