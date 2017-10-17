package sam.server;

import static sam.server.Tools.bytesToString;
import static sam.server.Tools.cyan;
import static sam.server.Tools.durationToString;
import static sam.server.Tools.green;
import static sam.server.Tools.red;
import static sam.server.Tools.yellow;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;

import com.sun.net.httpserver.HttpExchange;

public class DownloadThread implements Runnable {

    public static final int READ_TIMEOUT, CONNECT_TIMEOUT;
    static {
        int rt = 0, ct = 0;
        try {
            Properties rb = new Properties();
            rb.load(DownloadThread.class.getClassLoader().getResourceAsStream("server_config.properties"));
            rt = Integer.parseInt(rb.getProperty("read.timeout", "10000"));
            ct = Integer.parseInt(rb.getProperty("connect.timeout", "2000"));
        } catch (Exception e) {}
        READ_TIMEOUT = rt;
        CONNECT_TIMEOUT = ct;
    }

    private final LinkedBlockingQueue<DownloadTask> tasks = new LinkedBlockingQueue<>();

    private boolean cancel;
    private boolean kill;
    private double total;
    private long last = System.currentTimeMillis();
    private int lastTotal = 0;
    private int speed = 0;
    private String format;
    private DownloadTask task;
    private  Path temp;
    private FileOutputStream fs;
    private final byte[] buffer = new byte[8*1024];
    private String contentType;

    @Override
    public void run() { 
        System.out.println("Downloader started");
        System.out.println("read_timeout: "+READ_TIMEOUT);
        System.out.println("connection_timeout: "+CONNECT_TIMEOUT);
        System.out.println();
        while(true) {
            try {
                task = tasks.take();
            } catch (InterruptedException e) {
                if(kill) {
                    onCancel(e);
                    break;
                }
                else
                    continue;
            }
            try {
                reset();
                
                System.out.println(yellow("downloading: ")+task.url);

                URLConnection con = task.url.openConnection();
                con.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/61.0.3163.100 Safari/537.36");
                con.setConnectTimeout(CONNECT_TIMEOUT);
                con.setReadTimeout(READ_TIMEOUT);
                con.connect();
                
                save_cursor();
                onStart(con.getContentLength(), con.getContentType());

                temp = Files.createTempFile("server", "");
                fs = new FileOutputStream(temp.toFile());

                int bytesRead = 0;
                int n = 0;
                InputStream is = con.getInputStream();
                while((n = is.read(buffer)) > 0) {
                    if(cancel || kill)
                        break;
                    bytesRead += n;
                    task.write(buffer, 0, n);
                    fs.write(buffer, 0, n);
                    onUpdate(bytesRead); 
                }
                is.close();
                onComplete();
                if(kill)
                    break;
            } catch (IOException e) {
                onCancel(e);
                if(kill)
                    break;
            }
        }
    }
    private void reset() throws IOException {
        total = 0;
        last = System.currentTimeMillis();
        lastTotal = 0;
        speed = 0;
        format = null;
        temp = null;
        cancel = false;
        kill = false;
        if(fs != null) {
            fs.close();
            fs = null;
        }
    }

    private void onUpdate(int totalDownloaded) {
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

    private void onStart(int fsize, String contentType) throws IOException {
        total = fsize;
        System.out.println(yellow("file-size: ") + (fsize < 0 ? red(" -- ") : bytesToString(fsize)));
        format = "%s" + cyan(" | ") + (fsize < 0 ? red(" -- ") : green(" %.2f%%")) + cyan(" | ") + "%d Kb/sec"
                + (fsize < 0 ? "" : cyan(" | ") + yellow("time-left: ") + " %s");

        this.contentType = contentType;
        task.start(fsize, contentType);
    }

    private void onComplete() {
        resave_cursor();
        try {
            fs.flush();
            fs.close();
            task.close();
            task.onComplete(temp, contentType);
        } catch (IOException e) {
            task.onFailed(e);
        }
    }
    private void onCancel(Exception e2) {
        resave_cursor();
        try {
            if(fs != null)
                fs.close();
            task.close();
            if(temp != null)
                Files.deleteIfExists(temp);
            task.onFailed(e2);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void download(DownloadTask task) {
        tasks.add(task);
    }
    public void cancelAll() {
        tasks.clear();
        cancel = true;
    }

    private static boolean saved;
    static void save_cursor() {
        saved = true;
        System.out.print("\u001b[s");
    }

    static void unsave_cursor() {
        if(!saved)
            return;
        System.out.print("\u001b[u");
        saved = false;
    }

    static void erase_down() {
        System.out.print("\u001b[J");
    }

    static void resave_cursor() {
        unsave_cursor();
        erase_down();
        save_cursor();
    }
}
abstract class DownloadTask implements Closeable, AutoCloseable {
    final URL url;
    private final HttpExchange exchange;
    private final OutputStream os;

    public DownloadTask(URL url, HttpExchange exchange) {
        this.url = url;
        this.exchange = exchange;
        os = exchange.getResponseBody();
    }

    void write(byte[] b, int off, int len) throws IOException {
        if(closed)
            return;
        os.write(b, off, len);
    }

    private boolean headerSent = false;
    void start(int fsize, String contentType) throws IOException {
        if(closed)
            return;
        exchange.getResponseHeaders().add("Content-Type", contentType == null ? "text/plain" : contentType);
        exchange.sendResponseHeaders(200, fsize < 0 ? 0 : fsize);
        headerSent = true;
    }
    public abstract void onComplete(Path path, String contentType);
    public abstract void onFailed(Exception exception);

    private boolean closed = false;

    @Override
    public void close() throws IOException {
        if(closed)
            return;
        closed = true;
        if(!headerSent)
            exchange.sendResponseHeaders(404, -1);
        os.close();
    }
}
