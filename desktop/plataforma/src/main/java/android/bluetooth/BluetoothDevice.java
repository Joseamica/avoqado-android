package android.bluetooth;

import java.io.IOException;
import java.util.UUID;

/** Sólo el tipo: en escritorio nunca hay adaptador que entregue uno. */
public abstract class BluetoothDevice {
    public abstract BluetoothSocket createRfcommSocketToServiceRecord(UUID uuid) throws IOException;
    public abstract BluetoothClass getBluetoothClass();
    public abstract String getName();
    public abstract String getAddress();
}
