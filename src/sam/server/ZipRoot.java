package sam.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class ZipRoot implements ServerRoot {
    final ZipFile zipFile;
    final Path file;
    final Map<String, ZipEntry> map;
    URI currentUri;
    ZipEntry currentZipentry;

    public ZipRoot(Path root) throws ZipException, IOException {
        this.zipFile = new ZipFile(root.toFile());
        this.file = root;

        map = this.zipFile.stream()
                .collect(Collectors.toMap(z -> z.getName().replace('\\', '/'), z -> z));    
    }
    private ZipEntry zipEntry(URI uri) {
        if(Objects.equals(currentUri, uri))
            return currentZipentry;

        ZipEntry ze = map.get(toPath(uri));
        if(ze == null)
            return null;

        currentUri = uri;
        currentZipentry = ze;

        return ze;
    }

    @Override
    public InputStream getInputStream(URI uri) throws IOException {
        return zipEntry(uri) == null || currentZipentry.isDirectory() ? null :  zipFile.getInputStream(currentZipentry);
    }
    @Override
    public long getSize(URI uri) throws IOException{
        return zipEntry(uri) == null ? -1 :  currentZipentry.getSize();
    }
    @Override
    public String getName(URI uri) {
        return zipEntry(uri) == null ? null :  currentZipentry.getName();
    }
    @Override
    public void close() throws IOException {
        currentZipentry = null;
        currentUri = null;
        zipFile.close();
    }

    @Override
    public Path getRoot() {
        return file;
    }

    @Override
    public List<String> walkDirectory(final URI uri) {
        Stream<String> strm = map.keySet().stream();
        
        String str = uri.getPath().substring(1);
        
        if(!str.isEmpty()) {
            strm = strm
            .filter(s -> s.startsWith(str))
            .map(s -> s.substring(str.length()+1));
        }
        
        return strm 
                .map(s -> {
                    int index = s.indexOf('/');
                    if(index < 0)
                        return s;
                    return s.substring(0, index);
                })
                .distinct()
                .collect(Collectors.toList());
    }
}


