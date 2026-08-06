#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
电脑端接收程序：UDP → ViGEmBus 虚拟 Xbox 360 手柄（真 XInput）+ RFCOMM 命令通道客户端

与手机 App 的 "WiFi 桥接" 功能配合：
    App 把 USB-C 拉伸手柄状态通过局域网 UDP 发到本机，
    本程序解析后写入虚拟 Xbox 手柄，游戏即可识别为 XInput 设备。

用法：
    1. 打包成绿色 exe（含 Python 环境，电脑无需装 Python）:
       build_exe.bat   →  生成 dist\\receiver.exe
    2. 安装（一次）：接收端双击 receiver.exe --install
       - 自动检测/安装 ViGEmBus 驱动（弹一次 UAC，用户点确认）
       - 复制到 %LOCALAPPDATA%\\HidReceiver\\ 并注册开机自启
       - 之后"下次直接用"，开机自启或手动运行即可
    3. 运行接收端（默认）：receiver.exe
       （或源码模式: python receiver.py）
    4. 卸载：receiver.exe --uninstall
    5. 连接手机 RFCOMM 命令通道（阶段2验证）:
       receiver.exe --phone-addr AA:BB:CC:DD:EE:FF

手机端：
    1. 手机与电脑连同一个 Wi-Fi
    2. App "WiFi 桥接"卡片点"推送安装包到电脑"→ 蓝牙发 exe → 电脑收下双击 --install
    3. 电脑 IP 填本机局域网 IP，打开开关即可在游戏 / joy.cpl 中看到虚拟 Xbox 手柄

