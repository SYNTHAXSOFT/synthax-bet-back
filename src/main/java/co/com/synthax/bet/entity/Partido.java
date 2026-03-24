package co.com.synthax.bet.entity;

import co.com.synthax.bet.enums.EstadoPartido;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "partidos")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Partido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_partido_api", unique = true)
    private String idPartidoApi;

    @Column(name = "equipo_local", nullable = false)
    private String equipoLocal;

    @Column(name = "equipo_visitante", nullable = false)
    private String equipoVisitante;

    @Column(name = "id_equipo_local_api")
    private String idEquipoLocalApi;

    @Column(name = "id_equipo_visitante_api")
    private String idEquipoVisitanteApi;

    @Column(name = "liga")
    private String liga;

    @Column(name = "id_liga_api")
    private String idLigaApi;

    @Column(name = "pais")
    private String pais;

    @Column(name = "temporada")
    private String temporada;

    @Column(name = "ronda")
    private String ronda;

    @Column(name = "fecha_partido")
    private LocalDateTime fechaPartido;

    @Column(name = "arbitro")
    private String arbitro;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private EstadoPartido estado = EstadoPartido.PROGRAMADO;

    @Column(name = "logo_local")
    private String logoLocal;

    @Column(name = "logo_visitante")
    private String logoVisitante;

    @Column(name = "goles_local")
    private Integer golesLocal;

    @Column(name = "goles_visitante")
    private Integer golesVisitante;

    @Column(name = "sincronizado_en")
    private LocalDateTime sincronizadoEn;

    @PrePersist
    protected void alCrear() {
        sincronizadoEn = LocalDateTime.now();
    }

    @PreUpdate
    protected void alActualizar() {
        sincronizadoEn = LocalDateTime.now();
    }
}
