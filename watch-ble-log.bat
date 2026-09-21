@echo off
REM ============================================================
REM  喵聊 BLE 日志抓取（cmd 版本，不受 PowerShell 执行策略限制）
REM
REM  用法：
REM    1. 手机用 USB 连上电脑，USB 调试已打开并授权
REM    2. 双击本文件（或在 cmd 里执行 watch-ble-log.bat）
REM    3. 按提示在手机上操作 App 复现问题
REM    4. 按 Ctrl+C 停止，日志存成 nekochat-log.txt
REM ============================================================

setlocal
set ADB=C:\Android\Sdk\platform-tools\adb.exe
set OUT=%~dp0nekochat-log.txt

if not exist "%ADB%" (
    echo [错误] 找不到 adb: %ADB%
    echo 请修改本文件里的 ADB 路径。
    pause
    exit /b 1
)

echo === 检查设备连接 ===
"%ADB%" devices
echo.

echo 如果上面只显示 "List of devices attached" 一行，说明手机没连上，请检查：
echo   1. USB 调试已打开
echo   2. 手机上弹出的「允许 USB 调试」已点允许
echo   3. USB 用途改为「传输文件 / MTP」
echo.
pause

echo === 清空旧日志 ===
"%ADB%" logcat -c

echo.
echo ============================================================
echo  现在请在手机上操作：
echo    1. 两端 App 都进「设置」-^> 传输方式选「低功耗蓝牙 (BLE)」
echo    2. 两端都点「启动组网」
echo    3. 两端都进「设备」页点「扫描」
echo    4. 等它卡住 / 复现问题
echo  然后回到本窗口按 Ctrl+C 停止抓取
echo ============================================================
echo.

"%ADB%" logcat -v time NekoChatBle:D MeshManager:D AndroidRuntime:E BluetoothGatt:D BluetoothLeScanner:D BluetoothGattServer:D BluetoothLeAdvertiser:D *:S > "%OUT%"

echo.
echo 日志已保存到: %OUT%
echo 把这个文件发给助手即可定位问题。
pause
