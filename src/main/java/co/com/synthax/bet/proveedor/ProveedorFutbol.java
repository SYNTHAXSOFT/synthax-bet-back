package co.com.synthax.bet.proveedor;

import co.com.synthax.bet.proveedor.modelo.ArbitroExterno;
import co.com.synthax.bet.proveedor.modelo.CuotaExterna;
import co.com.synthax.bet.proveedor.modelo.EstadisticaExterna;
import co.com.synthax.bet.proveedor.modelo.PartidoExterno;

import java.time.LocalDate;
import java.util.List;

/**
 * Contrato que todo proveedor de datos de fútbol debe cumplir.
 * El motor de análisis solo habla con esta interfaz, nunca con una API directamente.
 * Cambiar de proveedor = cambiar una línea en application.properties.
 */
public interface ProveedorFutbol {

    /**
     * Partidos programados para una fecha.
     */
    List<PartidoExterno> obtenerPartidosDelDia(LocalDate fecha);

    /**
     * Partidos de una liga en una fecha específica.
     */
    List<PartidoExterno> obtenerPartidosPorLiga(String idLiga, LocalDate fecha);

    /**
     * Estadísticas históricas de un equipo en una temporada.
     */
    EstadisticaExterna obtenerEstadisticasEquipo(String idEquipo, String temporada);

    /**
     * Estadísticas históricas de un equipo con contexto de liga.
     * Necesario para proveedores como API-Football que requieren idLiga en el endpoint.
     * Por defecto delega al método sin liga (compatibilidad con otros proveedores).
     */
    default EstadisticaExterna obtenerEstadisticasEquipo(String idEquipo, String idLiga, String temporada) {
        return obtenerEstadisticasEquipo(idEquipo, temporada);
    }

    /**
     * Historial de enfrentamientos directos entre dos equipos.
     */
    List<PartidoExterno> obtenerHistorialH2H(String idLocal, String idVisitante);

    /**
     * Historial y promedios de un árbitro por nombre.
     */
    ArbitroExterno obtenerEstadisticasArbitro(String nombreArbitro);

    /**
     * Cuotas disponibles para un partido.
     */
    List<CuotaExterna> obtenerCuotasPartido(String idPartido);

    /**
     * Nombre del proveedor activo para logs y diagnóstico.
     */
    String nombreProveedor();

    /**
     * Requests disponibles hoy según el plan activo.
     */
    int requestsDisponiblesHoy();
}
