package com.avoqado.pos.kiosk.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.customerdisplay.NumericPad
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.kiosk.domain.KioskContent
import com.avoqado.pos.kiosk.domain.KioskPack
import com.avoqado.pos.kiosk.domain.KioskPerson
import com.avoqado.pos.kiosk.domain.KioskSession
import com.avoqado.pos.kiosk.domain.KioskState

// MARK: - Escala de la cara del cliente
//
// Se lee de pie, a medio metro, por alguien que trae prisa. Es la misma escala
// que usa el espejo del mostrador (CustomerDisplayScreen) — mismo hardware,
// misma distancia, misma letra.

private val KTitle = 40.sp
private val KBody = 26.sp
private val KAction = 30.sp
private val KDigits = 46.sp
private val KSmall = 20.sp

/**
 * La cara del cliente trabajando como KIOSCO.
 *
 * Vive en la MISMA ventana que el espejo del mostrador y se turnan: quien
 * decide cuál se pinta es [KioskState.enabled], una sola bifurcación arriba de
 * todo. Mientras el kiosco esté apagado, este archivo no se ejecuta.
 *
 * 🔴 **Aquí no hay teclado del sistema.** La ventana de la segunda pantalla es
 * `FLAG_NOT_FOCUSABLE` a propósito, para no robarle el foco a la caja: si el
 * cliente teclea, es con el teclado dibujado por la app ([NumericPad]). Poner un
 * campo de texto normal en esta pantalla se ve bien y nunca recibe una letra.
 */
@Composable
fun KioskScreen(
    state: KioskState,
    venueName: String?,
) {
    val content by state.content.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        when (val c = content) {
            is KioskContent.Welcome -> Welcome(venueName)

            is KioskContent.Roster -> Roster(
                content = c,
                onTap = { state.onCheckIn?.invoke(it) },
                onIdentify = state.onStart?.let { cb -> { cb() } },
                onSeePacks = state.onSeePacks?.let { cb -> { cb() } },
            )

            is KioskContent.Identify -> Identify(
                content = c,
                onDigit = { state.onDigit?.invoke(it) },
                onDelete = { state.onDelete?.invoke() },
                onSearch = { state.onSearch?.invoke() },
                onBack = { state.onRestart?.invoke() },
            )

            is KioskContent.Found -> Found(
                content = c,
                onCheckIn = { state.onCheckInSession?.invoke(it) },
                onSeePacks = { state.onSeePacks?.invoke() },
                onDone = { state.onRestart?.invoke() },
            )

            is KioskContent.CheckedIn -> CheckedIn(c)

            is KioskContent.Offer -> Offer(
                content = c,
                onToggle = { state.onPackToggled?.invoke(it) },
                onBuy = { state.onBuy?.invoke() },
                onBack = { state.onRestart?.invoke() },
            )

            is KioskContent.Paying -> Paying(c)

            is KioskContent.Purchased -> Purchased(c)

            is KioskContent.Trouble -> Trouble(c) { state.onRestart?.invoke() }
        }
    }
}

// MARK: - 1. Reposo

/**
 * En reposo. **Sin botón a propósito**: aquí no hay nada que empezar, porque la
 * lista de check-in la abre el reloj sola cuando se acerca la clase. Un botón
 * que sólo dijera "no hay clase ahorita" invita a tocarlo y no lleva a ningún
 * lado.
 */
