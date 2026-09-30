package android.bluetooth;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/** Sólo el tipo (ver BluetoothAdapter). */
public abstract class BluetoothSocket implements Closeable {
    public abstract void connect() throws IOException;
    public abstract boolean isConnected();
    public abstract OutputStream getOutputStream() throws IOException;
}
