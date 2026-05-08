package com.avoqado.pos.auth.presentation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

        // Dark background goes edge-to-edge (behind status & nav bars)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1C1C1E)),
        ) {
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
                    Image(
                        painter = painterResource(id = com.avoqado.pos.R.drawable.avoqado_logo),
                        contentDescription = "Avoqado",
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Tagline - matching iOS 38pt light weight, white
                Text(
                    text = "Empezó en tu barrio.",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                )
                Text(
                    text = "Terminó en todo México.",
                    fontSize = 38.sp,
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
