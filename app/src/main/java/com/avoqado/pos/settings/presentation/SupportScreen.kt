package com.avoqado.pos.settings.presentation

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Support Sections

private enum class SupportSection(val label: String) {
    HELP("Obtener ayuda"),
    TUTORIALS("Tutoriales"),
    HARDWARE("Pedir hardware"),
    FEEDBACK("Comentarios"),
    ABOUT("Acerca de"),
    LEGAL("Legal"),
}

// MARK: - Entry Point

@Composable
fun SupportScreen(
    isTablet: Boolean,
    onDismiss: () -> Unit,
) {
    if (isTablet) {
        TabletSupportLayout(onDismiss = onDismiss)
    } else {
        PhoneSupportLayout(onDismiss = onDismiss)
    }
}

// MARK: - Tablet Layout

@Composable
private fun TabletSupportLayout(onDismiss: () -> Unit) {
    var selectedSection by remember { mutableStateOf(SupportSection.HELP) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar (280dp)
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
            ) {
                CircleBackButton(onClick = onDismiss)
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = "Atención al cliente",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Section rows
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SupportSection.entries.forEach { section ->
                    SupportSectionRow(
                        section = section,
                        isSelected = section == selectedSection,
                        onClick = { selectedSection = section },
                    )
                }
            }
        }

        // Hairline divider
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            SupportSectionContent(section = selectedSection)
        }
    }
}

// MARK: - Phone Layout

@Composable
private fun PhoneSupportLayout(onDismiss: () -> Unit) {
    var showingSection by remember { mutableStateOf<SupportSection?>(null) }

    if (showingSection != null) {
        val section = showingSection!!
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = { showingSection = null })
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(modifier = Modifier.weight(1f)) {
                SupportSectionContent(section = section)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = onDismiss)
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                Text(
                    text = "Atención al cliente",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // WhatsApp contact card at the top
                Box(
                    modifier = Modifier.padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                ) {
                    WhatsAppContactCard()
                }

                SupportSection.entries.forEach { section ->
                    SupportSectionRow(
                        section = section,
                        isSelected = false,
                        onClick = { showingSection = section },
                    )
                }
            }
        }
    }
}

// MARK: - Section Row (matching ArticlesScreen pattern)

@Composable
private fun SupportSectionRow(
    section: SupportSection,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left border bar for selected state
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Text(
            text = section.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .padding(
                    horizontal = if (isSelected) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
        )
    }
}

// MARK: - Section Content Dispatcher

@Composable
private fun SupportSectionContent(section: SupportSection) {
    when (section) {
        SupportSection.HELP -> HelpContent()
        SupportSection.TUTORIALS -> TutorialsContent()
        SupportSection.HARDWARE -> HardwareContent()
        SupportSection.FEEDBACK -> FeedbackContent()
        SupportSection.ABOUT -> AboutContent()
        SupportSection.LEGAL -> LegalContent()
    }
}

// MARK: - WhatsApp Contact Card

private const val WHATSAPP_URL =
    "https://wa.me/525512956265?text=Hola,%20necesito%20ayuda%20con%20Avoqado%20POS"

@Composable
private fun WhatsAppContactCard() {
    val context = LocalContext.current
    val whatsAppGreen = Color(0xFF25D366)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        colors = CardDefaults.cardColors(containerColor = whatsAppGreen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WHATSAPP_URL))
                    context.startActivity(intent)
                }
                .padding(AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = "WhatsApp",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Contactar por WhatsApp",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = "+52 55 1295 6265",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
                Text(
                    text = "Respuesta rápida de lunes a viernes, 9am - 6pm",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Color.White,
            )
        }
    }
}

// MARK: - Help Content (FAQ cards with expandable answers)

private data class FaqItem(
    val question: String,
    val answer: String,
)

private val faqItems = listOf(
    FaqItem(
        question = "¿Cómo conecto una impresora?",
        answer = "Ve a Mas > Impresora y activa Bluetooth en tu dispositivo. Enciende la impresora y seleccionala de la lista de dispositivos disponibles.",
    ),
    FaqItem(
        question = "¿Cómo proceso un reembolso?",
        answer = "Ve a Pedidos, selecciona la transaccion y toca \"Reembolsar\". Puedes hacer reembolsos parciales o totales.",
    ),
    FaqItem(
        question = "¿Cómo agrego un producto?",
        answer = "Ve a Mas > Articulos > Productos y toca el boton \"+\" para crear un nuevo producto con nombre, precio y categoria.",
    ),
    FaqItem(
        question = "¿Cómo configuro la terminal de pago?",
        answer = "La terminal PAX se conecta automáticamente vía Bluetooth. Asegúrate de que esté encendida y en modo de emparejamiento.",
    ),
    FaqItem(
        question = "¿Cómo abro la caja?",
        answer = "Ve a Más > Caja. Desde ahí puedes registrar ingresos y egresos de efectivo, y ver el balance actual de tu caja.",
    ),
    FaqItem(
        question = "¿Cómo cambio de sucursal?",
        answer = "En la pantalla Mas, toca la tarjeta de sucursal en la parte superior. Si tienes acceso a multiples sucursales, podras seleccionar otra.",
    ),
    FaqItem(
        question = "¿Cómo genero un informe de ventas?",
        answer = "Ve a Mas > Informes. Selecciona el periodo deseado y el tipo de informe (ventas, productos, empleados).",
    ),
)

