package co.com.synthax.bet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "arbitros")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Arbitro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false, unique = true)
    private String nombre;

    @Column(name = "promedio_tarjetas_amarillas", precision = 5, scale = 2)
    private BigDecimal promedioTarjetasAmarillas;

    @Column(name = "promedio_tarjetas_rojas", precision = 5, scale = 2)
    private BigDecimal promedioTarjetasRojas;

    @Column(name = "promedio_corners", precision = 5, scale = 2)
    private BigDecimal promedioCorners;

    @Column(name = "promedio_faltas", precision = 5, scale = 2)
    private BigDecimal promedioFaltas;

    @Column(name = "partidos_analizados")
    private Integer partidosAnalizados = 0;

    @Column(name = "ultima_actualizacion")
    private LocalDateTime ultimaActualizacion;

    @PrePersist
    @PreUpdate
    protected void alGuardar() {
        ultimaActualizacion = LocalDateTime.now();
    }
}
