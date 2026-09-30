package android.bluetooth;

import java.util.Set;

/** Sólo el tipo: en escritorio nunca hay adaptador (BluetoothManager.getAdapter() es null). */
public abstract class BluetoothAdapter {
    public abstract boolean isEnabled();
    public abstract BluetoothDevice getRemoteDevice(String address);
    public abstract boolean cancelDiscovery();
    public abstract Set<BluetoothDevice> getBondedDevices();
}
