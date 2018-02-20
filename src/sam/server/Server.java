package sam.server;

import static sam.server.Tools.green;
import static sam.server.Tools.print;
import static sam.server.Tools.yellow;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import sam.server.root.DirectoryRoot;
import sam.server.root.FileUnit;
import sam.server.root.ServerRoot;
import sam.server.root.ZipRoot;

public class Server {
    private final URI rootUri;
    private final Path downloadsDir;
    private final Path lookDownloadDir;

    private ServerRoot file;
    private final HttpServer hs;
    private final Map<String, String> fileExtMimeMap;
    private final Map<String, String> mimeFileExtMap;
    private static final Set<Path> TEMP_FILES = new HashSet<>();
    private final Predicate<String> downloadAsServerResourcesPredicate;
    private final InetSocketAddress runningAt;
    private final int readTimeout,connectTimeout;
    private final ConcurrentHashMap<DownloadTask, Future<?>> tasksMap = new ConcurrentHashMap<>();
    private final BufferManeger bufferManeger = BufferManeger.getInstance();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public Server(int port) throws Exception {
        ResourceBundle rb = ResourceBundle.getBundle("1509617391333-server_config");
        runningAt = new InetSocketAddress("localhost", port);
        rootUri = new URI("/");

        downloadsDir = Optional.ofNullable(System.getProperty("server"))
                .map(Paths::get)
                .map(path -> Files.isRegularFile(path) ? path.getParent() : path)
                .orElse(Paths.get("."))
                .resolve("server_downloads");
        lookDownloadDir = downloadsDir.resolveSibling("look_for_download"); 

        Files.createDirectories(downloadsDir);

        readTimeout = Integer.parseInt(rb.getString("read.timeout"));
        connectTimeout = Integer.parseInt(rb.getString("connect.timeout"));

        downloadAsServerResourcesPredicate = createPredicate(rb, "download.as.server.resources");        
        final Predicate<String> download_resources = createPredicate(rb, "download.resources");
        ResourceBundle.clearCache();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executorService.shutdownNow();
            try {
                closeRoot();
                TEMP_FILES.stream().map(Path::toFile).forEach(File::delete);
            } catch (IOException e) {}   
        }));

        try(InputStream is = getClass().getClassLoader().getResourceAsStream("1509617391333-file.ext-mime.tsv");
                InputStreamReader reader = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(reader)) {

            fileExtMimeMap = br.lines()
                    .filter(s -> !s.startsWith("#") && s.indexOf('\t') > 0).map(s -> s.split("\t"))
                    .collect(Collectors.toMap(s -> s[2], s -> s[1], (o, n) -> n));
            
            mimeFileExtMap = new ConcurrentHashMap<>();
            fileExtMimeMap.forEach((s,t) -> mimeFileExtMap.put(t,s));
        }

        hs = HttpServer.create(runningAt, 10);
        hs.createContext(rootUri.toString(), new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                URI uri = exchange.getRequestURI();

                if (uri.equals(rootUri))
                    uri = rootUri.resolve("index.html");

                FileUnit fileUnit = file.getFileUnit(uri);

                if (fileUnit == null) {
                    uri = exchange.getRequestURI();

                    List<String> dir = file.walkDirectory(uri);
                    if (dir != null) {
                        final byte[] bytes = directoryHtml(uri, dir);
                        try(OutputStream resposeBody = setSendHeader(exchange, bytes.length, "text/html")) {
                            resposeBody.write(bytes);    
                        }
                    } else {
                        print(uri, null);
                        exchange.sendResponseHeaders(404, -1);
                    }
                    return;
                }
                String name = fileUnit.getName();
                print(uri,name);
                try(OutputStream resposeBody = exchange.getResponseBody()) {
                    exchange.getResponseHeaders().add("Content-Type", getMime(name));

                    // replace link which is to be cached 
                    if (uri.equals(rootUri.resolve("index.html")) || uri.toString().chars().filter(c -> c == '/').count() >= 2) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream((int)fileUnit.getSize());
                        InputStream is = fileUnit.getInputStream();
                        int b = 0;
                        while((b = is.read()) != -1) bos.write(b);

                        Pattern pattern = Pattern.compile("(\"|')(https?.+)\\1");
                        Matcher m = pattern.matcher(new String(bos.toByteArray()));

                        StringBuffer sb = new StringBuffer();

                        while(m.find()) {
                            if(download_resources.test(m.group(2)))
                                m.appendReplacement(sb, m.group(1)+"/download?"+m.group(2)+m.group(1));
                        } 
                        m.appendTail(sb);

                        byte[] bytes = sb.toString().getBytes();
                        exchange.sendResponseHeaders(200, bytes.length);
                        resposeBody.write(bytes);
                    } else {
                        exchange.sendResponseHeaders(200, fileUnit.getSize());
                        pipe(fileUnit.getInputStream(), resposeBody);
                    }
                    fileUnit.close();
                }
            }
        });
        // handle caching resource 
        hs.createContext("/download", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                // System.out.println(exchange.getRequestURI());
                URL url = new URL(exchange.getRequestURI().getQuery());
                final String query = url.getQuery(); 

                FileUnit fileUnit = query != null ? file.getFileUnit(query.hashCode()) : file.getFileUnit(rootUri.resolve(new File(url.getPath()).getName()));

                if (fileUnit != null) {
                    try(OutputStream resposeBody = setSendHeader(exchange, fileUnit.getSize(), getMime(fileUnit.getName()))) {
                        pipe(fileUnit.getInputStream(), resposeBody);
                        print(url, fileUnit.getName());
                        fileUnit.close();                        
                    }
                } else {
                    String name = query == null ? new File(url.getPath()).getName() : String.valueOf(query.hashCode());
                    Path path = downloadsDir.resolve(name);
                    if(Files.notExists(path))
                        downloadAction(name, url, exchange);
                    else
                        sendFile(path, exchange, name, url);
                }
            }
        });
    }

    public boolean isServerDownloadableResource(URL url){
        return downloadAsServerResourcesPredicate.test(url.toString());
    }
    public int getReadTimeout() {
        return readTimeout;
    }
    public int getConnectTimeout() {
        return connectTimeout;
    }
    public Path getDownloadsDir() {
        return downloadsDir;
    }
    public Path getLookDownloadDir() {
        return lookDownloadDir;
    }
    public String getFileExtenstionUsingMime(String mime) {
        return mimeFileExtMap.get(mime);
    }
    void sendFile(Path path, HttpExchange exchange, String name, URL url) {
        try(OutputStream resposeBody = setSendHeader(exchange, Files.size(path), getMime(name))) {
            Files.copy(path, resposeBody);
            print(url , path.subpath(path.getNameCount() - 2, path.getNameCount()));
        } catch (Exception e) {
            System.out.println("failed to send file: ");
            print(url , path.subpath(path.getNameCount() - 2, path.getNameCount()));
        }
    }
    private Predicate<String> createPredicate(ResourceBundle rb, String resourceKey) {
        Map<Boolean, Set<String>> map = 
                Optional.ofNullable(rb.getString(resourceKey))
                .map(s -> s.trim().isEmpty() ? null : s)
                .map(s -> s.split("\\s*,\\s*"))
                .map(ary -> Stream.of(ary)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.replace(".", "\\.").replace("*", ".+"))
                        .collect(Collectors.partitioningBy(s -> s.charAt(0) != '!', Collectors.toSet()))
                        )
                .orElse(new HashMap<>());

        Function<Boolean , Predicate<String>> get = key -> {
            if(map.get(key) == null || map.get(key).isEmpty())
                return null; 

            return map.get(key)
                    .stream()
                    .map(s -> key ? s : s.substring(1))
                    .map(Pattern::compile)
                    .map(pattern -> (Predicate<String>)(s -> pattern.matcher(s).matches()))
                    .reduce(Predicate::or)
                    .get();        
        };

        Predicate<String> add = get.apply(true);
        Predicate<String> remove = get.apply(false);

        return string -> remove != null && remove.test(string) ? false : add == null ? false : add.test(string);    
    }; 
    protected byte[] directoryHtml(URI uri, List<String> dir) {
        StringBuilder sb = new StringBuilder(
                "<!DOCTYPE html>\r\n<html>\r\n\r\n<head>\r\n    <meta charset=\"utf-8\">\r\n    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, shrink-to-fit=no\">\r\n    <title>")
                .append(uri)
                .append("</title>\r\n    <meta name=\"description\" content=\"\">\r\n    <meta name=\"author\" content=\"\">\r\n</head>\r\n<style>\r\n:root {\r\n    background-color: #0F0F0F;\r\n    color: white;\r\n    margin-left: 10px;\r\n    font-family: \"Consolas\";\r\n    line-height: 1.6;\r\n}\r\n\r\nul {\r\n    margin: 0;\r\n    padding: 0;\r\n    margin-left: 10px;\r\n}\r\n\r\nul * {\r\n    margin: 0;\r\n    padding: 0;\r\n}\r\n\r\nli {\r\n    list-style: none;\r\n}\r\n\r\nli a {\r\n    text-decoration: none;\r\n    color: white;\r\n    border: 1px solid #353535;\r\n    border-width: 0 0 1px 0;\r\n    padding-bottom: 1px;\r\nmargin-bottom: 1px;\r\n    transition: border-color 0.5s;\r\n    -webkit-transition: border-color 0.5s;\r\n}\r\n\r\nli a:hover {\r\n    border-color: white;\r\n}\r\n\r\n</style>\r\n\r\n<body>\r\n    <h1>Directory List</h1>\r\n    <ul>");

        URI uri2 = uri;
        dir.forEach(d -> sb.append("<li><a href='")
                .append(uri2 + (rootUri.equals(uri2) ? "" : "/")
                        + Paths.get("/" + d).toUri().toString().replaceFirst("^file:\\/+\\w+:\\/", ""))
                .append("'>").append(d).append("</a></li>\n"));
        sb.append("   </ul>\r\n</body>\r\n\r\n</html>\r\n");
        return sb.toString().getBytes();
    }

    OutputStream setSendHeader(HttpExchange exchange, long size, String mime) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", mime == null ? "text/html" : mime);
        exchange.sendResponseHeaders(200, size);
        return exchange.getResponseBody();
    }
    private String getMime(String fileName) {
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

    public void start(Path root, boolean openInBrowser) throws IOException {
        closeRoot();

        if (Files.notExists(root))
            throw new FileNotFoundException(root.toString());

        if (Files.isRegularFile(root))
            file = new ZipRoot(root);
        else
            file = new DirectoryRoot(root);

        hs.start();
        System.out.println(yellow("server running at: \n")+
                "    localhost:"+ runningAt.getPort()+
                "\n    "+runningAt.getAddress().getHostAddress() + ":"+runningAt.getPort());
        System.out.println(downloadsDir);
        System.out.println(lookDownloadDir);
        System.out.println("\n");

        if (openInBrowser)
            Runtime.getRuntime().exec("explorer http://localhost:" + runningAt.getPort());
    }

    public void changeRoot(Path path, boolean openInBrowser) throws IOException {
        if (Files.notExists(path))
            throw new FileNotFoundException(path.toString());

        closeRoot();

        if (Files.isRegularFile(path))
            file = new ZipRoot(path);
        else
            file = new DirectoryRoot(path);

        if (openInBrowser)
            Runtime.getRuntime().exec("explorer http://localhost:"+runningAt.getPort());

        System.out.println(green("\nroot changed to:  "+file.getRoot()));
    }

    public void closeRoot() throws IOException {
        tasksMap.forEach((d,f) -> f.cancel(true));
        tasksMap.clear();
        Tools.closeThese(file);
        file = null;
    }
    public void pipe(InputStream is, OutputStream resposeBody) throws IOException {
        byte[] bytes = bufferManeger.getbuffer();
        int n = 0;
        while ((n = is.read(bytes)) > 0)
            resposeBody.write(bytes, 0, n);

        bufferManeger.addBuffer(bytes);
    }
    protected void downloadAction(final String name, final URL url, final HttpExchange exchange) {
        Downloaded dd = DownloadTask.getDownloaded(url);
        if(dd != null && Files.exists(dd.getDownloadPath())) {
            sendFile(dd.getDownloadPath(), exchange, name, url);
            return;
        }
        DownloadTask dt = new DownloadTask(this, url, name, exchange, tasksMap::remove);
        tasksMap.put(dt, executorService.submit(dt));
    }
    synchronized void setResourceToRootFile(Path temp, String name2) throws IOException {
        if (file instanceof ZipRoot)
            ((ZipRoot) file).addRepackFile(temp, name2);
        else {
            DirectoryRoot dr = (DirectoryRoot) file;
            Files.copy(temp, dr.root.resolve(name2), StandardCopyOption.REPLACE_EXISTING);
        }
        TEMP_FILES.add(temp);
    }
}
