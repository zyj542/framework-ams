package com.example.compute;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 客户端:绑定远程 Service,拿到 IBinder 后转成 ICompute 接口,像调本地方法一样调用。
 */
public class MainActivity extends AppCompatActivity {

    private ICompute mCompute;        // 拿到的是 Proxy(如果跨进程)或本地 Stub(如果同进程)
    private TextView mResultText;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // service 就是 Binder 驱动送回来的 IBinder 引用
            // asInterface() 内部:先查本地有没有同进程的 Stub,没有就包一层 Proxy
            mCompute = ICompute.Stub.asInterface(service);
            try {
                // 跨进程调用!参数被 Proxy 打包 → 驱动传输 → Stub 解包 → 真正的 add() 执行
                int result = mCompute.add(3, 4);
                mResultText.setText("3 + 4 = " + result);
            } catch (RemoteException e) {
                mResultText.setText("调用失败: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // 服务端进程死亡时回调
            mCompute = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mResultText = findViewById(R.id.result_text);

        Intent intent = new Intent(this, ComputeService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mConnection);
    }
}
