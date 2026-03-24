package co.com.synthax.bet.entity;

import co.com.synthax.bet.enums.PerfilRiesgo;
import co.com.synthax.bet.enums.PlanSuscripcion;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "suscriptores")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Suscriptor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_telegram", unique = true)
    private String idTelegram;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "email", unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false)
    private PlanSuscripcion plan = PlanSuscripcion.FREE;

    // Bankroll (Diferencial #1 — Simulador de Bankroll)
    @Column(name = "bankroll_inicial", precision = 10, scale = 2)
    private BigDecimal bankrollInicial;

    @Column(name = "bankroll_actual", precision = 10, scale = 2)
    private BigDecimal bankrollActual;

    @Enumerated(EnumType.STRING)
    @Column(name = "perfil_riesgo")
    private PerfilRiesgo perfilRiesgo = PerfilRiesgo.MODERADO;

    @Column(name = "activo")
    private Boolean activo = true;

    @Column(name = "suscrito_en")
    private LocalDateTime suscritoEn;

    @Column(name = "vence_en")
    private LocalDateTime venceEn;

    @PrePersist
    protected void alCrear() {
        suscritoEn = LocalDateTime.now();
        if (bankrollActual == null) {
            bankrollActual = bankrollInicial;
        }
    }
}
