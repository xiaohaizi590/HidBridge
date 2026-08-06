# R8 / ProGuard 保留规则

# 反射调用保留：伪装本地设备类别（BluetoothKeyboardManager.spoofLocalDeviceClass）
-keepclassmembers class android.bluetooth.BluetoothAdapter {
    boolean setBluetoothClass(int);
}

# 反射调用保留：断开音频 Profile（BluetoothKeyboardManager.disconnectAudioProfiles）
-keepclassmembers class android.bluetooth.BluetoothProfile {
    boolean disconnect(android.bluetooth.BluetoothDevice);
}
