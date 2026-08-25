package com.avoqado.pos.kiosk.presentation

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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

// 🔴 Calibrados contra la cara del cliente de la Sunmi D3: 1280×800 con densidad 213 dpi.
// Antes venían de un mockup a pantalla completa y en el aparato se veían inflados —
// "muy grande, no se ve estético" (founder, viéndolo en la D3). El tamaño de un kiosco
// se decide EN el kiosco.
private val KTitle = 30.sp
private val KBody = 22.sp
private val KAction = 26.sp
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
                venueName = venueName,
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
    venueName: String?,
    onTap: (KioskPerson) -> Unit,
    onIdentify: (() -> Unit)? = null,
    onSeePacks: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Encabezado y pie FIJOS; sólo la lista se desliza.
        // El negocio primero, en versalitas: el mismo tono que la pantalla de reposo, para
        // que quien pasa de una a otra sienta que es el mismo lugar y no otra app.
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = listOfNotNull(venueName?.uppercase(), content.timeLabel).joinToString("  ·  "),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 3.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = content.classTitle,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.8).sp,
            lineHeight = 44.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        // Coach y cómo va llenándose el salón, en una sola línea tranquila. El conteo le
        // sirve a quien llega ("ya casi empieza") y a quien da la clase de un vistazo.
        val llegados = content.people.count { it.checkedIn }
        val subtitulo = listOfNotNull(
            content.staffLabel,
            if (content.people.isNotEmpty()) "$llegados de ${content.people.size} ya llegaron" else null,
        ).joinToString("  ·  ")
        if (subtitulo.isNotBlank()) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                text = subtitulo,
                fontSize = KBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(AvoqadoTheme.spacing.xl))

        // 🔴 DOS COLUMNAS en apaisado, una sola en vertical.
        //
        // La cara del cliente de la D3 es 1280×800 acostada. En una sola columna caben
        // CUATRO nombres y una clase de yoga es de ocho: media clase quedaba abajo,
        // fuera de la vista, y había que adivinar que la lista se desliza.
        //
        // Dos columnas entran los ocho de un vistazo SIN achicar el botón — que es lo
        // que no se puede sacrificar en un kiosco, porque aquí toca gente de pie, de
        // paso y a veces con las manos ocupadas. Sigue siendo Lazy: una clase de
        // veinte se desliza igual.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val columnas = if (maxWidth > maxHeight) 2 else 1
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnas),
                // Centrada: antes la lista se pegaba arriba y dejaba un hueco muerto
                // abajo que hacía ver la pantalla a medio terminar.
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md, Alignment.CenterVertically),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                // Aire abajo: sin esto el ÚLTIMO nombre queda medio tapado por el pie y
                // parece que la lista se acaba ahí. Se vio en la D3.
                contentPadding = PaddingValues(bottom = AvoqadoTheme.spacing.sm),
            ) {
                items(content.people, key = { it.reservationId }) { p ->
                    PersonRow(
                        person = p,
                        busy = content.busyId == p.reservationId,
                        expanded = content.justConfirmedId == p.reservationId,
                        onTap = { onTap(p) },
                    )
                }
            }
        }

        if (content.failed) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
            Note("No se pudo confirmar. Avisa en el mostrador.")
        }

        // 🔴 ZONA DE CONFIRMACIÓN, de alto FIJO y DEBAJO de la lista.
        //
        // El founder pidió que al hacer check-in salga la confirmación con su lugar y su
        // coach. No puede ser a pantalla completa: taparía la lista y dejaría esperando a
        // la persona de atrás — que es exactamente lo que prohíbe la nota de arriba, y el
        // kiosco existe para que dos personas lo usen a la vez.
        //
        // Alto FIJO —no `animateContentSize`— porque si esta zona creciera empujaría la
        // lista y el toque de quien viene atrás caería en el hueco. Ese defecto ya se vio
        // en la D3 y no se vuelve a introducir por una animación bonita.
        Confirmation(content = content)

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

/**
 * El momento del check-in: quién eres, dónde te toca y con quién.
 *
 * 🔴 Vive DEBAJO de la lista y con alto FIJO, no encima. Una confirmación a pantalla
 * completa se ve mejor en una captura y es peor en el mostrador: taparía los demás
 * nombres y dejaría esperando a la persona de atrás justo cuando el kiosco existe para
 * que dos usen la pantalla a la vez.
 *
 * Vacía reserva su espacio en silencio. Así, cuando aparece, **nada de arriba se mueve** —
 * el toque de quien viene atrás cae donde estaba mirando.
 */
