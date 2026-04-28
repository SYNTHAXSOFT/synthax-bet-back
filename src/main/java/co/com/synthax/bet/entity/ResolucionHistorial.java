package co.com.synthax.bet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "resoluciones_historial",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_resolucion_fecha_partido_mercado",
        columnNames = {"fecha_lote", "id_partido", "mercado"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResolucionHistorial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fecha_lote", nullable = false)
    private LocalDate fechaLote;

    @Column(name = "id_partido", nullable = false)
    private Long idPartido;

    @Column(name = "partido", nullable = false)
    private String partido;

    @Column(name = "liga")
    private String liga;

    @Column(name = "hora_partido")
    private String horaPartido;

    @Column(name = "categoria")
    private String categoria;

    @Column(name = "mercado", nullable = false)
    private String mercado;

    @Column(name = "probabilidad")
    private Double probabilidad;

    @Column(name = "cuota")
    private Double cuota;

    @Column(name = "edge")
    private Double edge;

    @Column(name = "resultado_real")
    private String resultadoReal;

    @Column(name = "goles_local")
    private Integer golesLocal;

    @Column(name = "goles_visitante")
    private Integer golesVisitante;

    @Column(name = "acerto")
    private Boolean acerto;

    @Column(name = "verificable")
    private boolean verificable;

    @Column(name = "candidata_sugerida")
    private Boolean candidataSugerida;

    @Column(name = "registrado_en")
    private LocalDateTime registradoEn;

    @PrePersist
    protected void alPersistir() {
        if (registradoEn == null) registradoEn = LocalDateTime.now();
    }

    @PreUpdate
    protected void alActualizar() {
        registradoEn = LocalDateTime.now();
    }
}
