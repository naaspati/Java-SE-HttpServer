package sam.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ZipRoot implements ServerRoot {
    private final ZipFile zipFile;
    private final Path file;
    private final Map<String, ZipEntry> map;
    private URI currentUri;
    private ZipEntry currentZipentry;
    private HashMap<String, Path> repackMap; 

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
        repack();
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
    
    public void addFile(Path file, String name) {
        if(repackMap == null)
            repackMap = new HashMap<>();
        
        repackMap.put(name, file);
    }
    private void repack() throws IOException {
        if (repackMap == null || Files.notExists(file))
            return;

        Path out = Files.createTempFile("__", ".zip");

        try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.WRITE);
                ZipOutputStream zos = new ZipOutputStream(os);) {

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                zos.putNextEntry(ze);
                Tools.pipe(zipFile.getInputStream(ze), zos);
            }
            repackMap.forEach((name, file) -> {
                try {
                    zos.putNextEntry(new ZipEntry(name.toString()));
                    Files.copy(file, zos);  
                } catch (IOException e) {
                    System.out.println(Tools.red("failed repack: ")+file+"  "+e);
                }
            });
        }
        zipFile.close();
        Files.move(out, file, StandardCopyOption.REPLACE_EXISTING);
        System.out.println(Tools.yellow("repacked: ") + file.getFileName() + Tools.yellow("  added: ") + repackMap.keySet());
    }
    
}