@Composable
private fun HelpContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        WhatsAppContactCard()

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

        Text(
            text = "Preguntas frecuentes",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        faqItems.forEach { faq ->
            FaqCard(faq = faq)
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
    }
}

@Composable
private fun FaqCard(faq: FaqItem) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = faq.question,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Text(
                    text = faq.answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = AvoqadoTheme.spacing.lg,
                            end = AvoqadoTheme.spacing.lg,
                            bottom = AvoqadoTheme.spacing.lg,
                        ),
                )
            }
        }
    }
}

// MARK: - Tutorials Content

private data class TutorialItem(
    val title: String,
    val description: String,
)

private val tutorialItems = listOf(
    TutorialItem("Primeros pasos con Avoqado", "Aprende a configurar tu punto de venta"),
    TutorialItem("Gestion de productos", "Cómo crear, editar y organizar productos"),
    TutorialItem("Procesamiento de pagos", "Acepta pagos con tarjeta y efectivo"),
    TutorialItem("Gestion de inventario", "Controla existencias y ordenes de compra"),
    TutorialItem("Informes y analiticas", "Entiende tus ventas y metricas"),
    TutorialItem("Gestion de empleados", "Reloj de entrada, permisos y roles"),
)

@Composable
private fun TutorialsContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Text(
            text = "Tutoriales",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        tutorialItems.forEach { tutorial ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tutorial.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = tutorial.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                        )
                        .padding(
                            horizontal = AvoqadoTheme.spacing.sm,
                            vertical = AvoqadoTheme.spacing.xxs,
                        ),
                ) {
                    Text(
                        text = "Próximamente",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// MARK: - Hardware Content

@Composable
private fun HardwareContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        Text(
            text = "Hardware compatible",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                Text(
                    text = "Terminal PAX A920 Pro",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Terminal de pago con pantalla táctil. Acepta tarjetas de crédito, débito y pagos sin contacto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Conexión: Bluetooth",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                Text(
                    text = "Impresora Bluetooth de recibos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Impresora termica portatil de 58mm o 80mm para imprimir recibos y tickets.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Conexión: Bluetooth",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = "Para solicitar hardware, contacta a tu representante de ventas o escribe a soporte@avoqado.io",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Feedback Content

@Composable
private fun FeedbackContent() {
    var feedbackText by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        Text(
            text = "Comentarios",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = "Tu opinion nos ayuda a mejorar Avoqado. Cuentanos que podemos hacer mejor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (sent) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Text(
                    text = "Gracias por tus comentarios. Los revisaremos pronto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
                )
            }
        } else {
            OutlinedTextField(
                value = feedbackText,
                onValueChange = { feedbackText = it },
                placeholder = {
                    Text(
                        "Escribe tus comentarios aquí...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                maxLines = 8,
            )

            PrimaryButton(
                text = "Enviar",
                onClick = {
                    if (feedbackText.isNotBlank()) {
                        sent = true
                        feedbackText = ""
                    }
                },
                enabled = feedbackText.isNotBlank(),
            )
        }
    }
}

// MARK: - About Content

@Composable
private fun AboutContent() {
    val context = LocalContext.current
    val versionName = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "1.0.0"
        }
    }
    val versionCode = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(pInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            "1"
        }
    }
    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Text(
            text = "Acerca de",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        AboutRow(label = "Aplicación", value = "Avoqado POS")
        AboutRow(label = "Versión", value = versionName)
        AboutRow(label = "Build", value = versionCode)
        AboutRow(label = "Dispositivo", value = deviceModel)
        AboutRow(label = "Sistema operativo", value = androidVersion)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// MARK: - Legal Content

@Composable
private fun LegalContent() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Text(
            text = "Legal",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        LegalRow(
            title = "Aviso de privacidad",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://avoqado.io/privacy"))
                context.startActivity(intent)
            },
        )
        LegalRow(
            title = "Terminos de servicio",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://avoqado.io/terms"))
                context.startActivity(intent)
            },
        )
        // El renglón se marca como pendiente en vez de aparentar que lleva a algo:
        // antes tocarlo lanzaba un Toast de "Próximamente" que en la Sunmi no se ve,
        // así que la fila simplemente no hacía nada.
        LegalRow(
            title = "Licencias de terceros",
            trailingText = "Próximamente",
            onClick = null,
        )
    }
}

@Composable
private fun LegalRow(
    title: String,
    onClick: (() -> Unit)? = null,
    /** Etiqueta al final, p. ej. "Próximamente" para lo que aún no existe. */
    trailingText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
