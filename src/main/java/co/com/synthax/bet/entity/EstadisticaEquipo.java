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

    // Goles
    @Column(name = "promedio_goles_favor", precision = 5, scale = 2)
    private BigDecimal promedioGolesFavor;

    @Column(name = "promedio_goles_contra", precision = 5, scale = 2)
    private BigDecimal promedioGolesContra;

    // Corners
    @Column(name = "promedio_corners_favor", precision = 5, scale = 2)
    private BigDecimal promedioCornersFavor;

    @Column(name = "promedio_corners_contra", precision = 5, scale = 2)
    private BigDecimal promedioCornersContra;

    // Tarjetas
    @Column(name = "promedio_tarjetas", precision = 5, scale = 2)
    private BigDecimal promedioTarjetas;

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

    @Column(name = "ultima_actualizacion")
    private LocalDateTime ultimaActualizacion;

    @PrePersist
    @PreUpdate
    protected void alGuardar() {
        ultimaActualizacion = LocalDateTime.now();
    }
}
