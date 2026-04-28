package co.com.synthax.bet.proveedor.modelo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Modelo neutro de estadísticas de equipo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstadisticaExterna {

    private String idEquipo;
    private String nombreEquipo;
    private String temporada;

    // Goles — total temporada
    private Double promedioGolesFavor;
    private Double promedioGolesContra;

    // Goles — split casa / visita
    private Double promedioGolesFavorCasa;
    private Double promedioGolesFavorVisita;
    private Double promedioGolesContraCasa;
    private Double promedioGolesContraVisita;

    // Corners — promedio total
    private Double promedioCornersFavor;
    private Double promedioCornersContra;

    // Corners — split casa / visita (calculado desde historial de fixtures)
    private Double promedioCornersFavorCasa;
    private Double promedioCornersFavorVisita;
    private Double promedioCornersContraCasa;
    private Double promedioCornersContraVisita;

    // Tarjetas — total temporada
    private Double promedioTarjetasAmarillas;
    private Double promedioTarjetasRojas;

    // Tarjetas — split casa / visita (calculado desde historial de fixtures)
    private Double promedioTarjetasCasa;
    private Double promedioTarjetasVisita;

    // Tiros
    private Double promedioTiros;

    // xG (expected goals)
    private Double promedioXg;

    // Porcentajes
    private Double porcentajeBtts;    // ambos marcan
    private Double porcentajeOver25;  // más de 2.5 goles

    // Forma reciente — promedio de goles de los últimos 10 partidos completados.
    // Se calcula desde el historial de fixtures (/fixtures?team=X&last=10&status=FT).
    // Permite aplicar decay temporal: ponderar más el momento actual que toda la temporada.
    private Double promedioGolesFavorReciente;
    private Double promedioGolesContraReciente;

    private Integer partidosAnalizados = 0; // default 0 → nunca null; evita NPE en auto-unboxing
}
