package co.com.synthax.bet.proveedor;

import co.com.synthax.bet.proveedor.modelo.CuotaExterna;

import java.util.List;

/**
 * Contrato para proveedores de cuotas de casas de apuestas.
 * Permite separar la fuente de cuotas de la fuente de datos de partidos.
 */
public interface ProveedorCuotas {

    /**
     * Cuotas de todos los mercados disponibles para un partido.
     */
    List<CuotaExterna> obtenerCuotasPorPartido(String idPartido);

    /**
     * Cuotas de un mercado específico para un partido.
     * Ej: mercado = "Over 2.5", "1X2", "BTTS"
     */
    List<CuotaExterna> obtenerCuotasPorMercado(String idPartido, String mercado);

    /**
     * Nombre del proveedor activo.
     */
    String nombreProveedor();
}
