package com.avoqado.pos.printing.data

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * nab17320 (tarjeta de la nube, 17-nov; Codex r4): «Ya la canté» y el replay de entregas a la pantalla de cocina se turnan.
 *
 * El replay revisa que una entrega siga pendiente y luego la imprime; «Ya la canté» borra la entrega y quita su marca. Sin un
 * turno, cualquier espera entre «revisar» e «imprimir» (la consulta a la base, el refresco de impresoras) dejaba que el cierre
 * terminara en medio y saliera el papel de una comanda ya cantada. Con este candado, el replay hace TODO lo de una fila
 * —revisar, refrescar, imprimir y borrar— con el turno tomado, y el cierre de «Ya la canté» borra con el turno tomado.
 *
 * 🔴 Sólo esos dos lo toman. `cerrarPorPapel` NO lo toma: lo llama el propio `reintentar` del replay (el papel que salió), y el
 * candado no es reentrante. Una instancia por proceso (Hilt `@Singleton`).
 */
@Singleton
class CandadoDeCierres @Inject constructor() {
    val turno = Mutex()
}
