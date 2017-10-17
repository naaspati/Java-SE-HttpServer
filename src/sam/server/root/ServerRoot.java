package sam.server.root;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

public interface ServerRoot extends AutoCloseable, Closeable {
    FileUnit getFileUnit(URI uri) throws IOException;
    FileUnit getFileUnit(long hashcode) throws IOException;
    List<String> walkDirectory(URI uri);
    Path getRoot();
    default String toPath(URI uri) {
        return uri.getPath().substring(1);
    }
}
