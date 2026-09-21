package com.nekochat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nekochat.R
import com.nekochat.util.QrPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扫码连接页。
 *
 * 自己接 CameraX 而不是用第三方扫码 Activity：少一个会过期的 UI 依赖，
 * 也便于控制「识别成功后立刻停止分析」。
 */
@Composable
fun QrScanScreen(onBack: () -> Unit, onScanned: (QrPayload.Endpoint) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // 下面这些文案用在非 @Composable 的 lambda（权限回调、扫码回调）里，
    // 必须在 composable 作用域提前取出，回调里再用 String.format 填占位符。
    val initialStatus = stringResource(R.string.qr_initial_status)
    val noPermissionStatus = stringResource(R.string.qr_no_permission)
    val recognizedText = stringResource(R.string.qr_recognized)
    var status by remember { mutableStateOf(initialStatus) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) status = noPermissionStatus
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            PageHeader(
                title = stringResource(R.string.qr_scan_title),
                subtitle = status,
                actions = {
                    TextButton(
                        text = stringResource(R.string.qr_back),
                        onClick = onBack,
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (hasPermission) {
                CameraScanner(
                    onDecoded = { endpoint ->
                        status = recognizedText.format(endpoint.nickname, endpoint.display)
                        onScanned(endpoint)
                    },
                    onError = { status = it }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.qr_need_permission),
                        style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    TextButton(
                        text = stringResource(R.string.qr_request_permission),
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }
}

/** 相机预览 + 逐帧解码。 */
@Composable
private fun CameraScanner(onDecoded: (QrPayload.Endpoint) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 识别成功后置位，避免同一次扫描回调多次
    val handled = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    // 这个文案用在 CameraX 的回调（非 composable）里，提前取出后用 String.format 填充
    val cameraFailedText = stringResource(R.string.qr_camera_failed)

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(0.dp)),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        // 只要最新帧：扫码不需要排队，积压反而增加延迟
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        // 1280x720 足够识别二维码，再大只是浪费 CPU
                        .setTargetResolution(Size(1280, 720))
                        .build()

                    analysis.setAnalyzer(executor) { proxy ->
                        if (handled.get()) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        val endpoint = runCatching { decodeFrame(proxy) }.getOrNull()
                        proxy.close()
                        if (endpoint != null && handled.compareAndSet(false, true)) {
                            previewView.post { onDecoded(endpoint) }
                        }
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (e: Exception) {
                    Log.w("NekoChatQr", "相机启动失败", e)
                    onError(cameraFailedText.format(e.message))
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

/**
 * 从一帧预览数据里识别二维码。
 *
 * **必须按旋转角把图像转正**：Android 相机给出的 YUV 帧是传感器方向（通常横向），
 * 直接交给 ZXing 会因为二维码躺倒而识别不出来。
 */
private fun decodeFrame(proxy: ImageProxy): QrPayload.Endpoint? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)

    val width = proxy.width
    val height = proxy.height
    // 行跨距可能大于宽度（对齐填充），解码器需要按 rowStride 定位每行起点
    val rowStride = plane.rowStride

    // 归一化到 0/90/180/270。这里只区分「转 90」和「转 270」两个方向，
    // 不纠结顺时针还是逆时针：把二维码转躺或转镜像，ZXing 都能识别出来。
    val rotation = ((proxy.imageInfo.rotationDegrees % 360) + 360) % 360

    return when (rotation) {
        90 -> QrPayload.decodeYuv(rotateY(data, width, height, rowStride, 90), height, width)
        180 -> QrPayload.decodeYuv(rotateY(data, width, height, rowStride, 180), width, height)
        270 -> QrPayload.decodeYuv(rotateY(data, width, height, rowStride, 270), height, width)
        else -> QrPayload.decodeYuv(stripPadding(data, width, height, rowStride), width, height)
    }
}

/** 去掉每行末尾的对齐填充，得到紧凑的宽×高亮度图。 */
private fun stripPadding(data: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
    if (rowStride == width) return data
    val out = ByteArray(width * height)
    for (y in 0 until height) {
        val src = y * rowStride
        if (src + width > data.size) break
        System.arraycopy(data, src, out, y * width, width)
    }
    return out
}

/**
 * 把亮度图顺时针旋转指定角度。
 *
 * 只做 0/90/180/270 四种正交旋转 —— 不需要任意角度插值，
 * 因为相机报告的旋转角本来就是 90 的整数倍。
 * 图像不大（约 92 万像素），逐像素拷贝完全够快。
 */
private fun rotateY(
    data: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    degrees: Int
): ByteArray {
    return when (degrees) {
        90 -> {
            // 顺时针 90°：新图宽=height，高=width
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                val srcRow = y * rowStride
                for (x in 0 until width) {
                    // 目标列 = (height-1-y)，目标行 = x
                    out[x * height + (height - 1 - y)] = data[srcRow + x]
                }
            }
            out
        }

        180 -> {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                val srcRow = y * rowStride
                val dstRow = (height - 1 - y) * width
                for (x in 0 until width) {
                    out[dstRow + (width - 1 - x)] = data[srcRow + x]
                }
            }
            out
        }

        else -> {
            // 顺时针 270°（= 逆时针 90°）
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                val srcRow = y * rowStride
                for (x in 0 until width) {
                    // 目标列 = y，目标行 = (width-1-x)
                    out[(width - 1 - x) * height + y] = data[srcRow + x]
                }
            }
            out
        }
    }
}
