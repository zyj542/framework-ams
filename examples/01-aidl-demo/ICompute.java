/*
 * 这是 AIDL 编译器自动生成的代码(还原版),与 AOSP 8.1 时代 aidl 工具的输出格式一致。
 * 真实工程里请勿手写此文件 —— Build 后它会自动出现在:
 *   AGP 3.x (AS 3.x)      : build/generated/source/aidl/<variant>/com/example/compute/ICompute.java
 *   AGP 7+  (AS 4.2+/新项目): build/generated/aidl_source_output_dir/<variant>/out/com/example/compute/ICompute.java
 * 本文件用于「读懂它」,理解了它,AMS 的 IActivityManager.java 骨架就一目了然。
 */
package com.example.compute;

// Declare any non-default types here with import statements

public interface ICompute extends android.os.IInterface
{
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.example.compute.ICompute
  {
    private static final java.lang.String DESCRIPTOR = "com.example.compute.ICompute";

    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }

    /**
     * Cast an IBinder object into an com.example.compute.ICompute interface,
     * generating a proxy if needed.
     */
    public static com.example.compute.ICompute asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.example.compute.ICompute))) {
        return ((com.example.compute.ICompute)iin);
      }
      return new com.example.compute.ICompute.Stub.Proxy(obj);
    }

    @Override
    public android.os.IBinder asBinder()
    {
      return this;
    }

    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
        case TRANSACTION_add:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          int _result = this.add(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
    }

    private static final int TRANSACTION_add = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }

  private static class Proxy implements com.example.compute.ICompute
  {
    private android.os.IBinder mRemote;

    Proxy(android.os.IBinder remote)
    {
      mRemote = remote;
    }

    @Override
    public android.os.IBinder asBinder()
    {
      return mRemote;
    }

    public java.lang.String getInterfaceDescriptor()
    {
      return DESCRIPTOR;
    }

    @Override
    public int add(int a, int b) throws android.os.RemoteException
    {
      android.os.Parcel _data = android.os.Parcel.obtain();
      android.os.Parcel _reply = android.os.Parcel.obtain();
      int _result;
      try {
        _data.writeInterfaceToken(DESCRIPTOR);
        _data.writeInt(a);
        _data.writeInt(b);
        mRemote.transact(Stub.TRANSACTION_add, _data, _reply, 0);
        _reply.readException();
        _result = _reply.readInt();
      }
      finally {
        _reply.recycle();
        _data.recycle();
      }
      return _result;
    }
  }

  static final int TRANSACTION_add = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);

  public int add(int a, int b) throws android.os.RemoteException;
}
