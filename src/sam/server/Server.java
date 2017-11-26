package sam.server;

import static sam.server.Tools.bytesToString;
import static sam.server.Tools.cyan;
import static sam.server.Tools.durationToString;
import static sam.server.Tools.green;
import static sam.server.Tools.red;
import static sam.server.Tools.resave_cursor;
import static sam.server.Tools.save_cursor;
import static sam.server.Tools.yellow;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
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
    private final URI ROOT_URI;
    private final Path DOWNLOADS_DIR;

    private ServerRoot file;
    private final HttpServer hs;
    private final Map<String, String> fileext_mimeMap;
    private static final Set<Path> TEMP_FILES = new HashSet<>();
    private final Predicate<String> download_as_server_resources;
    private final InetSocketAddress runningAt;
    private final int READ_TIMEOUT,CONNECT_TIMEOUT;
    private final LinkedList<Future<?>> tasksList = new LinkedList<>();
    private final ConcurrentLinkedQueue<byte[]> buffers = new ConcurrentLinkedQueue<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ConcurrentMap<URL, Object[]> downloaded = new ConcurrentHashMap<>();


    public Server(int port) throws Exception {
        ResourceBundle rb = ResourceBundle.getBundle("1509617391333-server_config");
        runningAt = new InetSocketAddress("localhost", port);
        ROOT_URI = new URI("/");

        DOWNLOADS_DIR = Optional.ofNullable(System.getProperty("server"))
                .map(Paths::get)
                .map(path -> Files.isRegularFile(path) ? path.getParent() : path)
                .orElse(Paths.get("."))
                .resolve("server_downloads");

        Files.createDirectories(DOWNLOADS_DIR);

        READ_TIMEOUT = Integer.parseInt(rb.getString("read.timeout"));
        CONNECT_TIMEOUT = Integer.parseInt(rb.getString("connect.timeout"));

        download_as_server_resources = createPredicate(rb, "download.as.server.resources");        
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

            fileext_mimeMap = br.lines()
                    .filter(s -> !s.startsWith("#") && s.indexOf('\t') > 0).map(s -> s.split("\t"))
                    .collect(Collectors.toMap(s -> s[2], s -> s[1], (o, n) -> n));
        }

        hs = HttpServer.create(runningAt, 10);
        hs.createContext(ROOT_URI.toString(), new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                URI uri = exchange.getRequestURI();

                if (uri.equals(ROOT_URI))
                    uri = ROOT_URI.resolve("index.html");

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
                    if (uri.equals(ROOT_URI.resolve("index.html")) || uri.toString().chars().filter(c -> c == '/').count() >= 2) {
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

                FileUnit fileUnit = query != null ? file.getFileUnit(query.hashCode()) : file.getFileUnit(ROOT_URI.resolve(new File(url.getPath()).getName()));

                if (fileUnit != null) {
                    try(OutputStream resposeBody = setSendHeader(exchange, fileUnit.getSize(), getMime(fileUnit.getName()))) {
                        pipe(fileUnit.getInputStream(), resposeBody);
                        print(url, fileUnit.getName());
                        fileUnit.close();                        
                    }
                } else {
                    String name = query == null ? new File(url.getPath()).getName() : String.valueOf(query.hashCode());
                    Path path = DOWNLOADS_DIR.resolve(name);
                    if(Files.notExists(path))
                        downloadAction(name, url, exchange);
                    else {
                        try(OutputStream resposeBody = setSendHeader(exchange, Files.size(path), getMime(name))) {
                            Files.copy(path, resposeBody);
                            print(url , path.subpath(path.getNameCount() - 2, path.getNameCount()));
                        } catch (Exception e) {
                            System.out.println("failed to send file: ");
                            print(url , path.subpath(path.getNameCount() - 2, path.getNameCount()));
                        }
                    }
                }
            }
        });
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
                .append(uri2 + (ROOT_URI.equals(uri2) ? "" : "/")
                        + Paths.get("/" + d).toUri().toString().replaceFirst("^file:\\/+\\w+:\\/", ""))
                .append("'>").append(d).append("</a></li>\n"));
        sb.append("   </ul>\r\n</body>\r\n\r\n</html>\r\n");
        return sb.toString().getBytes();
    }
    protected void print(Object request, Object response) {
        System.out.println(request + (response == null ? red("  ->  null") : yellow(" -> ") + response));
    }
    protected void error(Object request, Object msg, Exception e) {
        System.out.println(request + red(" -> ") + msg + red("Error: [")+ e.getClass().getSimpleName()+"] "+e.getMessage());
    }

    private OutputStream setSendHeader(HttpExchange exchange, long size, String mime) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", mime == null ? "text/html" : mime);
        exchange.sendResponseHeaders(200, size);
        return exchange.getResponseBody();
    }
    private String getMime(String fileName) {
        final int index = fileName.lastIndexOf('.');
        if (index < 0)
            return "text/plain";

        String mime = fileext_mimeMap.get(fileName.substring(index));
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
                "\n    "+runningAt.getAddress().getHostAddress() + ":"+runningAt.getPort()+"\n");
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
        tasksList.forEach(f -> f.cancel(true));

        if (file != null) {
            file.close();
            file = null;
        }
    }
    public void pipe(InputStream is, OutputStream resposeBody) throws IOException {
        byte[] bytes = getbuffer();
        int n = 0;
        while ((n = is.read(bytes)) > 0)
            resposeBody.write(bytes, 0, n);

        addBuffer(bytes);
    }
    private void addBuffer(byte[] buffer) {
        if(buffer != null)
            buffers.add(buffer);
    }
    private byte[] getbuffer() {
        byte[] bytes = buffers.poll();
        if(bytes == null)
            return new byte[256*1024];
        else
            return bytes;
    }
    protected void downloadAction(String name, URL url, HttpExchange exchange) {
        Object[] obj = downloaded.get(url);
        if(obj != null && Files.exists((Path)obj[0])) {
            try(OutputStream os = setSendHeader(exchange, Files.size((Path)obj[0]), (String)obj[1])) {
                Files.copy((Path)obj[0], os);
            } catch (IOException e1) {
                System.out.println(red("Error while sending file:") +e1);
            }
            return;
        }

        tasksList.add(executorService.submit(() -> {
            byte[] buffer = getbuffer();
            OutputStream ros = null, fos = null;
            InputStream is  = null;

            try {
                save_cursor();
                System.out.println(yellow("downloading: ")+url);
                URLConnection con = url.openConnection();
                con.setConnectTimeout(CONNECT_TIMEOUT);
                con.setReadTimeout(READ_TIMEOUT);
                con.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/62.0.3202.89 Safari/537.36");

                final double total = con.getContentLength();
                final String mime = con.getContentType();

                ros = setSendHeader(exchange, (long)total, mime);
                is = con.getInputStream();
                Path temp  = Files.createTempFile("server-download", "");
                fos = Files.newOutputStream(temp);

                long last = System.currentTimeMillis();
                int lastTotal = 0, speed = 0;

                final String format = "%s / "+green(bytesToString(total)) + cyan(" | ") + (total < 0 ? red(" -- ") : yellow(" %.2f%%")) + cyan(" | ") + "%d Kb/sec"
                        + (total < 0 ? "" : cyan(" | ") + yellow("time-left: ") + " %s");

                int bytesRead = 0, n = 0;
                while((n = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, n);
                    ros.write(buffer, 0, n);

                    bytesRead += n;

                    long timepassed = System.currentTimeMillis() - last;
                    if (timepassed >= 1000) {
                        double downloaded = bytesRead - lastTotal;
                        speed = (int) ((downloaded / timepassed) * (1000d / 1024d));
                        lastTotal = bytesRead;
                        last = System.currentTimeMillis();

                        resave_cursor();
                        System.out.println(yellow("downloading: ")+url);

                        if (total < 0)
                            System.out.printf(format, bytesToString(bytesRead), speed);
                        else
                            System.out.printf(format, bytesToString(bytesRead), (bytesRead * 100d) / total,
                                    speed, durationToString(
                                            Duration.ofSeconds((long) (((total - bytesRead) / 1024) / speed))));
                    }
                }

                for (Closeable c : new Closeable[] {ros, is, fos}) {
                    try {
                        c.close();
                    } catch (IOException e) {}
                }

                downloaded.put(url, new Object[] {temp, mime});
                if(download_as_server_resources.test(url.toString())) {
                    Files.move(temp, DOWNLOADS_DIR.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    print(url , yellow("downloaded/"+name));    
                    return;
                }
                String[] ext = {null};
                String name2 = name;
                if(name.matches("-?\\d+") ) {
                    if(mime != null) {
                        fileext_mimeMap.forEach((ext2, mime2) -> {
                            if(ext[0] == null && Objects.equals(mime, mime2)) {
                                ext[0] = ext2;
                            }
                        });
                        name2 = name + (ext[0] == null ? "" : ext[0]);
                    }
                }
                print(url, name2);

                if (file instanceof ZipRoot)
                    ((ZipRoot) file).addRepackFile(temp, name2);
                else {
                    DirectoryRoot dr = (DirectoryRoot) file;
                    Files.copy(temp, dr.root.resolve(name2), StandardCopyOption.REPLACE_EXISTING);
                }
                addBuffer(buffer);
                return;
            } catch (Exception e) {
                if(!(e instanceof InterruptedException))
                    System.out.println(red("failed download: ")+url+"  "+e.getClass().getSimpleName()+"  "+e.getMessage());
            }

            for (Closeable c : new Closeable[] {ros, is, fos}) {
                try {
                    if(c != null)
                        c.close();
                } catch (IOException e) {}
            }
            addBuffer(buffer);
        }));
    }
}
