package sam.server.root;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class DirectoryRoot implements ServerRoot {
    public final Path root;

    public DirectoryRoot(Path root) {
        this.root = root;
    }
    @Override
    public void close() throws IOException {}
    Path getPath(URI uri) {
        return root.resolve(toPath(uri));           
    }
    @Override
    public FileUnit getFileUnit(URI uri) throws IOException {
        Path p = getPath(uri);
        return Files.isRegularFile(p) ? new FileUnit(uri.toString().substring(1), Files.size(p), Files.newInputStream(p, StandardOpenOption.READ)) : null;
    }
    @Override
    public FileUnit getFileUnit(long hashcode) throws IOException {
        String str = String.valueOf(hashcode);
        Optional<Path> path = 
                Stream.of(root.toFile().list())
                .filter(s -> s.startsWith(str))
                .filter(s -> str.equals(s.indexOf('.') < 0 ? s : s.substring(0, s.indexOf('.'))))
                .map(root::resolve)
                .filter(Files::isRegularFile)
                .findFirst();
        
        if(!path.isPresent())
            return null;
        
        Path p = path.get();
        return new FileUnit(p.getFileName().toString(), Files.size(p), Files.newInputStream(p, StandardOpenOption.READ));
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

