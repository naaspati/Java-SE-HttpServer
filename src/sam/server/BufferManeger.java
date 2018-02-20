package sam.server;

import java.util.concurrent.ConcurrentLinkedQueue;

public class BufferManeger extends ConcurrentLinkedQueue<byte[]> {
    private static final long serialVersionUID = 4263639222253225606L;
    
    private static transient BufferManeger instance;

    public static BufferManeger getInstance() {
        if (instance == null) {
            synchronized (BufferManeger.class) {
                if (instance == null)
                    instance = new BufferManeger();
            }
        }
        return instance;
    }

    private final int bufferSize = 256*1024; 
    
    private BufferManeger() {}
    
    public void addBuffer(byte[] buffer) {
        if(buffer != null)
            add(buffer);
    }
    public byte[] getbuffer() {
        byte[] bytes = poll();
        if(bytes == null)
            return new byte[bufferSize];
        else
            return bytes;
    }
    

}
