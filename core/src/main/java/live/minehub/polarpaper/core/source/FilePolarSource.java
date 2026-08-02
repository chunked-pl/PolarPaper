package live.minehub.polarpaper.core.source;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public record FilePolarSource(Path path) implements PolarSource {
    @Override
    public byte[] readBytes() throws Exception {
        return Files.readAllBytes(this.path);
    }

    @Override
    public void saveBytes(byte[] data) throws Exception {
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) throw new IllegalArgumentException("Polar file must have a parent directory: " + path);

        Files.createDirectories(parent);
        String prefix = target.getFileName().toString();
        if (prefix.length() < 3) prefix = (prefix + "___").substring(0, 3);
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, data);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete() throws Exception {
        Files.deleteIfExists(path);
    }
}
