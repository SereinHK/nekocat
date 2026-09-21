package com.nekochat

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nekochat.ui.NekoChatAppRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须显式给出浅色 scrim。
        //
        // enableEdgeToEdge() 的默认导航栏样式在部分 ROM（本机 ColorOS/Android 15 已复现）
        // 会渲染成一块纯黑的圆角矩形，盖在应用背景之上——因为窗口确实延伸到了系统栏下面，
        // 而系统栏自己没有透明处理。显式传入「浅色 scrim」后由 Activity 1.8+ 负责
        // 把栏设成透明并切换图标为深色，黑块即消失。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        setContent {
            NekoChatAppRoot { finish() }
        }
    }
}
