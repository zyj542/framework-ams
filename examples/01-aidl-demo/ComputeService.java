package com.example.compute;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * 服务端:把 ICompute 接口的实现放进一个 Service。
 * 注意 AndroidManifest.xml 里给这个 Service 加上 android:process=":compute",
 * 让它跑在独立进程,才能真正演示「跨进程」Binder 调用。
 */
public class ComputeService extends Service {

    private static final String TAG = "ComputeService";

    /**
     * 继承 AIDL 生成的 ICompute.Stub(它 extends Binder),实现业务逻辑。
     * 客户端跨进程调用的 add() 最终会在这个对象上执行。
     */
    private final ICompute.Stub mBinder = new ICompute.Stub() {
        @Override
        public int add(int a, int b) throws RemoteException {
            // 注意:这里运行在 Binder 线程池的线程上,不是 Service 所在进程的主线程!
            Log.d(TAG, "add(" + a + ", " + b + ") 执行于线程: "
                    + Thread.currentThread().getName());
            return a + b;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        // 系统把 mBinder 交给 Binder 驱动,客户端才能拿到它的引用
        return mBinder;
    }
}
