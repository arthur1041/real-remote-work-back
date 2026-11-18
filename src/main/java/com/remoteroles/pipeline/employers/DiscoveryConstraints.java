package com.remoteroles.pipeline.employers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Os critérios básicos de uma vaga contra um perfil, como aritmética.
 *
 * <p>Separado do {@link DiscoveryRanker} para poder ser lido e testado sem
 * banco nem modelo: tudo aqui decide quantas posições alguém perde numa lista
 * que um empregador pagou para ver em ordem, e um erro aqui não lança nada —
 * aparece como uma lista na ordem errada que parece certa.
 *
 * <p><strong>Por que critério básico desconta e habilidade não.</strong> Em
 * 17 Nov 2026 a nota passou a ser o julgamento do modelo, porque a aritmética
 * da triagem punha um sênior de Go atrás de um júnior que escreveu "Python".
 * Isso continua certo para habilidade, que se aprende. Não é certo para onde a
 * pessoa pode trabalhar, quantos anos ela tem de carreira, a língua que fala
 * ou o fuso em que trabalha: nada disso se aprende no primeiro mês, e quem
 * pede "LATAM, 15 anos" e recebe Oriente Médio com cinco recebeu uma lista que
 * não serve. Pedido do dono em 18 Nov 2026.
 */
final class DiscoveryConstraints {

    private DiscoveryConstraints() {}

    /** One requirement as the arithmetic sees it. */
    record Check(int position, String requirement, boolean essential, boolean constraint,
                 String verdict, String source) {}

    /** The score after the constraints, and what each one did to it. */
    record Adjusted(double score, List<String> effects) {}

    /*
     * Os pesos. Escritos aqui, juntos, porque são a política inteira:
     *
     * - Essencial não atendido leva a nota a no máximo 2,5. Não a zero: a
     *   pessoa continua na lista, abaixo de todo mundo que atende, e o resumo
     *   diz o que falta — que é o que o dono pediu, "classificaria abaixo".
     * - Essencial quase atendido (PARTIAL: 12 anos contra "15+") custa 1,5.
     *   Num corte de dez perfis isso costuma ser três ou quatro posições.
     * - Essencial que ninguém sabe custa 0,3, para que, entre dois perfis
     *   iguais, o que confirma fique na frente do que não diz.
     * - Opcional não atendido custa 1,0; quase, 0,5.
     */
    static final double ESSENTIAL_UNMET_CAP = 2.5;
    static final double ESSENTIAL_PARTIAL = 1.5;
    static final double ESSENTIAL_UNKNOWN = 0.3;
    static final double OPTIONAL_UNMET = 1.0;
    static final double OPTIONAL_PARTIAL = 0.5;

    /** Quanto dos anos pedidos ainda é "um pouco abaixo". 12 de 15 é; 10 de 15 não é. */
    static final double YEARS_PARTIAL_RATIO = 0.75;

    /**
     * A nota do modelo depois dos critérios.
     *
     * <p>Fora da geografia é zero, sem conversa: é a única condição que é
     * promessa ao candidato além de pedido do empregador, e a web já não
     * deveria ter deixado a pessoa entrar — isto é a segunda tranca.
     */
    static Adjusted adjust(double fit, boolean eligible, List<Check> checks) {
        List<String> effects = new ArrayList<>();
        if (!eligible) {
            effects.add("outside the role's location: 0");
            return new Adjusted(0, effects);
        }
        double score = fit;
        double cap = 10;
        for (Check c : checks) {
            if (!c.constraint()) continue;
            String label = "#" + c.position() + " " + c.verdict().toLowerCase(Locale.ROOT)
                    + (c.essential() ? " essential" : " optional")
                    + ("MODEL".equals(c.source()) ? "" : " (" + c.source().toLowerCase(Locale.ROOT) + ")");
            switch (c.verdict()) {
                case "UNMET" -> {
                    if (c.essential()) {
                        cap = Math.min(cap, ESSENTIAL_UNMET_CAP);
                        effects.add(label + ": capped at " + ESSENTIAL_UNMET_CAP);
                    } else {
                        score -= OPTIONAL_UNMET;
                        effects.add(label + ": -" + OPTIONAL_UNMET);
                    }
                }
                case "PARTIAL" -> {
                    double cost = c.essential() ? ESSENTIAL_PARTIAL : OPTIONAL_PARTIAL;
                    score -= cost;
                    effects.add(label + ": -" + cost);
                }
                case "UNKNOWN" -> {
                    if (c.essential()) {
                        score -= ESSENTIAL_UNKNOWN;
                        effects.add(label + ": -" + ESSENTIAL_UNKNOWN);
                    }
                }
                default -> { }
            }
        }
        score = Math.max(0, Math.min(cap, score));
        return new Adjusted(Math.round(score * 10) / 10.0, effects);
    }

    /*
     * "15+ years", "at least 5 years", "minimum of 10 yrs", "3-5 years",
     * "8 or more years". O número que vem antes de "year"/"yr", tomando o
     * menor de um intervalo, porque "3-5 anos" pede três.
     */
    private static final Pattern YEARS = Pattern.compile(
            "(\\d{1,2})\\s*(?:\\+|plus)?\\s*(?:(?:-|–|to)\\s*\\d{1,2}\\s*)?(?:\\+\\s*)?(?:or more\\s+)?(?:years?|yrs?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** The years a requirement asks for, or null when it names none. */
    static Integer requiredYears(String text) {
        if (text == null) return null;
        Matcher m = YEARS.matcher(text);
        if (!m.find()) return null;
        int years = Integer.parseInt(m.group(1));
        return years >= 1 && years <= 40 ? years : null;
    }

    /**
     * Anos pedidos contra anos declarados, ou null quando não há o que contar.
     *
     * <p><strong>Só desce, nunca sobe.</strong> Os anos declarados são de
     * carreira; o requisito pode ser "3+ anos de Go". Quem tem seis anos de
     * carreira não tem dez de nada — isso o código sabe. Quem tem doze pode ter
     * só dois de Go, e isso só o perfil diz. Então MET daqui não é resposta, e
     * quem decide nesse caso é o modelo; ver {@link #combine}.
     */
    static String yearsVerdict(Integer required, Integer declared) {
        if (required == null || declared == null) return null;
        if (declared >= required) return "MET";
        return declared >= required * YEARS_PARTIAL_RATIO ? "PARTIAL" : "UNMET";
    }

    /** The worse of the code's answer and the model's, where the code's can only lower it. */
    static String combine(String code, String model) {
        if (code == null || "MET".equals(code)) return model;
        if ("UNMET".equals(code) || "UNMET".equals(model)) return "UNMET";
        return "PARTIAL";
    }

    private static final Pattern SENIORITY = Pattern.compile(
            "\\b(junior|mid[- ]?level|senior|staff|principal|lead|head of)\\b", Pattern.CASE_INSENSITIVE);

    /** The level a title asks for, as a requirement line, or null. */
    static String seniorityRequirement(String title) {
        if (title == null) return null;
        Matcher m = SENIORITY.matcher(title);
        return m.find() ? "Seniority: " + m.group(1).toLowerCase(Locale.ROOT) + " level (from the title)" : null;
    }
}
