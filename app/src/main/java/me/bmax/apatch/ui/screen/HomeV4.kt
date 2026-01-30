package me.bmax.apatch.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.ramcosta.composedestinations.generated.destinations.InstallModeSelectScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.util.Version
import androidx.compose.ui.draw.alpha
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import android.os.Build
import me.bmax.apatch.util.Version.getManagerVersion

private val managerVersion = getManagerVersion()

@Composable
fun HomeScreenV4(
    paddingValues: PaddingValues,
    navigator: DestinationsNavigator,
    kpState: APApplication.State,
    apState: APApplication.State
) {
    val scrollState = rememberScrollState()
    
    val showAuthKeyDialog = remember { mutableStateOf(false) }
    val showUninstallDialog = remember { mutableStateOf(false) }
    val showAuthFailedTipDialog = remember { mutableStateOf(false) }

    if (showAuthFailedTipDialog.value) {
        AuthFailedTipDialog(showDialog = showAuthFailedTipDialog)
    }
    if (showAuthKeyDialog.value) {
        AuthSuperKey(showDialog = showAuthKeyDialog, showFailedDialog = showAuthFailedTipDialog)
    }
    if (showUninstallDialog.value) {
        UninstallDialog(showDialog = showUninstallDialog, navigator)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
            .verticalScroll(scrollState)
    ) {
        // Header Spacer
        Spacer(Modifier.height(12.dp))
        
        // Main Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Status Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Left: Main Status Card
                MainStatusCard(
                    modifier = Modifier.weight(2f),
                    kpState = kpState,
                    apState = apState,
                    onClick = {
                        when (kpState) {
                            APApplication.State.UNKNOWN_STATE -> showAuthKeyDialog.value = true
                            APApplication.State.KERNELPATCH_NEED_UPDATE -> navigator.navigate(InstallModeSelectScreenDestination)
                            APApplication.State.KERNELPATCH_INSTALLED -> {} 
                            else -> navigator.navigate(InstallModeSelectScreenDestination)
                        }
                    }
                )
                
                // Right: Version Info Cards
                Column(
                    modifier = Modifier.weight(1.5f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    VersionInfoCard(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.kernel_patch),
                        value = if (kpState != APApplication.State.UNKNOWN_STATE) 
                            "${Version.installedKPVString()} (${managerVersion.second})" 
                        else "N/A",
                        icon = Icons.Outlined.Extension,
                        onClick = {
                            if (kpState == APApplication.State.KERNELPATCH_NEED_UPDATE) {
                                navigator.navigate(InstallModeSelectScreenDestination)
                            }
                        }
                    )
                    
                    VersionInfoCard(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.android_patch),
                        value = when(apState) {
                            APApplication.State.ANDROIDPATCH_INSTALLED -> "Active"
                            APApplication.State.ANDROIDPATCH_NEED_UPDATE -> "Update"
                            APApplication.State.ANDROIDPATCH_INSTALLING -> "..."
                            else -> "Inactive"
                        },
                        icon = Icons.Outlined.Android,
                        statusColor = when(apState) {
                            APApplication.State.ANDROIDPATCH_INSTALLED -> MaterialTheme.colorScheme.primary
                            APApplication.State.ANDROIDPATCH_NEED_UPDATE -> MaterialTheme.colorScheme.tertiary
                            APApplication.State.ANDROIDPATCH_INSTALLING -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.outline
                        },
                        onClick = {
                            when (apState) {
                                APApplication.State.ANDROIDPATCH_INSTALLED -> showUninstallDialog.value = true
                                APApplication.State.ANDROIDPATCH_NEED_UPDATE,
                                APApplication.State.ANDROIDPATCH_INSTALLING -> {
                                    if (kpState == APApplication.State.KERNELPATCH_INSTALLED) {
                                        APApplication.installApatch()
                                    }
                                }
                                else -> {} // No action for NOT_INSTALLED
                            }
                        }
                    )
                }
            }

            // AndroidPatch Install Section
            if (kpState != APApplication.State.UNKNOWN_STATE && apState != APApplication.State.ANDROIDPATCH_INSTALLED) {
                AndroidPatchStatusCard(apState)
            }
            
            // Info Card
            EnhancedInfoCard(kpState, apState)
            
            // Learn More Card
            val hideApatchCard = APApplication.sharedPreferences.getBoolean("hide_apatch_card", false)
            if (!hideApatchCard) {
                LearnMoreCard()
            }
        }
        
        // Bottom Spacer
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainStatusCard(
    modifier: Modifier = Modifier,
    kpState: APApplication.State,
    apState: APApplication.State,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isWorking = kpState == APApplication.State.KERNELPATCH_INSTALLED
    val isUpdate = kpState == APApplication.State.KERNELPATCH_NEED_UPDATE || 
                   kpState == APApplication.State.KERNELPATCH_NEED_REBOOT
    
    // Theme detection
    val prefs = APApplication.sharedPreferences
    val darkThemeFollowSys = prefs.getBoolean("night_mode_follow_sys", false)
    val nightModeEnabled = prefs.getBoolean("night_mode_enabled", true)
    
    // Background configuration
    val useCustomGridBg = BackgroundConfig.isGridWorkingCardBackgroundEnabled && 
                          !BackgroundConfig.gridWorkingCardBackgroundUri.isNullOrEmpty()
    
    // Colors
    val (containerColor, contentColor) = when {
        useCustomGridBg -> Color.Transparent to Color.White
        isWorking -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        isUpdate -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .shadow(
                elevation = if (useCustomGridBg || BackgroundConfig.isCustomBackgroundEnabled) 0.dp else 8.dp,
                shape = MaterialTheme.shapes.large,
                clip = false
            ),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = MaterialTheme.shapes.large,
        border = if (!useCustomGridBg && !BackgroundConfig.isCustomBackgroundEnabled) {
            CardDefaults.outlinedCardBorder()
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (useCustomGridBg || BackgroundConfig.isCustomBackgroundEnabled) 0.dp else 2.dp
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Custom Background Image
            if (useCustomGridBg) {
                val imageLoader = ImageLoader.Builder(context)
                    .components {
                        if (Build.VERSION.SDK_INT >= 28) {
                            add(ImageDecoderDecoder.Factory())
                        } else {
                            add(GifDecoder.Factory())
                        }
                    }
                    .build()

                Image(
                    painter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(context)
                            .data(BackgroundConfig.gridWorkingCardBackgroundUri)
                            .crossfade(true)
                            .build(),
                        imageLoader = imageLoader
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(BackgroundConfig.gridWorkingCardBackgroundOpacity)
                )
                
                // Gradient overlay for better text readability
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.3f),
                                    Color.Black.copy(alpha = 0.1f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            // Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Section: Icon
                if (!BackgroundConfig.isGridWorkingCardCheckHidden) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = contentColor.copy(alpha = 0.1f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isWorking) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .fillMaxSize(),
                                tint = contentColor
                            )
                        }
                    }
                }

                // Bottom Section: Text
                if (!BackgroundConfig.isGridWorkingCardTextHidden) {
                    Column {
                        Text(
                            text = when(kpState) {
                                APApplication.State.KERNELPATCH_INSTALLED -> stringResource(R.string.home_working)
                                APApplication.State.KERNELPATCH_NEED_UPDATE -> stringResource(R.string.home_kp_need_update)
                                APApplication.State.KERNELPATCH_NEED_REBOOT -> stringResource(R.string.home_ap_cando_reboot)
                                APApplication.State.UNKNOWN_STATE -> stringResource(R.string.home_install_unknown)
                                else -> stringResource(R.string.home_not_installed)
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            maxLines = 2
                        )
                        
                        if (isWorking && !BackgroundConfig.isGridWorkingCardModeHidden) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = contentColor.copy(alpha = 0.1f),
                                modifier = Modifier.clip(MaterialTheme.shapes.small)
                            ) {
                                Text(
                                    text = if (apState == APApplication.State.ANDROIDPATCH_INSTALLED) 
                                        "Full Mode" 
                                    else "Half Mode",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = contentColor.copy(alpha = 0.9f),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionInfoCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    statusColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (BackgroundConfig.isCustomBackgroundEnabled) {
                MaterialTheme.colorScheme.surface.copy(alpha = BackgroundConfig.customBackgroundOpacity)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = MaterialTheme.shapes.medium,
        border = CardDefaults.outlinedCardBorder(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (BackgroundConfig.isCustomBackgroundEnabled) 0.dp else 1.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with Icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusColor.copy(alpha = 0.1f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(6.dp)
                            .fillMaxSize(),
                        tint = statusColor
                    )
                }
                
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Value
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // Status Indicator
            if (value != "N/A" && value != "Inactive") {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = statusColor.copy(alpha = 0.1f),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .padding(top = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = statusColor,
                                    shape = MaterialTheme.shapes.extraSmall
                                )
                        )
                        Text(
                            text = when {
                                value == "Active" || value.contains("Update") -> "Ready"
                                else -> "Installed"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidPatchStatusCard(apState: APApplication.State) {
    val statusText = when(apState) {
        APApplication.State.ANDROIDPATCH_NEED_UPDATE -> "Update Available"
        APApplication.State.ANDROIDPATCH_INSTALLING -> "Installing..."
        else -> "Install Android Patch"
    }
    
    val statusColor = when(apState) {
        APApplication.State.ANDROIDPATCH_NEED_UPDATE -> MaterialTheme.colorScheme.tertiary
        APApplication.State.ANDROIDPATCH_INSTALLING -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = statusColor.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, MaterialTheme.shapes.medium)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Complete the installation for full functionality",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Icon(
                imageVector = Icons.Outlined.Android,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnhancedInfoCard(kpState: APApplication.State, apState: APApplication.State) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "System Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Status Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusIndicator(
                    label = "Kernel Patch",
                    isActive = kpState == APApplication.State.KERNELPATCH_INSTALLED,
                    needsUpdate = kpState == APApplication.State.KERNELPATCH_NEED_UPDATE
                )
                
                StatusIndicator(
                    label = "Android Patch",
                    isActive = apState == APApplication.State.ANDROIDPATCH_INSTALLED,
                    needsUpdate = apState == APApplication.State.ANDROIDPATCH_NEED_UPDATE
                )
            }
            
            Divider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
            
            Text(
                text = when {
                    kpState == APApplication.State.KERNELPATCH_INSTALLED && 
                    apState == APApplication.State.ANDROIDPATCH_INSTALLED -> 
                        "Both patches are active and running properly."
                    kpState == APApplication.State.KERNELPATCH_INSTALLED -> 
                        "Kernel patch is active. Consider installing Android patch for full functionality."
                    else -> 
                        "System patches are not fully configured. Follow setup instructions."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun StatusIndicator(
    label: String,
    isActive: Boolean,
    needsUpdate: Boolean
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    color = when {
                        isActive -> MaterialTheme.colorScheme.primary
                        needsUpdate -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    shape = MaterialTheme.shapes.small
                )
        )
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LearnMoreCard() {
    // Implement your LearnMoreCard component here
    // This is a placeholder implementation
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { /* Handle click */ }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Learn More",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}