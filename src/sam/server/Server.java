package sam.server;

import static sam.server.ServerUtils.DOWNLOADS_DIR;
import static sam.server.ServerUtils.LOOK_DOWNLOADS_DIR;
import static sam.server.ServerUtils.getMime;
import static sam.server.ServerUtils.pipe;
import static sam.server.ServerUtils.setSendHeader;
import static sam.server.Utils.green;
import static sam.server.Utils.print;
import static sam.server.Utils.yellow;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import sam.console.ansi.ANSI;
import sam.server.DownloadTask.DownloadResult;
import sam.server.root.DirectoryRoot;
import sam.server.root.FileUnit;
import sam.server.root.ServerRoot;
import sam.server.root.ZipRoot;

public class Server extends ThreadPoolExecutor implements AutoCloseable {
    private final URI rootUri;

    final Predicate<String> downloadResourcesTester;

    private volatile AtomicBoolean canceller;
    private volatile ServerRoot file;
    private final HttpServer hs;
    private Thread shutDownHook = ServerUtils.addShutdownHook(this); 

    private final Predicate<String> downloadAsServerResourcesPredicate;
    private final InetSocketAddress runningAt;
    
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);

            if(r == null)
                return;

            @SuppressWarnings("unchecked")
            Future<DownloadResult> f = (Future<DownloadResult>)r;
            DownloadResult d = null;
            try {
                d = f.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }

            if(d == null)
                return;

            synchronized (file) {
                if(file == null)
                    return;
                
                Path path = d.getPath();
                String name = d.getName(); 

                if (file instanceof ZipRoot)
                    ((ZipRoot) file).addRepackFile(path, name);
                else {
                    DirectoryRoot dr = (DirectoryRoot) file;
                    try {
                        Files.copy(path, dr.root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        System.out.println("failed to copy: "+path+" -> "+dr.root.resolve(name)+"  error: "+e);
                    }
                }
            }
        }
        
    public Server(int port) throws Exception {
        super(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
        
        runningAt = new InetSocketAddress("localhost", port);
        rootUri = new URI("/");
        
        ResourceBundle rb = ResourceBundle.getBundle("1509617391333-server_config");
        
        downloadAsServerResourcesPredicate = new Tester(rb.getString("download.as.server.resources"));        
        downloadResourcesTester = new Tester(rb.getString("download.resources"));

        ResourceBundle.clearCache();
        hs = HttpServer.create(runningAt, 10);

        hs.createContext(rootUri.toString(), new SimpleHandler());
        // handle caching resource 
        hs.createContext("/download", new DownloadHandler());
    }

    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
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
                Path path = DOWNLOADS_DIR.resolve(name);
                if(Files.notExists(path))
                    downloadAction(name, url, exchange);
                else
                    sendFile(path, exchange, name, url);
            }
        }
    }

    private class SimpleHandler implements HttpHandler {
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
                        if(downloadResourcesTester.test(m.group(2)))
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
    }

    public boolean isServerDownloadableResource(URL url){
        return downloadAsServerResourcesPredicate.test(url.toString());
    }
    static void sendFile(Path path, HttpExchange exchange, String name, URL url) {
        try(OutputStream resposeBody = setSendHeader(exchange, Files.size(path), getMime(name))) {
            Files.copy(path, resposeBody);
            print(url , path.subpath(path.getNameCount() - 2, path.getNameCount()));
        } catch (Exception e) {
            System.out.println("failed to send file: ");
            print(url , path.subpath(path.getNameCount() - 2, path.getNameCount()));
        }
    }

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

    public void start(Path root, boolean openInBrowser) throws IOException {
        createNewServerRoot(root);
        hs.start();
        System.out.println(yellow("server running at: \n")+
                "    localhost:"+ runningAt.getPort()+
                "\n    "+runningAt.getAddress().getHostAddress() + ":"+runningAt.getPort());
        System.out.println(ANSI.yellow("DOWNLOADS_DIR\n")+DOWNLOADS_DIR.toUri());
        System.out.println(ANSI.yellow("LOOK_DOWNLOADS_DIR\n")+LOOK_DOWNLOADS_DIR.toUri());
        System.out.println("\n");

        if (openInBrowser)
            Runtime.getRuntime().exec("explorer http://localhost:" + runningAt.getPort());
    }

    private void createNewServerRoot(Path root) throws IOException {
        if (Files.notExists(root))
            throw new FileNotFoundException(root.toString());

        closeRoot();
        file = Files.isRegularFile(root) ? new ZipRoot(root) : new DirectoryRoot(root);
        canceller = new AtomicBoolean(false);
    }
    public void closeRoot() throws IOException {
        if(file != null) {
            canceller.set(true);
            file.close();
            getQueue().stream().filter(r -> r instanceof Future).map(Future.class::cast).forEach(f -> f.cancel(true));
            getQueue().clear();
            file = null;
            canceller = null;
        }
    }

    public void changeRoot(Path path, boolean openInBrowser) throws IOException {
        createNewServerRoot(path);

        if (openInBrowser)
            Runtime.getRuntime().exec("explorer http://localhost:"+runningAt.getPort());

        System.out.println(green("\nroot changed to:  "+file.getRoot()));
    }

    protected void downloadAction(final String name, final URL url, final HttpExchange exchange) {
        Downloaded dd = DownloadTask.getDownloaded(url);
        if(dd != null && Files.exists(dd.getDownloadPath())) {
            sendFile(dd.getDownloadPath(), exchange, name, url);
            return;
        }
        
        submit(new DownloadTask(canceller, url, name, exchange, isServerDownloadableResource(url)));
    }

    @Override
    public void close() throws Exception {
        Runtime.getRuntime().removeShutdownHook(shutDownHook);
        shutDownHook.start();
    }
}
