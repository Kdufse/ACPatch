package me.yuki.apatch.ui.theme

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.yuki.apatch.APApplication

/**
 * 背景层组件，用于显示自定义背景或默认背景
 */
@Composable
fun BackgroundLayer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // 加载背景配置
    LaunchedEffect(Unit) {
        BackgroundManager.loadCustomBackground(context)
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // 如果启用了自定义背景，显示自定义背景图片
        if (BackgroundConfig.isCustomBackgroundEnabled && !BackgroundConfig.customBackgroundUri.isNullOrEmpty()) {
            val painter = rememberAsyncImagePainter(
                ImageRequest.Builder(context)
                    .data(BackgroundConfig.customBackgroundUri)
                    .crossfade(true)
                    .build()
            )
            
            Image(
                painter = painter,
                contentDescription = "Custom Background",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            // 添加半透明遮罩层，确保内容可读性
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.2f)
                            )
                        )
                    )
            )
        } else {
            // 默认背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }
        
        // 内容
        content()
    }
}

/**
 * 扩展函数，用于保存自定义背景
 */
fun Context.saveCustomBackground(uri: Uri?) {
    if (uri != null) {
        // 使用IO调度器在后台线程中处理
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            BackgroundManager.saveAndApplyCustomBackground(this@saveCustomBackground, uri)
        }
    } else {
        BackgroundManager.clearCustomBackground(this)
    }
}