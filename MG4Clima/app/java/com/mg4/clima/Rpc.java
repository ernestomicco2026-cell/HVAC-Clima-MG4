package com.mg4.clima;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Chiamate binder "a mano" verso i servizi SAIC: stesso protocollo dei proxy AIDL generati
 * (interface token + argomenti, poi readException + risultato), senza dipendere dalle loro classi.
 */
public final class Rpc {
    private Rpc() {}

    /** Scrive gli argomenti della chiamata nel parcel. */
    public interface Args {
        void write(Parcel p);
    }

    /** Legge il risultato dal parcel di risposta. */
    public interface Reader<T> {
        T read(Parcel p);
    }

    public static final Args NONE = p -> {};

    public static <T> T call(IBinder b, String descriptor, int code, Args args, Reader<T> reader)
            throws RemoteException {
        if (b == null) throw new RemoteException("binder non disponibile");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (args != null) args.write(data);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reader == null ? null : reader.read(reply);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static void call(IBinder b, String descriptor, int code, Args args) throws RemoteException {
        call(b, descriptor, code, args, null);
    }

    public static int callInt(IBinder b, String descriptor, int code) throws RemoteException {
        Integer v = call(b, descriptor, code, NONE, Parcel::readInt);
        return v == null ? 0 : v;
    }

    public static float callFloat(IBinder b, String descriptor, int code) throws RemoteException {
        Float v = call(b, descriptor, code, NONE, Parcel::readFloat);
        return v == null ? 0f : v;
    }

    public static boolean callBool(IBinder b, String descriptor, int code) throws RemoteException {
        Integer v = call(b, descriptor, code, NONE, Parcel::readInt);
        return v != null && v != 0;
    }

    public static String callString(IBinder b, String descriptor, int code) throws RemoteException {
        return call(b, descriptor, code, NONE, Parcel::readString);
    }

    public static IBinder callBinder(IBinder b, String descriptor, int code, Args args) throws RemoteException {
        return call(b, descriptor, code, args, Parcel::readStrongBinder);
    }

    /** Legge un Parcelable restituito con la convenzione AIDL (int 0 = null). */
    public static <T> T readNullable(Parcel p, Reader<T> body) {
        return p.readInt() != 0 ? body.read(p) : null;
    }
}
