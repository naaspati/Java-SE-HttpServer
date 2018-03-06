package sam.server;

import static java.util.stream.Collectors.toMap;
import static sam.string.stringutils.StringUtils.contains;
import static sam.string.stringutils.StringUtils.split;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;

public final class ServerUtils {
    private static final  Map<String, String> fileExtMimeMap;
    private static final Map<String, String> mimeFileExtMap;
    private static final Set<Path> TEMP_FILES = new HashSet<>();
    private static final int BUFFER_SIZE = Optional.ofNullable(System.getProperty("sam.buffer.size")).map(Integer::parseInt).orElse(8*1024);
    public static final Path DOWNLOADS_DIR;
    public static final Path LOOK_DOWNLOADS_DIR;
    public static final int READ_TIMEOUT;
    public static final int CONNECT_TIMEOUT;
    
    static {
        ResourceBundle rb = ResourceBundle.getBundle("1509617391333-server_config");
        
        READ_TIMEOUT = Integer.parseInt(rb.getString("read.timeout"));
        CONNECT_TIMEOUT = Integer.parseInt(rb.getString("connect.timeout"));
        
        try(InputStream is = ServerUtils.class.getClassLoader().getResourceAsStream("1509617391333-file.ext-mime.tsv");
                InputStreamReader reader = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(reader)) {
            
            DOWNLOADS_DIR = Optional.ofNullable(System.getProperty("server"))
                    .map(Paths::get)
                    .map(path -> Files.isRegularFile(path) ? path.getParent() : path)
                    .orElse(Paths.get("."))
                    .resolve("server_downloads");

            LOOK_DOWNLOADS_DIR = DOWNLOADS_DIR.resolveSibling("look_for_download"); 

            Files.createDirectories(DOWNLOADS_DIR);

            fileExtMimeMap = br.lines()
                    .filter(s -> !s.isEmpty() && s.charAt(0) != '#' && contains(s, '\t'))
                    .map(s -> split(s,'\t'))
                    .collect(toMap(s -> s[2], s -> s[1], (o, n) -> n));
            
            mimeFileExtMap = new ConcurrentHashMap<>();
            fileExtMimeMap.forEach((s,t) -> mimeFileExtMap.put(t,s));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    private ServerUtils() {}
    public static void deleteTempFiles() {
        TEMP_FILES.stream().map(Path::toFile).forEach(File::delete);
        TEMP_FILES.clear();
    }
    public static Thread addShutdownHook(Server server) {
        Thread t = new Thread(() -> {
            server.shutdownNow();
            try {
                server.closeRoot();
                ServerUtils.deleteTempFiles();
            } catch (IOException e) {}   
        });
        Runtime.getRuntime().addShutdownHook(t);
        return t;
    }
    public static String getFileExtenstionUsingMime(String mime) {
        return mimeFileExtMap.get(mime);
    }
    public static String getMime(String fileName) {
        final int index = fileName.lastIndexOf('.');
        if (index < 0)
            return "text/plain";

        String mime = fileExtMimeMap.get(fileName.substring(index));
        if(mime == null) {
            try {
                mime = Files.probeContentType(Paths.get(fileName));
            } catch (IOException e) {}
        }

        return mime == null ? "text/plain" : mime;
    }

    static OutputStream setSendHeader(HttpExchange exchange, long size, String mime) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", mime == null ? "text/html" : mime);
        exchange.sendResponseHeaders(200, size);
        return exchange.getResponseBody();
    }

    public static byte[] createBuffer() {
        return new byte[BUFFER_SIZE];
    }
    
    public static void pipe(InputStream is, OutputStream resposeBody) throws IOException {
        byte[] bytes = createBuffer();
        int n = 0;
        while ((n = is.read(bytes)) > 0)
            resposeBody.write(bytes, 0, n);
        
    }
}
