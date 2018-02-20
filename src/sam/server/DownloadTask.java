package sam.server;

import static sam.server.Tools.bytesToString;
import static sam.server.Tools.cyan;
import static sam.server.Tools.durationToString;
import static sam.server.Tools.green;
import static sam.server.Tools.print;
import static sam.server.Tools.red;
import static sam.server.Tools.resave_cursor;
import static sam.server.Tools.save_cursor;
import static sam.server.Tools.yellow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpExchange;

public class DownloadTask implements Runnable {
    private static final ConcurrentMap<URL, Downloaded> downloaded = new ConcurrentHashMap<>();

    public static Downloaded getDownloaded(URL url) {
        return downloaded.get(url);
    }

    private final Server server;
    private final URL url;
    private final String name;
    private final HttpExchange exchange;
    private final BufferManeger bufferManeger = BufferManeger.getInstance();
    private final Consumer<DownloadTask> whenFinished;

    byte[] buffer = null;
    String mime = null;

    private long last = System.currentTimeMillis();
    private int lastTotal = 0, speed = 0;
    private int bytesRead;
    private String format;
    private double total;

    public DownloadTask(Server server, URL url, String name, HttpExchange exchange, Consumer<DownloadTask> whenFinished) {
        this.server = server;
        this.url = url;
        this.name = name;
        this.exchange = exchange;
        this.whenFinished = whenFinished;
    }

    @Override
    public void run() {
        InputStream inputStream = null;
        OutputStream responseBody = null;
        OutputStream file = null;

        try {
            save_cursor();
            System.out.println(yellow("downloading: ")+url);
            URLConnection con = getConnection();

            total = con.getContentLength();
            mime = con.getContentType();
            inputStream = con.getInputStream();
            String name2 = prepareName(name, mime);

            Path path;
            if(name2 != null && Files.exists(path = server.getLookDownloadDir().resolve(name2))) {
                server.sendFile(path, exchange, name2, url);
                server.setResourceToRootFile(path, name2);
                return;
            }

            responseBody = total <= 0 ? null : server.setSendHeader(exchange, (long)total, mime);
            Path temp  = Files.createTempFile("server-download", "");
            file = Files.newOutputStream(temp);

            format = "%s / "+green(bytesToString(total)) + cyan(" | ") + (total < 0 ? red(" -- ") : yellow(" %.2f%%")) + cyan(" | ") + "%d Kb/sec"
                    + (total < 0 ? "" : cyan(" | ") + yellow("time-left: ") + " %s");

            buffer = bufferManeger.getbuffer();
            int n = 0;
            while((n = inputStream.read(buffer)) > 0) {
                file.write(buffer, 0, n);
                if(responseBody != null)
                    responseBody.write(buffer, 0, n);

                bytesRead += n;
                if(responseBody != null)
                    progress();
            }
            if(responseBody == null) {
                file.close();
                responseBody = server.setSendHeader(exchange, bytesRead == n ? bytesRead : Files.size(temp), mime);
                if(bytesRead == n)
                    responseBody.write(buffer, 0, n);
                else {
                    n = 0;
                    try(InputStream is = Files.newInputStream(temp)) {
                        while((n = is.read(buffer)) > 0)
                            responseBody.write(buffer, 0, n);
                    }
                } 
            }
            downloaded.put(url, new Downloaded(temp, mime));

            if(server.isServerDownloadableResource(url)) {
                Files.move(temp, server.getDownloadsDir().resolve(name), StandardCopyOption.REPLACE_EXISTING);
                print(url , yellow("downloaded/"+name));    
                return;
            }
            name2 = prepareName(name, mime);
            print(url, name2);
            server.setResourceToRootFile(temp, name2);
        } catch (Exception e) {
            if(!(e instanceof InterruptedException)) {
                System.out.println(
                        red("failed download: ")+url+" , "+prepareName(name, mime)+"  ["+
                                e.getClass().getSimpleName()+"]  "+(e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        finally {
            Tools.closeThese(responseBody, inputStream, file);
            bufferManeger.addBuffer(buffer);
            whenFinished.accept(this);
        }
    }
    private void progress() {
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

    private URLConnection getConnection() throws IOException {
        URLConnection con = url.openConnection();
        con.setConnectTimeout(server.getConnectTimeout());
        con.setReadTimeout(server.getReadTimeout());
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/62.0.3202.89 Safari/537.36");

        return con;
    }
    private String prepareName(String name, String mime) {
        if(name == null || mime == null)
            return name;

        if(name.matches("-?\\d+") ) {
            if(mime != null) {
                String ext = server.getFileExtenstionUsingMime(mime);
                name = name + (ext == null ? "" : ext);
            }
        }
        return name;
    }
}
