package sam.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

public class DirectoryRoot implements ServerRoot {
    final Path root;

    public DirectoryRoot(Path root) {
        this.root = root;
    }
    @Override
    public void close() throws IOException {}
    Path getPath(URI uri) {
        return root.resolve(toPath(uri));           
    }
    @Override
    public InputStream getInputStream(URI uri) throws IOException {
        Path p = getPath(uri);
        return Files.notExists(p) || Files.isDirectory(p) ? null : Files.newInputStream(p, StandardOpenOption.READ);
    }
    @Override
    public long getSize(URI uri) throws IOException {
        Path p = getPath(uri);
        return Files.notExists(p) ? -1 :  Files.size(p);
    }
    @Override
    public String getName(URI uri) {
        return uri.getPath().substring(1);
    }
    @Override
    public Path getRoot() {
        return root;
    }
    @Override
    public List<String> walkDirectory(URI uri) {
        Path p = getPath(uri);

        if(!Files.isDirectory(p))
            return null;

        return Arrays.asList(p.toFile().list());
    }
}

