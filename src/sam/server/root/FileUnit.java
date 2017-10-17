package sam.server.root;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

public final class FileUnit implements AutoCloseable, Closeable {
    private final long size;
    private final String name;
    private final InputStream inputStream;

    public long getSize() {
        return size;
    }
    public String getName() {
        return name;
    }
    public InputStream getInputStream() {
        return inputStream;
    }
    public FileUnit(String name, long size, InputStream inputStream) {
        this.size = size;
        this.name = name;
        this.inputStream = inputStream;
    }
    @Override
    public void close() throws IOException {
        inputStream.close();
    }
    

}
