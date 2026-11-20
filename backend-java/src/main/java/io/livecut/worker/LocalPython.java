package io.livecut.worker;

import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalPython {
    private LocalPython() {}

    public static String interpreter(String configured) {
        if (!configured.isBlank()) {
            Path path = Path.of(configured);
            return path.getParent() == null ? configured : path.toAbsolutePath().normalize().toString();
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 3 && dir != null; i++, dir = dir.getParent()) {
            for (String relative : new String[]{".venv/Scripts/python.exe", ".venv/bin/python"}) {
                Path candidate = dir.resolve(relative);
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
        }
        return "python";
    }
}
