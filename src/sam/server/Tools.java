package sam.server;

import java.time.Duration;

public final class Tools {
    static boolean NO_COLOR = false; 
    
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

    static synchronized void resave_cursor() {
        unsave_cursor();
        erase_down();
        save_cursor();
    }
}
