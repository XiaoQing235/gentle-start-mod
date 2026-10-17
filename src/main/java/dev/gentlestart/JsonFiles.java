package dev.gentlestart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class JsonFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonFiles() {
    }

    static void write(Path file, JsonElement json) {
        Path temporary = null;
        try {
            Path target = file.toAbsolutePath();
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            Files.writeString(temporary, GSON.toJson(json) + "\n");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot save " + file.getFileName(), exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot remove temporary configuration file", exception);
                }
            }
        }
    }
}