@Composable
private fun Welcome(venueName: String?) {
    Screen {
        Spacer(Modifier.weight(1f))
        venueName?.let {
            Text(
                text = it.uppercase(),
                fontSize = KSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        }
        Big("Bienvenida")
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Sub("El registro se abre antes de cada clase.")
        Spacer(Modifier.weight(1f))
    }
}

// MARK: - La lista de la clase en curso

/**
 * Quién viene a la clase de ahorita. Tocas tu nombre y ya.
 *
 * 🔴 Al confirmar **NO se cambia de pantalla**. En una clase se registran varias
 * personas seguidas: una confirmación a pantalla completa dejaría a la de atrás
 * esperando. El renglón se marca y la fila avanza.
 */
@Composable
private fun Roster(
    content: KioskContent.Roster,
    onTap: (KioskPerson) -> Unit,
    onIdentify: (() -> Unit)? = null,
    onSeePacks: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Encabezado y pie FIJOS; sólo la lista se desliza.
        Text(
            text = content.timeLabel,
            fontSize = KSmall,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
        Big(content.classTitle)
        content.staffLabel?.let {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxs))
            Sub(it)
        }

        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Sub("Toca tu nombre para confirmar que llegaste.")
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))

        // 🔴 LazyColumn y no un Column suelto: en la cara del cliente (800×1280)
        // caben CUATRO nombres. Una clase de ocho dejaba a la mitad del grupo
        // fuera de la pantalla, sin manera de bajar — se vio en el emulador,
        // no en el compilador.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            items(content.people, key = { it.reservationId }) { p ->
                PersonRow(
                    person = p,
                    busy = content.busyId == p.reservationId,
                    expanded = content.justConfirmedId == p.reservationId,
                    staffLabel = content.staffLabel,
                    onTap = { onTap(p) },
                )
            }
        }

        if (content.failed) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
            Note("No se pudo confirmar. Avisa en el mostrador.")
        }

        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        // Las dos salidas cuando la lista no alcanza. Se pintan SÓLO si su callback
        // existe: una opción que no hace nada es peor que no ofrecerla.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onIdentify != null) {
                FooterAction("¿No apareces?", onClick = onIdentify)
            } else {
                Sub("¿No apareces? Pregunta en el mostrador.")
            }
            if (onSeePacks != null) FooterAction("Comprar paquete", onClick = onSeePacks)
        }
    }
}

/** Acción discreta del pie: el kiosco es para llegar, comprar es lo secundario. */
@Composable
private fun FooterAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
    )
}

@Composable
private fun PersonRow(
    person: KioskPerson,
    busy: Boolean,
    expanded: Boolean,
    staffLabel: String?,
    onTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            // Quien ya confirmó SE HUNDE (surfaceContainerLow) y quien falta
            // resalta: lo que queda por hacer es lo que tiene que saltar a la
            // vista. Nada de `primaryContainer`, que este tema no define y
            // saldría con el tinte morado de Material3.
            .background(
                if (person.checkedIn) MaterialTheme.colorScheme.surfaceContainerLow
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            // Ya confirmada: deja de ser tocable. Volver a tocarla no haría nada
            // y sólo hace dudar de si sirvió la primera vez.
            .clickable(enabled = !person.checkedIn && !busy, onClick = onTap)
            .padding(AvoqadoTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = person.displayName,
                fontSize = KAction,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            when {
                busy -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                person.checkedIn -> Text(
                    text = "✓ llegaste",
                    fontSize = KBody,
                    fontWeight = FontWeight.SemiBold,
                    color = Success,
                )
            }
        }

        // Se abre unos segundos justo después de confirmar, con lo que la
        // persona necesita para caminar a su lugar. Sólo se pinta lo que EXISTE:
        // sin acomodo configurado no hay lugar, y no se inventa uno.
        if (expanded) {
            val detalle = listOfNotNull(person.spotLabel, staffLabel)
            if (detalle.isNotEmpty()) {
                Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = detalle.joinToString(" · "),
                    fontSize = KBody,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// MARK: - Se cayó algo

@Composable
private fun Trouble(content: KioskContent.Trouble, onRestart: () -> Unit) {
    Screen {
        Spacer(Modifier.weight(1f))
        Big("Algo salió mal")
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Sub(content.message)
        Spacer(Modifier.weight(1f))
        // Siempre hay salida: un kiosco atorado en un error es un kiosco muerto.
        PrimaryAction("Empezar de nuevo", onClick = onRestart)
    }
}

// MARK: - Piezas compartidas

@Composable
internal fun Screen(body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        content = body,
    )
}

@Composable
internal fun Big(text: String) {
    Text(
        text = text,
        fontSize = KTitle,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun Sub(text: String) {
    Text(
        text = text,
        fontSize = KBody,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun Note(text: String) {
    Text(
        text = text,
        fontSize = KBody,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
internal fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = KAction,
            fontWeight = FontWeight.Bold,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun GhostAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = KBody, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Checkmark() {
    Box(
        modifier = Modifier
            .size(120.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✓",
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
