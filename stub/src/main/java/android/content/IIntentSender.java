package android.content;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IIntentSender extends IInterface {

    void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
              IIntentReceiver finishedReceiver, String requiredPermission,
              Bundle options) throws RemoteException;

    abstract class Stub extends Binder implements IIntentSender {

        @Override
        public IBinder asBinder() {
            return this;
        }

        public static IIntentSender asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
