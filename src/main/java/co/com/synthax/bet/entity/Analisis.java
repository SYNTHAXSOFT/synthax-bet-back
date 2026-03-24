package co.com.synthax.bet.entity;

import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.enums.NivelConfianza;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "analisis")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Analisis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "id_partido", nullable = false)
    private Partido partido;

    @Enumerated(EnumType.STRING)
    @Column(name = "categoria_mercado", nullable = false)
    private CategoriaAnalisis categoriaMercado;

    @Column(name = "nombre_mercado", nullable = false)
    private String nombreMercado;  // "Over 2.5", "BTTS Sí", "Over 9.5 Corners"

    @Column(name = "probabilidad", precision = 5, scale = 4)
    private BigDecimal probabilidad;  // 0.8700 = 87%

    @Enumerated(EnumType.STRING)
    @Column(name = "nivel_confianza")
    private NivelConfianza nivelConfianza;

    // Variables usadas en el cálculo — snapshot JSON para auditoría
    @Column(name = "variables_usadas", columnDefinition = "JSON")
    private String variablesUsadas;

    @Column(name = "calculado_en")
    private LocalDateTime calculadoEn;

    @PrePersist
    protected void alCrear() {
        calculadoEn = LocalDateTime.now();
        calcularNivelConfianza();
    }

    @PreUpdate
    protected void alActualizar() {
        calculadoEn = LocalDateTime.now();
        calcularNivelConfianza();
    }

    private void calcularNivelConfianza() {
        if (probabilidad == null) return;
        double prob = probabilidad.doubleValue();
        if (prob >= 0.80)      nivelConfianza = NivelConfianza.MUY_ALTA;
        else if (prob >= 0.70) nivelConfianza = NivelConfianza.ALTA;
        else if (prob >= 0.60) nivelConfianza = NivelConfianza.MEDIA;
        else                   nivelConfianza = NivelConfianza.BAJA;
    }
}
