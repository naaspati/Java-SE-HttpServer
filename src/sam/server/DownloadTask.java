package sam.server;

import static sam.server.ServerUtils.*;
import static sam.server.Utils.bytesToString;
import static sam.server.Utils.cyan;
import static sam.server.Utils.durationToString;
import static sam.server.Utils.green;
import static sam.server.Utils.print;
import static sam.server.Utils.red;
import static sam.server.Utils.resave_cursor;
import static sam.server.Utils.save_cursor;
import static sam.server.Utils.yellow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpExchange;

public class DownloadTask implements Callable<DownloadTask.DownloadResult> {
    private static final ConcurrentMap<URL, Downloaded> downloaded = new ConcurrentHashMap<>();

    public static Downloaded getDownloaded(URL url) {
        return downloaded.get(url);
    }

    public static final class DownloadResult {
        private final Path path;
        private final String name;

        public DownloadResult(Path path, String name) {
            this.path = path;
            this.name = name;
        }
        public Path getPath() {    return path; }
        public String getName() { return name; }
    }

    private final URL url;
    private final String name;
    private final HttpExchange exchange;

    String mime = null;

    private long last = System.currentTimeMillis();
    private int lastTotal = 0, speed = 0;
    private int bytesRead;
    private String format;
    private double total;
    private final AtomicBoolean canceller;
    private boolean downloadableAsServerResource;

    public DownloadTask(AtomicBoolean canceller, URL url, String name, HttpExchange exchange, boolean downloadableAsServerResource) {
        this.url = url;
        this.name = name;
        this.exchange = exchange;
        this.canceller = canceller;
        this.downloadableAsServerResource = downloadableAsServerResource;
    }
    private boolean isCancelled() {
        return canceller.get();
    }

    @Override
    public DownloadResult call() throws Exception {
        if(isCancelled()) return null;

        InputStream inputStream = null;
        OutputStream responseBody = null;
        OutputStream file = null;

        try {
            save_cursor();
            System.out.println(yellow("downloading: ")+url);
            URLConnection con = getConnection();

            if(isCancelled()) return null;

            total = con.getContentLength();
            mime = con.getContentType();
            inputStream = con.getInputStream();
            String name2 = prepareName(name, mime);

            Path path;
            if(name2 != null && Files.exists(path = LOOK_DOWNLOADS_DIR.resolve(name2))) {
                Server.sendFile(path, exchange, name2, url);
                return new DownloadResult(path, name2);
            }

            responseBody = total <= 0 ? null : setSendHeader(exchange, (long)total, mime);
            Path temp  = Files.createTempFile("server-download", "");
            file = Files.newOutputStream(temp);

            format = "%s / "+green(bytesToString(total)) + cyan(" | ") + (total < 0 ? red(" -- ") : yellow(" %.2f%%")) + cyan(" | ") + "%d Kb/sec"
                    + (total < 0 ? "" : cyan(" | ") + yellow("time-left: ") + " %s");

            byte[] buffer = createBuffer();
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
                responseBody = setSendHeader(exchange, bytesRead == n ? bytesRead : Files.size(temp), mime);
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

            if(downloadableAsServerResource) {
                Files.move(temp, DOWNLOADS_DIR.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                print(url , yellow("downloaded/"+name));    
                return null;
            }
            name2 = prepareName(name, mime);
            print(url, name2);
            return new DownloadResult(temp, name2);
        } finally {
            Utils.closeThese(responseBody, inputStream, file);
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
        con.setConnectTimeout(CONNECT_TIMEOUT);
        con.setReadTimeout(READ_TIMEOUT);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/62.0.3202.89 Safari/537.36");

        return con;
    }
    private String prepareName(String name, String mime) {
        if(name == null || mime == null)
            return name;

        if(name.matches("-?\\d+") ) {
            String ext = getFileExtenstionUsingMime(mime);
            name = name + (ext == null ? "" : ext);
        }
        return name;
    }
}
