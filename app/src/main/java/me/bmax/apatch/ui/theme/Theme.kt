package me.bmax.apatch.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.MutableLiveData
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import android.net.Uri
import me.bmax.apatch.APApplication
import androidx.compose.ui.draw.paint
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.ramcosta.composedestinations.generated.destinations.SettingScreenDestination

@Composable
private fun SystemBarStyle(
    darkMode: Boolean,
    statusBarScrim: Color = Color.Transparent,
    navigationBarScrim: Color = Color.Transparent
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    SideEffect {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                statusBarScrim.toArgb(),
                statusBarScrim.toArgb(),
            ) { darkMode }, navigationBarStyle = when {
                darkMode -> SystemBarStyle.dark(
                    navigationBarScrim.toArgb()
                )

                else -> SystemBarStyle.light(
                    navigationBarScrim.toArgb(),
                    navigationBarScrim.toArgb(),
                )
            }
        )
    }
}

val refreshTheme = MutableLiveData(false)

@Composable
fun APatchTheme(
    isSettingsScreen: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = APApplication.sharedPreferences

    var darkThemeFollowSys by remember {
        mutableStateOf(
            prefs.getBoolean(
                "night_mode_follow_sys",
                true
            )
        )
    }
    var nightModeEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(
                "night_mode_enabled",
                false
            )
        )
    }
    // Dynamic color is available on Android 12+, and custom 1t!
    var dynamicColor by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) prefs.getBoolean(
                "use_system_color_theme",
                true
            ) else false
        )
    }
    var customColorScheme by remember { mutableStateOf(prefs.getString("custom_color", "blue")) }

    val refreshThemeObserver by refreshTheme.observeAsState(false)
    if (refreshThemeObserver == true) {
        darkThemeFollowSys = prefs.getBoolean("night_mode_follow_sys", true)
        nightModeEnabled = prefs.getBoolean("night_mode_enabled", false)
        dynamicColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) prefs.getBoolean(
            "use_system_color_theme",
            true
        ) else false
        customColorScheme = prefs.getString("custom_color", "blue")
        refreshTheme.postValue(false)
    }

    val darkTheme = if (darkThemeFollowSys) {
        isSystemInDarkTheme()
    } else {
        nightModeEnabled
    }

    val baseColorScheme = if (!dynamicColor) {
        if (darkTheme) {
            when (customColorScheme) {
                "amber" -> DarkAmberTheme
                "blue_grey" -> DarkBlueGreyTheme
                "blue" -> DarkBlueTheme
                "brown" -> DarkBrownTheme
                "cyan" -> DarkCyanTheme
                "deep_orange" -> DarkDeepOrangeTheme
                "deep_purple" -> DarkDeepPurpleTheme
                "green" -> DarkGreenTheme
                "indigo" -> DarkIndigoTheme
                "light_blue" -> DarkLightBlueTheme
                "light_green" -> DarkLightGreenTheme
                "lime" -> DarkLimeTheme
                "orange" -> DarkOrangeTheme
                "pink" -> DarkPinkTheme
                "purple" -> DarkPurpleTheme
                "red" -> DarkRedTheme
                "sakura" -> DarkSakuraTheme
                "teal" -> DarkTealTheme
                "yellow" -> DarkYellowTheme
                else -> DarkBlueTheme
            }
        } else {
            when (customColorScheme) {
                "amber" -> LightAmberTheme
                "blue_grey" -> LightBlueGreyTheme
                "blue" -> LightBlueTheme
                "brown" -> LightBrownTheme
                "cyan" -> LightCyanTheme
                "deep_orange" -> LightDeepOrangeTheme
                "deep_purple" -> LightDeepPurpleTheme
                "green" -> LightGreenTheme
                "indigo" -> LightIndigoTheme
                "light_blue" -> LightLightBlueTheme
                "light_green" -> LightLightGreenTheme
                "lime" -> LightLimeTheme
                "orange" -> LightOrangeTheme
                "pink" -> LightPinkTheme
                "purple" -> LightPurpleTheme
                "red" -> LightRedTheme
                "sakura" -> LightSakuraTheme
                "teal" -> LightTealTheme
                "yellow" -> LightYellowTheme
                else -> LightBlueTheme
            }
        }
    } else {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkBlueTheme
            else -> LightBlueTheme
        }
    }
    
    val colorScheme = baseColorScheme.copy(
        background = if (BackgroundConfig.isCustomBackgroundEnabled) Color.Transparent else baseColorScheme.background,
        surface = if (BackgroundConfig.isCustomBackgroundEnabled) {
            // 在自定义背景模式下，为surface添加半透明效果
            baseColorScheme.surface.copy(alpha = BackgroundConfig.customBackgroundOpacity)
        } else {
            baseColorScheme.surface
        },
        // 同样处理primary和secondary颜色，确保KStatusCard也有半透明效果
        primary = baseColorScheme.primary,
        secondary = baseColorScheme.secondary,
        secondaryContainer = if (BackgroundConfig.isCustomBackgroundEnabled) {
            baseColorScheme.secondaryContainer.copy(alpha = BackgroundConfig.customBackgroundOpacity)
        } else {
            baseColorScheme.secondaryContainer
        },
        surfaceContainer = if (BackgroundConfig.isCustomBackgroundEnabled) {
            baseColorScheme.surfaceContainer.copy(alpha = BackgroundConfig.customBackgroundOpacity)
        } else {
            baseColorScheme.surfaceContainer
        }
    )

    SystemBarStyle(
        darkMode = darkTheme
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = getTypography(FontConfig.getFontFamily(context)),
        content = content
    )
}

