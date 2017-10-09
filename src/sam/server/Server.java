package sam.server;

import static sam.server.Tools.bytesToString;
import static sam.server.Tools.cyan;
import static sam.server.Tools.durationToString;
import static sam.server.Tools.green;
import static sam.server.Tools.pipe;
import static sam.server.Tools.red;
import static sam.server.Tools.resave_cursor;
import static sam.server.Tools.save_cursor;
import static sam.server.Tools.yellow;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.kamranzafar.jddl.DirectDownloader;
import org.kamranzafar.jddl.DownloadListener;
import org.kamranzafar.jddl.DownloadTask;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Server {
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            printUsage();
            System.exit(0);
        }

        Server s = new Server("localhost", 8080);
        s.start(Paths.get(args[0]), true);//args.length > 1 ? "--open".equals(args[1]) : false);
    }

    private static void printUsage() {
        String usage = "" + "usage: java Server [zipfile/folder]"
                /**
                 * + " [options] \n\n" 
                 * +"options:\n" 
                 * + "--port       Port to use [8080]" 
                 * + "--address    Address to use [localhost]"
                 */
                 ;

        System.out.println(yellow(usage));
    }

    static final URI ROOT_URI;

    static {
        URI u = null;
        try {
            u = new URI("/");
        } catch (URISyntaxException e) {
        }
        ROOT_URI = u;
    }

    private ServerRoot file;
    private final HttpServer hs;
    private final Map<String, String> fileext_mimeMap;
    private final int port;

    public Path getRoot(int port) {
        return file == null ? null : file.getRoot();
    }

    public Server(String address, int port) throws IOException {
        this.port = port;

        try(InputStream is = getClass().getClassLoader().getResourceAsStream("file.ext-mime.tsv");
                InputStreamReader reader = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(reader)) {

            fileext_mimeMap = br.lines()
                    .filter(s -> !s.startsWith("#") && s.indexOf('\t') > 0).map(s -> s.split("\t"))
                    .collect(Collectors.toMap(s -> s[2], s -> s[1], (o, n) -> n));
        }

        hs = HttpServer.create(new InetSocketAddress(address, port), 10);
        hs.createContext(ROOT_URI.toString(), new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                URI uri = exchange.getRequestURI();

                if (uri.equals(ROOT_URI))
                    uri = ROOT_URI.resolve("index.html");

                InputStream is = file.getInputStream(uri);

                if (is == null) {
                    uri = exchange.getRequestURI();
                    List<String> dir = file.walkDirectory(uri);
                    if (dir != null) {
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
                        final byte[] bytes = sb.toString().getBytes();
                        exchange.getResponseHeaders().add("Content-Type", "text/html");
                        exchange.sendResponseHeaders(200, bytes.length);
                        OutputStream resposeBody = exchange.getResponseBody();
                        resposeBody.write(bytes);
                        resposeBody.close();
                    } else {
                        System.out.println(uri + red("  ->  null"));
                        exchange.sendResponseHeaders(404, -1);
                    }
                    return;
                }
                String name = file.getName(uri);
                System.out.println(uri + yellow(" -> ") + name);
                OutputStream resposeBody = exchange.getResponseBody();
                exchange.getResponseHeaders().add("Content-Type", getMime(name));

                // replace link which may create CORS error // i'm using this to download constant resource(s)
                if (name.endsWith("js")) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
                    int b = 0;
                    while ((b = is.read()) > -1)
                        bos.write(b);

                    String str = new String(bos.toByteArray()).replaceAll("([\"'])(https?://)", "$1/download?$2");
                    byte[] bytes = str.getBytes();
                    exchange.sendResponseHeaders(200, bytes.length);
                    resposeBody.write(bytes);
                } else {
                    exchange.sendResponseHeaders(200, file.getSize(uri));
                    pipe(is, resposeBody);
                }
                resposeBody.close();
                is.close();
            }
        });
        
        // handle CORS replacement
        hs.createContext("/download", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                URL url = new URL(exchange.getRequestURI().getQuery());
                InputStream is = null;
                long size = 0;
                String name = null;
                final URI uri = ROOT_URI.resolve(new File(url.getPath()).getName());
                is = file.getInputStream(uri);

                if (is != null) {
                    size = file.getSize(uri);
                    name = file.getName(uri);
                    exchange.getResponseHeaders().add("Content-Type", getMime(name));
                    exchange.sendResponseHeaders(200, size);
                    OutputStream resposeBody = exchange.getResponseBody();
                    pipe(is, resposeBody);
                    System.out.println(url + "  " + name);
                    is.close();
                    resposeBody.close();
                } else {
                    try {
                        downloadAction(url, exchange);
                    } catch (InterruptedException e) {
                        System.out.println("download failed: " + url);
                    }
                }
            }
        });
    }
    private String getMime(String fileName) throws IOException {
        final int index = fileName.lastIndexOf('.');
        if (index < 0) {
            String mime = Files.probeContentType(Paths.get(fileName));
            return mime == null ? "text/plain" : mime;
        }
        String mime = fileext_mimeMap.get(fileName.substring(index));
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
        System.out.println(yellow("server running at: localhost:" + port));
        if (openInBrowser)
            Runtime.getRuntime().exec("explorer http://localhost:" + port);
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
            Runtime.getRuntime().exec("explorer http://localhost:" + port);
        
        System.out.println(green("\nroot changed to:  "+file.getRoot()));
    }

    public void closeRoot() throws IOException {
        if(downloader != null) {
            downloader.cancelAll();
            downloader.shutdown();
            downloader = null;
        }

        if (file != null) {
            file.close();
            file = null;
        }
    }

    private DirectDownloader downloader;

    protected void downloadAction(URL url, HttpExchange exchange) throws IOException, InterruptedException {

        if (downloader == null) {
            downloader = new DirectDownloader();
            Thread thread = new Thread(downloader);
            thread.setDaemon(true);
            thread.start();
            thread.join();
        }
        
        Path temp1 = Paths.get("temp", String.valueOf(System.currentTimeMillis()));
        while(Files.exists(temp1)) temp1 = temp1.resolveSibling(String.valueOf(System.currentTimeMillis()));
        Files.createDirectories(temp1.getParent());
        Path temp = temp1;
        
        FileOutputStream fs = new FileOutputStream(temp1.toFile());
        downloader.download(new DownloadTask(url, fs, new DownloadListener() {
            double total;
            long last = System.currentTimeMillis();
            int lastTotal = 0;
            int speed = 0;
            String format;

            @Override
            public void onUpdate(int bytes, int totalDownloaded) {
                long timepassed = System.currentTimeMillis() - last;
                if (timepassed >= 1000) {
                    double downloaded = totalDownloaded - lastTotal;
                    speed = (int) ((downloaded / timepassed) * (1000d / 1024d));
                    lastTotal = totalDownloaded;
                    last = System.currentTimeMillis();

                    resave_cursor();

                    if (total < 0)
                        System.out.printf(format, bytesToString(totalDownloaded), speed);
                    else
                        System.out.printf(format, bytesToString(totalDownloaded), (totalDownloaded * 100d) / total,
                                speed, durationToString(
                                        Duration.ofSeconds((long) (((total - totalDownloaded) / 1024) / speed))));
                }
            }

            @Override
            public void onStart(String fname, int fsize) {
                total = fsize;
                System.out.println(yellow(url));
                System.out.println(yellow("file-size: ") + (fsize < 0 ? red(" -- ") : bytesToString(fsize)));
                format = "%s" + cyan(" | ") + (fsize < 0 ? red(" -- ") : green(" %.2f%%")) + cyan(" | ") + "%d Kb/sec"
                        + (fsize < 0 ? "" : cyan(" | ") + yellow("time-left: ") + " %s");
                save_cursor();
            }

            @Override
            public void onComplete() {
                resave_cursor();
                try {
                    fs.flush();
                    fs.close();

                    Path name = Paths.get(url.getPath()).getFileName();

                    if (file instanceof ZipRoot)
                        ((ZipRoot) file).addFile(temp, name.toString());
                    else {
                        DirectoryRoot dr = (DirectoryRoot) file;
                        Files.copy(temp, dr.root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }

                    exchange.getResponseHeaders().add("Content-Type", getMime(name.toString()));
                    exchange.sendResponseHeaders(200, (long) total);
                    OutputStream resposeBody = exchange.getResponseBody();

                    Files.copy(temp, resposeBody);
                    resposeBody.close();
                } catch (IOException e) {
                }
            }

            @Override
            public void onCancel() {
                resave_cursor();
                try {
                    fs.close();
                    Files.deleteIfExists(temp);
                } catch (IOException e) {}
                System.out.println(red("CANCELLED"));
            }
        }));
    }
}
