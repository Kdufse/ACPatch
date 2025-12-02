package me.bmax.apatch.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.KpmAutoLoadConfig
import me.bmax.apatch.ui.component.KpmAutoLoadManager
import me.bmax.apatch.util.ui.APDialogBlurBehindUtils
import me.bmax.apatch.util.ui.showToast

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KpmAutoLoadConfigScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(KpmAutoLoadManager.isEnabled.value) }
    var jsonString by remember { mutableStateOf(KpmAutoLoadManager.getConfigJson()) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var isValidJson by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val config = KpmAutoLoadManager.loadConfig(context)
        isEnabled = config.enabled
        jsonString = KpmAutoLoadManager.getConfigJson()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.kpm_autoload_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(android.R.string.cancel)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 功能启用开关
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.kpm_autoload_enabled),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.kpm_autoload_enabled_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it }
                    )
                }
            }

            // JSON配置编辑框
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.kpm_autoload_json_config),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    OutlinedTextField(
                        value = jsonString,
                        onValueChange = { 
                            jsonString = it
                            isValidJson = KpmAutoLoadManager.parseConfigFromJson(it) != null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        label = { Text(stringResource(R.string.kpm_autoload_json_label)) },
                        placeholder = { Text(stringResource(R.string.kpm_autoload_json_placeholder)) },
                        isError = !isValidJson,
                        supportingText = {
                            if (!isValidJson) {
                                Text(stringResource(R.string.kpm_autoload_json_error))
                            } else {
                                Text(stringResource(R.string.kpm_autoload_json_helper))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }

            // 保存按钮
            Button(
                onClick = {
                    showSaveDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = isValidJson
            ) {
                Text(stringResource(R.string.kpm_autoload_save))
            }
        }
    }

    // 保存确认对话框
    if (showSaveDialog) {
        BasicAlertDialog(
            onDismissRequest = { showSaveDialog = false }
        ) {
            Surface(
                modifier = Modifier
                    .width(320.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = AlertDialogDefaults.TonalElevation,
                color = AlertDialogDefaults.containerColor,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.kpm_autoload_save_confirm),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showSaveDialog = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            val config = KpmAutoLoadConfig(enabled = isEnabled, kpmPaths = 
                                KpmAutoLoadManager.parseConfigFromJson(jsonString)?.kpmPaths ?: emptyList()
                            )
                            
                            val success = KpmAutoLoadManager.saveConfig(context, config)
                            if (success) {
                                showToast(context, context.getString(R.string.kpm_autoload_save_success))
                                navigator.navigateUp()
                            } else {
                                showToast(context, context.getString(R.string.kpm_autoload_save_failed))
                            }
                            showSaveDialog = false
                        }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                }
            }
        }
    }
}