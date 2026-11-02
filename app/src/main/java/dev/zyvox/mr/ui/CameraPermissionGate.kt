package dev.zyvox.mr.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.zyvox.mr.R

/**
 * Portão de permissão de câmera.
 *
 * ### Por que um portão e não pedir dentro da cena
 * O `ARSceneView`/`CameraController` só pode fazer bind depois de a permissão existir. Pedir
 * permissão *depois* de montar a cena significa criar o `ProcessCameraProvider`, falhar o
 * bind, e então precisar desfazer e refazer tudo quando o usuário concede. Pedir antes mantém
 * um único caminho de inicialização da câmera, que é o que torna o comportamento previsível.
 *
 * ### Negativa permanente
 * Se o usuário marcou "não perguntar de novo", `rememberLauncherForActivityResult` retorna
 * `false` e o diálogo do sistema nunca mais aparece. Sem tratamento, o app fica numa tela
 * pedindo permissão que não vai chegar — um beco sem saída. Por isso o segundo botão leva às
 * configurações do app.
 */
@Composable
fun CameraPermissionGate(
    onGranted: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var deniedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        if (!result) deniedOnce = true
    }

    if (granted) {
        onGranted()
        return
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MrTokens.Background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .clip(RoundedCornerShape(MrTokens.RadiusLg))
                    .background(MrTokens.GlassSurface)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MrTokens.SpaceMd),
            ) {
                Text(
                    text = stringResource(R.string.permission_camera_title),
                    color = MrTokens.TextPrimary,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.permission_camera_body),
                    color = MrTokens.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )

                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = MrTokens.AccentStrong),
                ) {
                    Text(
                        text = stringResource(R.string.permission_camera_grant),
                        color = MrTokens.Background,
                    )
                }

                // O botão de configurações só aparece depois de uma negativa: antes disso é
                // ruído, e oferecê-lo cedo convida o usuário a sair do app sem conceder.
                if (deniedOnce) {
                    Text(
                        text = stringResource(R.string.permission_camera_denied),
                        color = MrTokens.Danger,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = {
                        // `ACTION_APPLICATION_DETAILS_SETTINGS` com o `package:` Uri é a rota
                        // direta para a tela de permissões deste app.
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    }) {
                        Text(
                            text = stringResource(R.string.permission_camera_settings),
                            color = MrTokens.Accent,
                        )
                    }
                }
            }
        }
    }
}
