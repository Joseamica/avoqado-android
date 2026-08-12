package com.avoqado.pos.auth.presentation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat

@Composable
fun LandingScreen(
    onLoginSuccess: () -> Unit,
) {
    var showSignIn by remember { mutableStateOf(false) }

    // Make status bar icons white on the dark landing screen
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
    }

    if (showSignIn) {
        SignInFlowScreen(
            onLoginSuccess = onLoginSuccess,
            onBack = { showSignIn = false },
        )
    } else {
        val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

        // Fondo edge-to-edge (detrás de las barras de estado y navegación):
        // el MISMO `background_video.mp4` que iOS reproduce aquí
        // (`VideoBackgroundView`, ver LandingView.swift). El archivo es una
        // copia del de iOS; si se cambia allá, hay que copiarlo también aquí.
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
            VideoBackground(modifier = Modifier.matchParentSize())

            // 🔴 Velo MÁS un degradado, no el 0.2 plano de iOS. Medido en una
            // N86: con sólo 0.2, en la toma de la calle soleada —cielo blanco
            // ocupando media pantalla— el tagline blanco y el botón "Crear
            // cuenta" se borraban. El video cambia de escena cada pocos
            // segundos, así que el contraste tiene que aguantar el frame MÁS
            // CLARO, no el promedio. El degradado carga la tinta arriba y abajo
            // (donde viven logo, texto y botones) y deja el centro despejado
            // para que el video siga viéndose.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.45f),
                            0.42f to Color.Black.copy(alpha = 0.10f),
                            1f to Color.Black.copy(alpha = 0.60f),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    // Respect system bars for content only
                    .padding(
                        top = systemBarsPadding.calculateTopPadding(),
                        bottom = systemBarsPadding.calculateBottomPadding(),
                    ),
            ) {
                // Top bar with logo
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // `avoqado_logo_mark` y no `avoqado_logo`: el original trae
                    // 65% de aire transparente alrededor del glifo, así que en
                    // 44dp la marca se veía de ~14dp — un puntito verde. El
                    // mark está recortado a su contenido y sí llena la caja.
                    Image(
                        painter = painterResource(id = com.avoqado.pos.R.drawable.avoqado_logo_mark),
                        contentDescription = "Avoqado",
                        modifier = Modifier.size(44.dp),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Tagline - matching iOS 38pt light weight, white
                //
                // 🔴 `lineHeight` explícito: sin él, Text hereda el del estilo
                // base del tema —muy por debajo de 38sp— y en cuanto la frase
                // se parte en dos renglones, SE ENCABALGAN. En la PAX (720px de
                // ancho) se leía "barrio." pisando "Empezó en tu". Es la PRIMERA
                // pantalla que ve cualquiera. Medido en una A910S el 2026-08-04.
                Text(
                    text = "Empezó en tu barrio.",
                    fontSize = 38.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                )
                Text(
                    text = "Terminó en todo México.",
                    fontSize = 38.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                )

                Spacer(modifier = Modifier.weight(2f))

                // Sign in + Create account buttons
                Row {
                    Button(
                        onClick = { showSignIn = true },
                        modifier = Modifier.height(52.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Text(
                            text = "Iniciar sesión",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    val context = LocalContext.current
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dashboard.avoqado.io/signup"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.height(52.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                        ),
                        border = BorderStroke(1.dp, Color.White),
                    ) {
                        Text(
                            text = "Crear cuenta",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

/**
 * El mp4 en bucle, recortado para LLENAR la pantalla — el equivalente de
 * `.resizeAspectFill` que usa iOS en `VideoBackgroundView`.
 *
 * 🔴 `VideoView` solo hace *letterbox*: respeta la proporción DENTRO de sus
 * límites. Puesto a pantalla completa en una terminal vertical (720x1280), un
 * video 16:9 saldría como una franja en medio de dos bandas negras enormes. Por
 * eso aquí se le da un tamaño MAYOR que la pantalla por el lado que sobra y el
 * `clipToBounds` del padre recorta: eso es aspect fill.
 */
@Composable
private fun VideoBackground(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val videoAspect = 1280f / 720f
        val boxAspect = maxWidth / maxHeight
        val width = if (boxAspect < videoAspect) maxHeight * videoAspect else maxWidth
        val height = if (boxAspect < videoAspect) maxHeight else maxWidth / videoAspect

        AndroidView(
            // 🔴 `requiredSize` y NO `size`: el Box padre mide a sus hijos con
            // SUS constraints (el ancho de la pantalla), así que `size` se dejaba
            // recortar y el video volvía a salir en letterbox — justo lo que esta
            // función existe para evitar. `requiredSize` ignora al padre.
            modifier = Modifier.requiredSize(width, height),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(
                        Uri.parse(
                            "android.resource://${ctx.packageName}/${com.avoqado.pos.R.raw.background_video}",
                        ),
                    )
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        // Mudo: es un letrero de bienvenida, no un anuncio. Y el
                        // volumen del aparato lo maneja el negocio, no nosotros.
                        mp.setVolume(0f, 0f)
                        start()
                    }
                }
            },
            // Sin esto el reproductor sigue vivo tras salir de la pantalla.
            onRelease = { it.stopPlayback() },
        )
    }
}
