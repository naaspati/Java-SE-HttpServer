package sam.server.root;

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
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import sam.server.Utils;

public final class ZipRoot implements ServerRoot {
    private ZipFile zipFile;
    private Path file;
    private Map<String, ZipEntry> map;
    private HashMap<String, Path> repackMap; 

    public ZipRoot(Path root) throws ZipException, IOException {
        this.zipFile = new ZipFile(root.toFile());
        this.file = root;

        map = this.zipFile.stream()
                .collect(Collectors.toMap(z -> z.getName().replace('\\', '/'), z -> z));    
    }
    private ZipEntry zipEntry(URI uri) {
        ZipEntry ze = map.get(toPath(uri));
        if(ze == null)
            return null;

        return ze;
    }

    @Override
    public FileUnit getFileUnit(URI uri) throws IOException {
        ZipEntry ze = zipEntry(uri); 
        return  ze == null ? null : new FileUnit(ze.getName(), ze.getSize(), zipFile.getInputStream(ze));
    }
    @Override
    public FileUnit getFileUnit(long hashcode) throws IOException {
        String hashcodeS = String.valueOf(hashcode);
        Optional<String> path = 
                map.keySet().stream()
                .filter(s -> s.startsWith(hashcodeS))
                .filter(s -> hashcodeS.equals(s.indexOf('.') < 0 ? s : s.substring(0, s.indexOf('.'))))
                .filter(f -> !map.get(f).isDirectory())
                .findFirst();
        
        if(path.isPresent()) {
            ZipEntry ze = map.get(path.get());
            return new FileUnit(ze.getName(), ze.getSize(), zipFile.getInputStream(ze));
        }
        
        if(repackMap != null) {
            Optional<String> value = repackMap.keySet().stream()
            .filter(s -> s.startsWith(hashcodeS))
            .filter(s -> hashcodeS.equals(s.indexOf('.') < 0 ? s : s.substring(0, s.indexOf('.'))))
            .findFirst();
            
            if(value.isPresent()) {
                Path p = repackMap.get(value.get());
                return new FileUnit(p.getFileName().toString(), Files.size(p), Files.newInputStream(p, StandardOpenOption.READ));
            }
        }
        return null;
    }
    
    
    @Override
    public void close() throws IOException {
        repack();
        file = null;
        map = null;
        repackMap = null;
        if(zipFile != null) 
            zipFile.close();
        zipFile = null;
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

    public void addRepackFile(Path file, String name) {
        if(repackMap == null)
            repackMap = new HashMap<>();

        repackMap.put(name, file);
    }
    private void repack() throws IOException {
        if (repackMap == null || repackMap.isEmpty() || Files.notExists(file))
            return;

        Path out = Files.createTempFile("__", ".zip");

        try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.WRITE);
                ZipOutputStream zos = new ZipOutputStream(os);) {

            byte[] bytes = new byte[256*1024];
            
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                zos.putNextEntry(ze);
                
                InputStream is = zipFile.getInputStream(ze); 
                int n = 0;
                while ((n = is.read(bytes)) > 0)
                    zos.write(bytes, 0, n);
            }
            repackMap.forEach((name, file) -> {
                try {
                    zos.putNextEntry(new ZipEntry(name.toString()));
                    Files.copy(file, zos);  
                } catch (IOException e) {
                    System.out.println(Utils.red("failed repack: ")+file+"  "+e);
                }
            });
        }
        zipFile.close();
        zipFile = null;

        Files.move(out, file, StandardCopyOption.REPLACE_EXISTING);
        System.out.println(Utils.yellow("repacked: ") + file.getFileName() + Utils.yellow("  added: ") + repackMap.keySet());
    }

}


