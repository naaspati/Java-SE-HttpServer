
import java.nio.file.Paths;

import sam.server.Server;
import sam.server.Utils;

public class Main {
    public static void main(String[] args) throws Exception {
        if(args.length == 0)
            args = new String[] {"."};
        
        if (args[0].equals("-h")) {
            printUsage();
            System.exit(0);
        }
        if(args[0].equals("-v")) {
            System.out.println(1.453);
            System.exit(0);
        }

        Server s = new Server(8080);
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

        System.out.println(Utils.yellow(usage));
    }
}
