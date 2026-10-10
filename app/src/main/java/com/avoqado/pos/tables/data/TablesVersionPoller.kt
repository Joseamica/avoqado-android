package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.network.ApiService
import retrofit2.HttpException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Lo que contestó `/tables/version`. Espejo: avoqado-ios `ConsultaDeVersiones`. */
sealed interface ConsultaDeVersiones {
    data class Versiones(val tablesVersion: String, val floorPlanVersion: String) : ConsultaDeVersiones

    /** 404: el servidor no conoce la ruta (todavía no se despliega la fase 4a). */
    data object ServidorViejo : ConsultaDeVersiones

    /** Sin red, tiempo agotado u otro error: no se sabe nada y no se toca nada. */
    data object SinRespuesta : ConsultaDeVersiones
}

interface FuenteDeVersiones {
    suspend fun consultar(venueId: String): ConsultaDeVersiones
}

class ApiFuenteDeVersiones @Inject constructor(private val apiService: ApiService) : FuenteDeVersiones {
    override suspend fun consultar(venueId: String): ConsultaDeVersiones = try {
        val data = apiService.getTablesVersion(venueId).data
        if (data == null || data.tablesVersion.isEmpty()) {
            ConsultaDeVersiones.SinRespuesta
        } else {
            ConsultaDeVersiones.Versiones(data.tablesVersion, data.floorPlanVersion)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        if (e.code() == 404) ConsultaDeVersiones.ServidorViejo else ConsultaDeVersiones.SinRespuesta
    } catch (e: Exception) {
        ConsultaDeVersiones.SinRespuesta
    }
}

/**
 * Plano en el POS (spec 2026-10-09 §4.1): qué pedir al entrar a Mesas, en cada consulta de 10 s y al refrescar.
 * Uno por pantalla (lo crea el ViewModel). Espejo: avoqado-ios `Tables/TablesVersionPoller.swift`.
 */
class TablesVersionPoller(
    private val mesas: TableServiceRepository,
    private val plano: FloorPlanRepository,
    private val versiones: FuenteDeVersiones,
) {
    /**
     * El servidor no conoce `/tables/version` (404) o sus mesas no traen versión: se pide `/tables` completo cada vez, como
     * antes de esta fase. Se recuerda en memoria y se olvida al volver a entrar a la pantalla (a lo más un 404 por visita).
     */
    var servidorViejo: Boolean = false
        private set

    suspend fun entrar(venueId: String) {
        servidorViejo = false
        mesas.hydrate(venueId)
        plano.hydrate(venueId)
        val mesasAlDia = mesas.refresh(venueId).isSuccess
        if (mesasAlDia && mesas.tablesVersion == null) {
            servidorViejo = true
            return
        }
        val guardado = plano.floorPlanVersion
        if (guardado == null || (mesasAlDia && mesas.floorPlanVersionFromTables != guardado)) {
            plano.refresh(venueId)
        }
    }

    suspend fun consultar(venueId: String) {
        if (servidorViejo) {
            mesas.refresh(venueId)
            return
        }
        when (val r = versiones.consultar(venueId)) {
            is ConsultaDeVersiones.Versiones -> {
                if (r.tablesVersion != mesas.tablesVersion) mesas.refresh(venueId)
                if (r.floorPlanVersion != plano.floorPlanVersion) plano.refresh(venueId)
            }
            ConsultaDeVersiones.ServidorViejo -> {
                servidorViejo = true
                mesas.refresh(venueId)
            }
            // Sin red: lo guardado se queda; el aviso «Sin conexión» ya lo pone el banner de conectividad.
            ConsultaDeVersiones.SinRespuesta -> Unit
        }
    }

    /** El botón de refrescar: las dos cosas completas, sin mirar versiones. */
    suspend fun refrescarTodo(venueId: String) {
        mesas.refresh(venueId)
        if (!servidorViejo) plano.refresh(venueId)
    }
}
