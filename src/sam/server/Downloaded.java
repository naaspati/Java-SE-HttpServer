package sam.server;

import java.nio.file.Path;

public final class Downloaded {
    private final Path downloadPath;
    private final String mime;
    public Downloaded(Path downloadPath, String mime) {
        this.downloadPath = downloadPath;
        this.mime = mime;
    }
    public Path getDownloadPath() {
        return downloadPath;
    }
    public String getMime() {
        return mime;
    }
}
