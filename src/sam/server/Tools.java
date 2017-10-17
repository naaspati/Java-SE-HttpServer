package sam.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;

public final class Tools {
    static boolean NO_COLOR = false; 
    
    public static void pipe(InputStream is, OutputStream resposeBody) throws IOException {
        byte[] bytes = new byte[1024];
        int n = 0;
        while ((n = is.read(bytes)) > 0)
            resposeBody.write(bytes, 0, n);
    }
    
    static String durationToString(Duration duration) {
        return duration.toString().replace("PT", "").replace("M", "min ").replace("S", "sec ").replace("H", "hr ");
    }

    static String bytesToString(double size) {
        return String.format("%.2f", size / (size < 1048576 ? 1024 : 1048576)) + (size < 1048576 ? "Kb" : "Mb");
    }

    static String cyan(Object obj) {
        if(NO_COLOR) return String.valueOf(obj);
        return "\u001b[36m" + obj + "\u001b[0m";
    }

    static String green(Object obj) {
        if(NO_COLOR) return String.valueOf(obj);
        return "\u001b[32m" + obj + "\u001b[0m";
    }

    public static String yellow(Object obj) {
        if(NO_COLOR) return String.valueOf(obj);
        return "\u001b[33m" + obj + "\u001b[0m";
    }

    public static String red(Object obj) {
        if(NO_COLOR) return String.valueOf(obj);
        return "\u001b[31m" + obj + "\u001b[0m";
    }
}