注意：首次运行 Windows 会弹防火墙提示，请勾选"专用网络"并允许。
"""

import argparse
import os
import select
import shutil
import socket
import struct
import sys
import threading
import time

import vgamepad as vg

UDP_PORT = 47808  # 与手机端 UdpBridge.DEFAULT_PORT 一致
TAG = "[receiver]"

# 与手机端 WifiCommandBridge.SPP_UUID 一致：SPP 标准 UUID
SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

# 安装模式：固定安装目录 + 开机自启注册表项（HKCU，无需管理员）
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "HidReceiver"

# 18 位按键位图 → XInput 按键（与手机端 InputBridge 位图一致）
# bit0=A bit1=B bit2=X bit3=Y bit4=LB bit5=RB bit6=LT bit7=RT
# bit8=Select bit9=Start bit10=L3 bit11=R3
# bit12=DPadU bit13=DPadD bit14=DPadL bit15=DPadR bit16=C bit17=Z
BUTTON_BITS = {
    0: vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
    1: vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
    2: vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
    3: vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
    4: vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
    5: vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
    8: vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
    9: vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
    10: vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
    11: vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
    12: vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
    13: vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
    14: vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
    15: vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
}
# bit16=C bit17=Z：XInput 无对应按键，忽略

# 超过该秒数未收到任何包，视为手机断开，释放所有按键防止"卡键"
STALE_TIMEOUT_S = 2.0


def to_axis(v: float) -> int:
    """-1..1 → -32768..32767，夹紧"""
    v = max(-1.0, min(1.0, v))
    return int(v * 32767.0)


def reset_all(pad: vg.VX360Gamepad) -> None:
    """释放所有按键 / 扳机 / 摇杆，回到中心"""
    for button in BUTTON_BITS.values():
        pad.release_button(button)
    pad.left_trigger(0.0)
    pad.right_trigger(0.0)
    pad.left_joystick(0, 0)
    pad.right_joystick(0, 0)
    pad.update()


# ---------------- 阶段2：RFCOMM 命令通道（PC 端客户端） ----------------

def connect_command_channel(phone_addr: str):
    """通过 RFCOMM 连接手机的 HID 命令通道（手机端 WifiCommandBridge 是服务端）。

    依赖 PyBluezWin10（Windows 蓝牙栈）。连接成功后返回 socket。
    """
    try:
        import bluetooth  # noqa: PLC0415
    except ImportError:
        print(TAG, "未安装 PyBluezWin10，无法连接命令通道。请先执行: pip install PyBluezWin10")
        return None

    try:
        print(TAG, f"查找手机 {phone_addr} 的 SPP 命令通道（SDP）...")
        services = bluetooth.find_service(uuid=SPP_UUID, address=phone_addr)
        if services:
            svc = services[0]
            channel = svc.get("port", 1)
            name = svc.get("name", "?")
            print(TAG, f"SDP 找到服务: {name}，通道 {channel}")
        else:
            channel = 1
            print(TAG, f"SDP 未找到服务，回退使用通道 {channel}")

        sock = bluetooth.BluetoothSocket(bluetooth.RFCOMM)
        sock.connect((phone_addr, channel))
        sock.settimeout(10)
        print(TAG, f"命令通道已连接: {phone_addr}（通道 {channel}）")
        return sock
    except Exception as exc:  # noqa: BLE001
        print(TAG, f"命令通道连接失败: {exc}")
        print(TAG, "请确认：手机已打开 App（RFCOMM 监听中）、已配对、蓝牙已开启")
        return None


def command_reader(sock) -> None:
    """后台线程：持续读取手机发来的命令行并打印。"""
    try:
        while True:
            data = sock.recv(1024)
            if not data:
                break
            text = data.decode("utf-8", "replace").strip()
            if text:
                print(f"[RFCOMM<<] {text}")
    except Exception as exc:  # noqa: BLE001
        print(TAG, f"命令通道读取结束: {exc}")
    finally:
        print(TAG, "命令通道已断开")
        try:
            sock.close()
        except Exception:  # noqa: BLE001
            pass


def command_writer(sock) -> None:
    """后台线程：从命令行输入发送文本行给手机；输入 q 退出。"""
    try:
        while True:
            line = input("[RFCOMM>>] ").strip()
            if not line:
                continue
            if line.lower() in ("q", "quit", "exit"):
                break
            sock.send((line + "\n").encode("utf-8"))
    except (EOFError, KeyboardInterrupt):
        pass


# ---------------- 安装模式：装驱动 + 固定目录 + 开机自启 ----------------

def is_admin() -> bool:
    """当前进程是否以管理员权限运行"""
    try:
        import ctypes
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:  # noqa: BLE001
        return False


def elevate_and_wait(args) -> bool:
    """以管理员权限重新运行本程序并等待其结束（弹一次 UAC，用户点确认）"""
    import subprocess
    ps_args = " ".join("'" + a.replace("'", "''") + "'" for a in args)
    script = (
        f"Start-Process -FilePath '{sys.executable}' "
        f"-ArgumentList {ps_args} -Verb RunAs -Wait"
    )
    try:
        subprocess.run(["powershell", "-NoProfile", "-Command", script], check=False)
        return True
    except Exception as exc:  # noqa: BLE001
        print(TAG, f"提权失败: {exc}")
        return False


def default_install_dir() -> str:
    """固定安装目录：%LOCALAPPDATA%\\HidReceiver"""
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    return os.path.join(base, "HidReceiver")


def driver_installed() -> bool:
    """检测 ViGEmBus 驱动是否可用（能创建虚拟手柄即已装）"""
    try:
        pad = vg.VX360Gamepad()
        try:
            pad.close()
        except Exception:  # noqa: BLE001
            pass
        del pad
        return True
    except Exception:  # noqa: BLE001
        return False


def install_driver_now() -> bool:
    """安装 ViGEmBus 驱动（需管理员权限）"""
    print(TAG, "正在安装 ViGEmBus 驱动...")
    try:
        vg.install_driver()
        print(TAG, "驱动安装完成")
        return True
    except Exception as exc:  # noqa: BLE001
        print(TAG, f"驱动安装失败: {exc}")
        print(TAG, "可手动重试：以管理员运行 receiver.exe --install")
        return False


def setup_autostart(exe_path: str) -> bool:
    """注册当前用户开机自启（HKCU Run，无需管理员）"""
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            winreg.SetValueEx(key, RUN_VALUE, 0, winreg.REG_SZ, f'"{exe_path}"')
        print(TAG, "已注册开机自启")
        return True
    except Exception as exc:  # noqa: BLE001
        print(TAG, f"注册开机自启失败: {exc}")
        return False


def remove_autostart() -> bool:
    """移除开机自启"""
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, RUN_VALUE)
        print(TAG, "已移除开机自启")
        return True
    except FileNotFoundError:
        print(TAG, "开机自启未注册，无需移除")
        return True
    except Exception as exc:  # noqa: BLE001
        print(TAG, f"移除开机自启失败: {exc}")
        return False


def do_install() -> None:
    """实际安装：装驱动（管理员）→ 复制 exe 到固定目录 → 注册自启"""
    if not driver_installed():
        if not install_driver_now():
            print(TAG, "驱动未安装成功，安装流程中止（可稍后重试）")
            return

    install_dir = default_install_dir()
    os.makedirs(install_dir, exist_ok=True)
    if getattr(sys, "frozen", False):
        target = os.path.join(install_dir, "receiver.exe")
        shutil.copy2(sys.executable, target)
        print(TAG, f"已复制到固定目录: {target}")
        setup_autostart(target)
    else:
        print(TAG, "源码模式（非 exe），跳过复制与自启（请用 build_exe.bat 打包）")
        return
    print(TAG, f"安装完成！启动方式：{install_dir}\\receiver.exe")
    print(TAG, "已注册开机自启，下次开机 / 手动运行即可直接连接手机")


def cmd_install() -> int:
    if not getattr(sys, "frozen", False):
        print(TAG, "请先运行 build_exe.bat 打包成 receiver.exe，再执行 --install")
        return 1
    if not is_admin():
        print(TAG, "安装需要管理员权限，正在弹出 UAC 确认...")
        elevate_and_wait(["--install"])
        print(TAG, "安装流程结束（若已确认 UAC，可重新运行 receiver.exe 开始使用）")
        return 0
    do_install()
    return 0


def cmd_uninstall() -> int:
    if not is_admin():
        print(TAG, "卸载需要管理员权限，正在弹出 UAC 确认...")
        elevate_and_wait(["--uninstall"])
        return 0
    remove_autostart()
    install_dir = default_install_dir()
    if os.path.isdir(install_dir):
        shutil.rmtree(install_dir, ignore_errors=True)
        print(TAG, f"已删除安装目录: {install_dir}")
    if driver_installed():
        print(TAG, "ViGEmBus 驱动仍在（可能被其他程序使用），如需彻底移除请到设备管理器手动卸载")
    print(TAG, "卸载完成")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(
        description="UDP → 虚拟 Xbox 手柄 + RFCOMM 命令通道客户端"
    )
    parser.add_argument(
        "--phone-addr",
        default=None,
        help="手机蓝牙 MAC 地址（如 AA:BB:CC:DD:EE:FF），提供后连接 RFCOMM 命令通道（阶段2验证）",
    )
    parser.add_argument(
        "--install",
        action="store_true",
        help="安装模式：装 ViGEmBus 驱动 + 复制到固定目录 + 注册开机自启（弹一次 UAC）",
    )
    parser.add_argument(
        "--uninstall",
        action="store_true",
        help="卸载：移除开机自启 + 删除安装目录",
    )
    args = parser.parse_args()

    if args.install:
        sys.exit(cmd_install())
    if args.uninstall:
        sys.exit(cmd_uninstall())

    if args.phone_addr:
        cmd_sock = connect_command_channel(args.phone_addr)
        if cmd_sock:
            threading.Thread(target=command_reader, args=(cmd_sock,), daemon=True).start()
            threading.Thread(target=command_writer, args=(cmd_sock,), daemon=True).start()
    else:
        print(TAG, "未指定 --phone-addr，仅运行 UDP 数据接收（命令通道不可用）")

    print(TAG, "创建虚拟 Xbox 360 手柄...")
    try:
        pad = vg.VX360Gamepad()
    except Exception as exc:  # noqa: BLE001
        print(TAG, f"创建虚拟手柄失败: {exc}")
        print(TAG, "ViGEmBus 驱动可能未安装。请运行: receiver.exe --install（弹一次 UAC 确认）")
        sys.exit(1)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", UDP_PORT))
    sock.setblocking(False)
    print(TAG, f"监听 UDP 端口 {UDP_PORT}，等待手机连接...")
    print(TAG, "手机 App → WiFi 桥接卡片 → 填入本机 IP 并打开开关")

    last_mask = 0
    last_recv = time.time()
    frames = 0
    fps_window_start = time.time()

    try:
        while True:
            ready, _, _ = select.select([sock], [], [], 0.5)
            if not ready:
                # 超时未收到包：检查手机是否断开
                if time.time() - last_recv > STALE_TIMEOUT_S and last_mask != 0:
                    reset_all(pad)
                    last_mask = 0
                    print(TAG, "超过 2 秒未收到数据（手机断开？），已释放所有按键")
                continue

            data, addr = sock.recvfrom(64)
            last_recv = time.time()
            if len(data) < 24:
                continue

            # 包格式：seq(u32) mask(u32) leftX(f) leftY(f) rightX(f) rightY(f)，小端
            seq, mask, lx, ly, rx, ry = struct.unpack("<IIffff", data[:24])
            frames += 1

            # 链路确认：向手机源地址回 4 字节 ACK（小端 seq），手机端显示"电脑已确认"
            try:
                sock.sendto(struct.pack("<I", seq), addr)
            except Exception as exc:  # noqa: BLE001
                print(TAG, f"发送 ACK 失败: {exc}")

            # 按键：仅对变化的位做 press / release
            changed = mask ^ last_mask
            if changed:
                for bit, button in BUTTON_BITS.items():
                    if changed & (1 << bit):
                        if mask & (1 << bit):
                            pad.press_button(button)
                        else:
                            pad.release_button(button)
            last_mask = mask

            # LT / RT（bit6/bit7）：XInput 模拟扳机，按下=满行程，松开=0
            pad.left_trigger(1.0 if mask & (1 << 6) else 0.0)
            pad.right_trigger(1.0 if mask & (1 << 7) else 0.0)

            # 摇杆：Android Y 轴"上推为 -1"，XInput 上推为 +，故取反
            pad.left_joystick(to_axis(lx), to_axis(-ly))
            pad.right_joystick(to_axis(rx), to_axis(-ry))

            pad.update()

            now = time.time()
            if now - fps_window_start >= 1.0:
                print(TAG, f"接收 {frames} 帧/秒 | 来源 {addr[0]} | 最新 seq={seq}")
                frames = 0
                fps_window_start = now
    except KeyboardInterrupt:
        print(TAG, "退出中，释放所有按键...")
        reset_all(pad)
        print(TAG, "已退出")


if __name__ == "__main__":
    main()