@Composable
private fun Confirmation(content: KioskContent.Roster) {
    // 🔴 La tarjeta ENGANCHA a la persona y la sostiene ella misma.
    //
    // Antes leía `justConfirmedId` directo del contenido, y esa bandera la puede borrar
    // cualquier reconstrucción de la lista: el tick de refresco, una recarga, un cambio de
    // clase. En la D3 se midió instrumentando la pantalla — la tarjeta aparecía y
    // desaparecía en la misma respiración, antes de que nadie alcanzara a leerla.
    //
    // Con el enganche, el ciclo de refresco puede hacer lo que quiera: quien acaba de
    // confirmar tiene sus segundos completos para leer su lugar y con quién le toca.
    var enganchada by remember { mutableStateOf<KioskPerson?>(null) }
    LaunchedEffect(content.justConfirmedId) {
        val quien = content.people.firstOrNull { it.reservationId == content.justConfirmedId }
        if (quien != null) {
            enganchada = quien
            delay(CONFIRMATION_MS)
            enganchada = null
        }
    }

    val person = enganchada
    val staffLabel = content.staffLabel
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CONFIRMATION_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        if (person == null) return@Box

        // Sólo lo que EXISTE. Sin acomodo configurado no hay lugar, y no se inventa uno.
        val detalle = listOfNotNull(person.spotLabel, staffLabel)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Success),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(Modifier.width(AvoqadoTheme.spacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = person.displayName,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (detalle.isNotEmpty()) {
                    Spacer(Modifier.height(AvoqadoTheme.spacing.xxs))
                    Text(
                        text = detalle.joinToString(" · "),
                        fontSize = KBody,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            Text(
                text = "Puedes pasar",
                fontSize = KSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                color = Success,
            )
        }
    }
}

/** Lo que mide la zona de confirmación, ocupada o no. Fijo: que nada se mueva. */
private val CONFIRMATION_HEIGHT = 108.dp

/** Cuánto se sostiene la confirmación. Lo bastante para leer el lugar sin frenar la fila. */
private const val CONFIRMATION_MS = 8_000L

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
            // Quien ya confirmó se APAGA y quien falta resalta: lo que queda por hacer
            // es lo que tiene que saltar a la vista en una pantalla que se mira de paso.
            .background(
                if (person.checkedIn) MaterialTheme.colorScheme.surfaceContainerLowest
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            // Ya confirmada: deja de ser tocable. Volver a tocarla no haría nada
            // y sólo hace dudar de si sirvió la primera vez.
            .clickable(enabled = !person.checkedIn && !busy, onClick = onTap)
            // md y no lg: el alto de la fila es lo que decide cuántos nombres caben, y
            // 12 dp arriba y abajo siguen dejando un blanco de sobra para el dedo.
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = person.displayName,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = if (person.checkedIn) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            when {
                busy -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                person.checkedIn -> Text(
                    // 🔴 EN LA MISMA LÍNEA, y la altura del renglón NUNCA cambia.
                    //
                    // Antes esto se abría en una segunda línea unos segundos. En la D3 se
                    // vio lo que eso provoca: al confirmar Ñuño, su renglón creció y
                    // EMPUJÓ ~50 px hacia abajo a todos los de su fila. La persona de al
                    // lado —que en un kiosco está usando la pantalla AL MISMO TIEMPO— ya
                    // venía bajando el dedo hacia su nombre y cayó en el hueco.
                    //
                    // Es el mismo peligro que el reordenamiento, por otra puerta: la lista
                    // no puede moverse bajo el dedo de nadie. Que quepa en un renglón es
                    // lo que lo garantiza, no un cuidado al escribir la animación.
                    //
                    // El instructor NO se repite aquí: ya está en el encabezado, arriba.
                    // "Check-in", no "llegaste": es el término que el sector usa y además
                    // NO tiene género — "llegaste" obligaba a inventar concordancia con
                    // nombres que pueden ser de cualquier persona.
                    text = "✓ Check-in",
                    fontSize = KSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = Success,
                    maxLines = 1,
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
        Big(content.title)
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Sub(content.message)
        Spacer(Modifier.weight(1f))
        // Siempre hay salida: un kiosco atorado en un error es un kiosco muerto.
        PrimaryAction("Empezar de nuevo", onClick = onRestart)
    }
}

// MARK: - Piezas compartidas

@Composable
internal fun Screen(
    scrollable: Boolean = false,
    body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // 🔴 `scrollable` existe por la cara del cliente de la D3: 800 px de alto. El teclado
    // del teléfono NO cabía y su última fila —el 0, el borrar y el botón "Buscar"— quedaba
    // CORTADA fuera de la pantalla, sin aviso. Nadie podía teclear un número con cero ni
    // buscar: el respaldo entero era inservible en el aparato real.
    //
    // Se vio mirando la pantalla, no compilando: una Column que se pasa de alto no falla,
    // sólo recorta en silencio. En una pantalla de autoservicio eso es una función muerta.
    //
    // 🔴 Ojo: dentro de un Column con scroll, `Modifier.weight()` no vale (la altura deja
    // de estar acotada). Las pantallas que scrollean usan espaciadores fijos.
    val base = Modifier
        .fillMaxSize()
        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .padding(AvoqadoTheme.spacing.xl)

    Column(
        modifier = base,
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
