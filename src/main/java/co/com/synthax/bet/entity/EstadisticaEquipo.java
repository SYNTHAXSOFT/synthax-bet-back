package co.com.synthax.bet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "estadisticas_equipos",
        uniqueConstraints = @UniqueConstraint(columnNames = {"id_equipo", "temporada"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstadisticaEquipo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_equipo", nullable = false)
    private String idEquipo;

    @Column(name = "nombre_equipo")
    private String nombreEquipo;

    @Column(name = "temporada", nullable = false)
    private String temporada;

    // Goles — promedio total de temporada
    @Column(name = "promedio_goles_favor", precision = 5, scale = 2)
    private BigDecimal promedioGolesFavor;

    @Column(name = "promedio_goles_contra", precision = 5, scale = 2)
    private BigDecimal promedioGolesContra;

    // Goles — split casa / visita (más preciso para el modelo Poisson)
    @Column(name = "promedio_goles_favor_casa", precision = 5, scale = 2)
    private BigDecimal promedioGolesFavorCasa;

    @Column(name = "promedio_goles_favor_visita", precision = 5, scale = 2)
    private BigDecimal promedioGolesFavorVisita;

    @Column(name = "promedio_goles_contra_casa", precision = 5, scale = 2)
    private BigDecimal promedioGolesContraCasa;

    @Column(name = "promedio_goles_contra_visita", precision = 5, scale = 2)
    private BigDecimal promedioGolesContraVisita;

    // Corners — promedio total (todos los partidos)
    @Column(name = "promedio_corners_favor", precision = 5, scale = 2)
    private BigDecimal promedioCornersFavor;

    @Column(name = "promedio_corners_contra", precision = 5, scale = 2)
    private BigDecimal promedioCornersContra;

    // Corners — split casa / visita (calculado desde historial de fixtures)
    // Permite al modelo Poisson usar el contexto real del partido en lugar del promedio global.
    // Un equipo top puede generar 6.5 corners en casa pero solo 4.2 de visitante.
    @Column(name = "promedio_corners_favor_casa", precision = 5, scale = 2)
    private BigDecimal promedioCornersFavorCasa;

    @Column(name = "promedio_corners_favor_visita", precision = 5, scale = 2)
    private BigDecimal promedioCornersFavorVisita;

    @Column(name = "promedio_corners_contra_casa", precision = 5, scale = 2)
    private BigDecimal promedioCornersContraCasa;

    @Column(name = "promedio_corners_contra_visita", precision = 5, scale = 2)
    private BigDecimal promedioCornersContraVisita;

    // Tarjetas — total temporada
    @Column(name = "promedio_tarjetas", precision = 5, scale = 2)
    private BigDecimal promedioTarjetas;

    // Tarjetas — split casa / visita (calculado desde historial de fixtures)
    @Column(name = "promedio_tarjetas_casa", precision = 5, scale = 2)
    private BigDecimal promedioTarjetasCasa;

    @Column(name = "promedio_tarjetas_visita", precision = 5, scale = 2)
    private BigDecimal promedioTarjetasVisita;

    // Tiros
    @Column(name = "promedio_tiros", precision = 5, scale = 2)
    private BigDecimal promedioTiros;

    // xG
    @Column(name = "promedio_xg", precision = 5, scale = 2)
    private BigDecimal promedioXg;

    // Porcentajes
    @Column(name = "porcentaje_btts", precision = 5, scale = 4)
    private BigDecimal porcentajeBtts;

    @Column(name = "porcentaje_over25", precision = 5, scale = 4)
    private BigDecimal porcentajeOver25;

    // Forma reciente — goles promedio de los últimos 10 partidos completados.
    // Calculados desde el historial de fixtures durante el enriquecimiento de stats.
    // Permite el decay temporal en los modelos Poisson (blend 75% temporada / 25% reciente).
    @Column(name = "promedio_goles_favor_reciente", precision = 5, scale = 2)
    private BigDecimal promedioGolesFavorReciente;

    @Column(name = "promedio_goles_contra_reciente", precision = 5, scale = 2)
    private BigDecimal promedioGolesContraReciente;

    @Column(name = "ultima_actualizacion")
    private LocalDateTime ultimaActualizacion;

    @PrePersist
    @PreUpdate
    protected void alGuardar() {
        ultimaActualizacion = LocalDateTime.now();
    }
}
