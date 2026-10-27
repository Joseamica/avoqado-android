package com.avoqado.pos.kiosk.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.customerdisplay.NumericPad
import com.avoqado.pos.customerdisplay.QrCode
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.kiosk.domain.KioskContent
import com.avoqado.pos.kiosk.domain.KioskPack
import com.avoqado.pos.kiosk.domain.KioskSession

/**
 * Autoservicio A MANO: identificarse tecleando y comprar un paquete.
 *
 * ⚠️ **Todavía no se llega aquí.** El kiosco de hoy lo maneja el reloj: abre la
 * lista de la clase en curso y se toca un nombre ([KioskScreen]). Estas
 * pantallas son el respaldo para quien no aparece en la lista y el carril de
 * compra, y entran en las rebanadas siguientes — la de compra, con sus pruebas
 * primero, porque toca dinero.
 *
 * Están escritas y compilando a propósito: [com.avoqado.pos.kiosk.domain.KioskDriver]
 * deja sus callbacks en nulo, y una pantalla sin callback NI PINTA su botón.
 */

private val KTitle = 40.sp
private val KBody = 26.sp
private val KAction = 30.sp
private val KDigits = 46.sp
private val KSmall = 20.sp

// MARK: - 2. Se identifica

@Composable
internal fun Identify(
    content: KioskContent.Identify,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
) {
    // 🔴 La cara del cliente de la D3 puede estar ACOSTADA (1280×800, demo de Mindform 26-oct):
    // en una columna el teclado ocupaba todo el alto, «Buscar» quedaba cortado abajo y al
    // bajar a buscarlo se perdía de vista el número tecleado. Acostada va en dos mitades —el
    // número y los botones a la izquierda, el teclado a la derecha— y todo cabe sin scroll.
    // Sólo si hay alto para el teclado entero (la D3 da ~600 dp); una pantalla acostada más
    // chica conserva la columna con scroll de antes (Codex r1, 26-oct).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight && maxHeight >= TWO_HALVES_MIN_HEIGHT) {
            Row(
                modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    IdentifyHeader(content)
                    Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
                    IdentifyActions(content, onSearch, onBack)
                }
                Box(Modifier.weight(1f)) {
                    NumericPad(onKey = onDigit, onDelete = onDelete, compact = true)
                }
            }
        } else {
            // Parada: con scroll, porque en 800 px de ancho el teclado no cabe entero con todo.
            Screen(scrollable = true) {
                IdentifyHeader(content)
                Spacer(Modifier.height(AvoqadoTheme.spacing.md))
                NumericPad(onKey = onDigit, onDelete = onDelete, compact = true)
                // Espaciador FIJO, no `weight`: dentro de un Column con scroll la altura deja de
                // estar acotada y `weight` no vale.
                Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
                IdentifyActions(content, onSearch, onBack)
            }
        }
    }
}

@Composable
private fun IdentifyHeader(content: KioskContent.Identify) {
    Big("Tu teléfono")
    Spacer(Modifier.height(AvoqadoTheme.spacing.md))

    Text(
        text = formatNational(content.national),
        fontSize = KDigits,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(Modifier.height(AvoqadoTheme.spacing.md))

    // Dos fracasos DISTINTOS, y decirlo importa: "no te encontramos" manda
    // a la persona con el mostrador; "se cayó la red" le dice que vuelva a
    // intentar. Un mensaje genérico la deja parada sin saber qué hacer.
    when {
        content.notFound -> Note("No encontramos ese número. Pregunta en el mostrador.")
        content.failed -> Note("No pudimos conectarnos. Inténtalo otra vez.")
        else -> Sub("Con el número que usaste al reservar.")
    }
}

@Composable
private fun IdentifyActions(content: KioskContent.Identify, onSearch: () -> Unit, onBack: () -> Unit) {
    if (content.searching) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    } else {
        PrimaryAction("Buscar", enabled = content.canSearch, onClick = onSearch)
    }
    Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
    // «Regresar» y no «Cancelar»: lleva de vuelta a la lista de la clase.
    GhostAction("Regresar", onClick = onBack)
}

private val TWO_HALVES_MIN_HEIGHT = 480.dp

/** "5512345678" -> "55 1234 5678". Sólo presentación. */
private fun formatNational(digits: String): String = when {
    digits.isEmpty() -> "—"
    digits.length <= 2 -> digits
    digits.length <= 6 -> "${digits.take(2)} ${digits.drop(2)}"
    else -> "${digits.take(2)} ${digits.drop(2).take(4)} ${digits.drop(6)}"
}

// MARK: - 3. Su clase

@Composable
internal fun Found(
    content: KioskContent.Found,
    onCheckIn: (KioskSession) -> Unit,
    onSeePacks: () -> Unit,
    onDone: () -> Unit,
) {
    Screen {
        Spacer(Modifier.weight(1f))
        Big("Hola, ${content.customerName}")
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))

        if (content.sessions.isEmpty()) {
            // Venir sin reserva NO es un error: es quien pasa a comprar. Por eso
            // esta pantalla ofrece paquetes en vez de disculparse.
            Sub("Hoy no tienes ninguna clase agendada.")
        } else {
            content.sessions.forEach { s ->
                SessionCard(s) { onCheckIn(s) }
                Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
            }
        }

        if (content.failed) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
            Note("No pudimos confirmar tu llegada. Avisa en el mostrador.")
        }

        Spacer(Modifier.weight(1f))
        GhostAction("Ver paquetes", onClick = onSeePacks)
        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        GhostAction("Listo", onClick = onDone)
    }
}

