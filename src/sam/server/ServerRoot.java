package sam.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

public interface ServerRoot extends AutoCloseable, Closeable {
    InputStream getInputStream(URI uri) throws IOException;
    List<String> walkDirectory(URI uri);
    Path getRoot();
    long getSize(URI uri) throws IOException;
    String getName(URI uri);
    default String toPath(URI uri) {
        return uri.getPath().substring(1);
    }
}


