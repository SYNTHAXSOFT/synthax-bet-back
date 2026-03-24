package co.com.synthax.bet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "movimientos_bankroll")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MovimientoBankroll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_suscriptor", nullable = false)
    private Suscriptor suscriptor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_pick")
    private Pick pick;

    @Column(name = "monto_apostado", precision = 10, scale = 2)
    private BigDecimal montoApostado;

    @Column(name = "monto_sugerido", precision = 10, scale = 2)
    private BigDecimal montoSugerido;  // sugerido por el simulador según perfil

    @Column(name = "ganancia_perdida", precision = 10, scale = 2)
    private BigDecimal gananciaPerdida;

    @Column(name = "bankroll_antes", precision = 10, scale = 2)
    private BigDecimal bankrollAntes;

    @Column(name = "bankroll_despues", precision = 10, scale = 2)
    private BigDecimal bankrollDespues;

    @Column(name = "fecha")
    private LocalDateTime fecha;

    @PrePersist
    protected void alCrear() {
        fecha = LocalDateTime.now();
    }
}