@Composable
private fun SessionCard(session: KioskSession, onCheckIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(AvoqadoTheme.spacing.lg),
    ) {
        Text(session.title, fontSize = KAction, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(AvoqadoTheme.spacing.xxs))
        Text(
            text = listOfNotNull(session.timeLabel, session.staffLabel).joinToString(" · "),
            fontSize = KBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        if (session.alreadyCheckedIn) {
            // Ya llegó: se lo confirmamos, no se lo volvemos a pedir. Un botón
            // que repite algo ya hecho invita a tocarlo dos veces.
            Text(
                text = "Ya confirmaste tu llegada",
                fontSize = KBody,
                fontWeight = FontWeight.SemiBold,
                color = Success,
            )
        } else {
            PrimaryAction("Confirmar que llegué", onClick = onCheckIn)
        }
    }
}

// MARK: - 4. Listo

@Composable
internal fun CheckedIn(content: KioskContent.CheckedIn) {
    Screen {
        Spacer(Modifier.weight(1f))
        Checkmark()
        Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
        Big("Listo, ${content.customerName}")
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        // El separador sólo si hay las dos partes: por el respaldo del teléfono la hora
        // llega vacía (el servidor devuelve la clase, no su etiqueta formateada) y quedaba
        // un "·" colgando al final. Se vio en la D3, de frente al cliente.
        // Lugar y coach, lo mismo que dice el renglón al tocar su nombre en la lista.
        val s = content.session
        Sub(listOfNotNull(s.title.takeIf { it.isNotBlank() }, s.timeLabel.takeIf { it.isNotBlank() }, s.spotLabel, s.staffLabel).joinToString(" · "))
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text("Puedes pasar", fontSize = KBody, fontWeight = FontWeight.SemiBold, color = Success)
        Spacer(Modifier.weight(1f))
    }
}

// MARK: - 5. Comprar un paquete

@Composable
internal fun Offer(
    content: KioskContent.Offer,
    onToggle: (String) -> Unit,
    onBuy: () -> Unit,
    onBack: () -> Unit,
) {
    Screen {
        Big("Paquetes")
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Sub("Elige uno y págalo con tu teléfono.")
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))

        content.packs.forEach { p ->
            PackCard(p, selected = p.id == content.selectedId) { onToggle(p.id) }
            Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        }

        Spacer(Modifier.weight(1f))
        PrimaryAction(
            label = content.selected?.let { "Comprar ${it.name}" } ?: "Elige un paquete",
            enabled = content.selected != null,
            onClick = onBuy,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        GhostAction("Cancelar", onClick = onBack)
    }
}

@Composable
private fun PackCard(pack: KioskPack, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pack.name, fontSize = KAction, fontWeight = FontWeight.Bold)
            pack.detail?.let {
                Spacer(Modifier.height(AvoqadoTheme.spacing.xxs))
                Text(it, fontSize = KSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(AvoqadoTheme.spacing.md))
        Text(pesos(pack.priceCents), fontSize = KAction, fontWeight = FontWeight.Bold)
    }
}

/** Centavos -> "$1,800". El precio ya viene del catálogo; aquí sólo se pinta. */
private fun pesos(cents: Int): String {
    val whole = cents / 100
    return "$" + whole.toString().reversed().chunked(3).joinToString(",").reversed()
}

// MARK: - 6. Pasa tu tarjeta

@Composable
internal fun Paying(content: KioskContent.Paying) {
    Screen {
        Spacer(Modifier.weight(1f))
        Big("Escanea para pagar")
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        // 🔴 Se paga en SU teléfono, no aquí: este aparato no tiene lector y, sobre todo,
        // nadie debería meter su tarjeta en la pantalla compartida de la entrada.
        Sub("Con la cámara de tu teléfono.")
        Spacer(Modifier.height(AvoqadoTheme.spacing.xl))

        when {
            content.failed -> Sub("No pudimos preparar el pago. Pide ayuda en el mostrador.")
            content.payUrl == null -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            else -> QrCode(content = content.payUrl, size = 260.dp)
        }

        Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
        Text(
            text = pesos(content.pack.priceCents),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
        Sub(content.pack.name)
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
        // 🔴 El kiosco NO sabe si el pago terminó: eso lo confirma Stripe contra el
        // servidor. Decir "ya está en tu cuenta" aquí sería inventarlo — la pantalla
        // promete lo único que sí es cierto.
        Sub("Tus clases aparecen solas al terminar el pago.")
        Spacer(Modifier.weight(1f))
    }
}

@Composable
internal fun Purchased(content: KioskContent.Purchased) {
    Screen {
        Spacer(Modifier.weight(1f))
        Checkmark()
        Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
        Big("Gracias, ${content.customerName}")
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Sub("${content.pack.name} ya está en tu cuenta.")
        Spacer(Modifier.weight(1f))
    }
}