@Composable
fun APatchThemeWithBackground(
    navController: NavHostController? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    // 检查当前是否在设置页面
    val isSettingsScreen = navController?.currentBackStackEntryAsState()?.value?.destination?.route == SettingScreenDestination.route
    
    // 立即加载背景配置，不使用LaunchedEffect
    BackgroundManager.loadCustomBackground(context)
    FontConfig.load(context)
    android.util.Log.d("APatchThemeWithBackground", "加载背景配置完成")
    
    // 监听refreshTheme的变化，重新加载背景配置
    val refreshThemeObserver by refreshTheme.observeAsState(false)
    if (refreshThemeObserver) {
        BackgroundManager.loadCustomBackground(context)
        FontConfig.load(context)
        android.util.Log.d("APatchThemeWithBackground", "重新加载背景配置")
        refreshTheme.postValue(false)
    }
    
    APatchTheme(isSettingsScreen = isSettingsScreen) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Always show background layer if enabled
            BackgroundLayer()
            
            // Content layer - add zIndex to ensure it's above the background
            Box(modifier = Modifier.fillMaxSize().zIndex(1f)) {
                content()
            }
        }
    }
}

@Composable
fun BackgroundLayer() {
    val context = LocalContext.current
    
    // 获取当前主题模式
    val prefs = APApplication.sharedPreferences
    val darkThemeFollowSys = prefs.getBoolean("night_mode_follow_sys", true)
    val nightModeEnabled = prefs.getBoolean("night_mode_enabled", false)
    val darkTheme = if (darkThemeFollowSys) {
        isSystemInDarkTheme()
    } else {
        nightModeEnabled
    }
    
    // 添加日志以便调试
    android.util.Log.d("BackgroundLayer", "背景状态: 启用=${BackgroundConfig.isCustomBackgroundEnabled}, URI=${BackgroundConfig.customBackgroundUri}, 深色模式=$darkTheme")
    
    // 默认背景
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(-2f)
            .background(MaterialTheme.colorScheme.background)
    )
    
    // 如果启用了自定义背景，显示背景图片
    if (BackgroundConfig.isCustomBackgroundEnabled && !BackgroundConfig.customBackgroundUri.isNullOrEmpty()) {
        android.util.Log.d("BackgroundLayer", "显示自定义背景图片")
        
        // 使用AsyncImagePainter加载图片
        val painter = rememberAsyncImagePainter(
            model = BackgroundConfig.customBackgroundUri,
            onError = { error ->
                android.util.Log.e("BackgroundLayer", "背景加载失败: ${error.result.throwable.message}")
            },
            onSuccess = {
                android.util.Log.d("BackgroundLayer", "背景加载成功")
            }
        )
        
        // 背景图片
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(-2f)
                .paint(painter = painter, contentScale = ContentScale.Crop)
        )
        
        // 遮罩层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(-1f)
                .background(Color.Black.copy(alpha = BackgroundConfig.customBackgroundDim))
        )
    }
}
