package co.com.synthax.bet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cuotas")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_partido", nullable = false)
    private Partido partido;

    @Column(name = "casa_apuestas", nullable = false)
    private String casaApuestas;  // betplay, rushbet, wplay

    @Column(name = "nombre_mercado", nullable = false)
    private String nombreMercado;  // "Over 2.5", "1X2 - Home"

    @Column(name = "valor_cuota", precision = 6, scale = 3, nullable = false)
    private BigDecimal valorCuota;  // 1.850

    @Column(name = "probabilidad_impl", precision = 5, scale = 4)
    private BigDecimal probabilidadImpl;  // 1 / cuota = 0.5405

    @Column(name = "ventaja", precision = 6, scale = 4)
    private BigDecimal ventaja;  // probabilidad_nuestra - probabilidad_impl

    @Column(name = "obtenido_en")
    private LocalDateTime obtenidoEn;

    @PrePersist
    protected void alCrear() {
        obtenidoEn = LocalDateTime.now();
        calcularProbabilidadImplicita();
    }

    @PreUpdate
    protected void alActualizar() {
        calcularProbabilidadImplicita();
    }

    private void calcularProbabilidadImplicita() {
        if (valorCuota != null && valorCuota.doubleValue() > 0) {
            probabilidadImpl = BigDecimal.valueOf(1.0 / valorCuota.doubleValue())
                    .setScale(4, java.math.RoundingMode.HALF_UP);
        }
    }
}
