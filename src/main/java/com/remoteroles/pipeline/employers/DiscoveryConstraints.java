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
     * - Essencial não atendido leva a nota para baixo de 2,5. Não a zero: a
     *   pessoa continua na lista, abaixo de todo mundo que atende, e o resumo
     *   diz o que falta — que é o que o dono pediu, "classificaria abaixo".
     *   Desde 20 Nov 2026 é escala e não corte: o trecho de 1 a 10 é levado
     *   para 1 a 2,5, e não mais {@code min(nota, 2,5)}. O corte empatava
     *   todo mundo que falhava um critério e tinha mais de 2,5 -- um sênior
     *   de 9 e um júnior de 3 liam os dois 2,5, e o desempate era a ordem dos
     *   vetores. É o mesmo defeito que a triagem tinha com o seu 4,0, achado
     *   no teste de ponta a ponta daquele dia. A escala parte de 1, e não de
     *   zero, por causa de {@link DiscoveryRanker#SHOWN_FLOOR}: multiplicar
     *   por 0,25 teria mandado para baixo de 1 -- para fora da lista -- quem o
     *   modelo leu entre 1 e 4, que é justamente a gente que esta regra diz
     *   que continua na lista.
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

    /*
     * O teto do que os descontos somam. Até 18 Nov 2026 eles se empilhavam sem
     * limite, e nas três vagas de teste daquele dia isso zerou gente que o
     * modelo tinha lido como parcialmente relevante: um sênior espanhol de 9
     * anos saiu de 4,0 para 0,7 (dois PARTIAL de fuso e um UNKNOWN), um sênior
     * holandês de 8 anos de 2,0 para 0. O modelo já leu os mesmos requisitos
     * quando deu a nota, então o desconto é desempate, não segunda sentença:
     * no máximo 2,0 no total, e nunca mais que metade da nota do modelo. Assim
     * zero só sai de duas coisas -- o modelo achou que nada tem a ver, ou a
     * pessoa está fora da localização -- e é isso que a web esconde.
     */
    static final double MAX_DEDUCTION = 2.0;

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
        double deducted = 0;
        boolean essentialUnmet = false;
        for (Check c : checks) {
            if (!c.constraint()) continue;
            String label = "#" + c.position() + " " + c.verdict().toLowerCase(Locale.ROOT)
                    + (c.essential() ? " essential" : " optional")
                    + ("MODEL".equals(c.source()) ? "" : " (" + c.source().toLowerCase(Locale.ROOT) + ")");
            switch (c.verdict()) {
                case "UNMET" -> {
                    if (c.essential()) {
                        essentialUnmet = true;
                        effects.add(label + ": scaled under " + ESSENTIAL_UNMET_CAP);
                    } else {
                        deducted += OPTIONAL_UNMET;
                        effects.add(label + ": -" + OPTIONAL_UNMET);
                    }
                }
                case "PARTIAL" -> {
                    double cost = c.essential() ? ESSENTIAL_PARTIAL : OPTIONAL_PARTIAL;
                    deducted += cost;
                    effects.add(label + ": -" + cost);
                }
                case "UNKNOWN" -> {
                    if (c.essential()) {
                        deducted += ESSENTIAL_UNKNOWN;
                        effects.add(label + ": -" + ESSENTIAL_UNKNOWN);
                    }
                }
                default -> { }
            }
        }
        double limit = Math.min(MAX_DEDUCTION, fit / 2);
        if (deducted > limit) effects.add("deductions limited to " + Math.round(limit * 10) / 10.0);
        double score = fit - Math.min(deducted, limit);
        if (essentialUnmet && score > DiscoveryRanker.SHOWN_FLOOR) {
            double floor = DiscoveryRanker.SHOWN_FLOOR;
            score = floor + (score - floor) * (ESSENTIAL_UNMET_CAP - floor) / (10 - floor);
        }
        score = Math.max(0, Math.min(10, score));
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
            "\\b(junior|mid[- ]?level|senior|staff|principal|lead(?!\\s*gen)|head of)\\b", Pattern.CASE_INSENSITIVE);

    /** The level a title asks for, as a requirement line, or null. */
    static String seniorityRequirement(String title) {
        if (title == null) return null;
        Matcher m = SENIORITY.matcher(title);
        return m.find() ? "Seniority: " + m.group(1).toLowerCase(Locale.ROOT) + " level (from the title)" : null;
    }

    /*
     * Um critério que a vaga declara em campo só vira requisito a mais quando
     * a descrição não o trouxe. No 18 Nov 2026 o fuso estava nas duas pontas
     * em duas das três vagas de teste (EU #6 e #9, LATAM #5 e #10), e quem
     * ficava um pouco fora do horário pagava o mesmo PARTIAL duas vezes. Os
     * textos extraídos ali eram "Working Central European hours" e "Working at
     * least 4 hours overlapping with US Eastern time" -- "hours" sozinho é o
     * sinal, porque é assim que as vagas falam de fuso.
     */
    private static final Pattern TIMEZONE_WORDS = Pattern.compile(
            "\\b(time\\s*zones?|timezones?|hours|overlap\\w*|"
            + "(?:eastern|central|pacific|mountain)\\s+(?:time|european)|"
            + "utc|gmt|cet|cest|eet|wet|est|edt|cst|pst|pdt|brt|aest)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SENIORITY_WORDS = Pattern.compile(
            "\\b(seniority|junior|mid[- ]?level|senior|staff|principal|head of|"
            + "(?:team|tech|technical)\\s+lead)\\b", Pattern.CASE_INSENSITIVE);

    /** Whether any extracted requirement already speaks about timezone or hours. */
    static boolean coversTimezone(List<String> requirements) {
        return requirements.stream().anyMatch(t -> t != null && TIMEZONE_WORDS.matcher(t).find());
    }

    /** Whether any extracted requirement already speaks about the level. */
    static boolean coversSeniority(List<String> requirements) {
        return requirements.stream().anyMatch(t -> t != null && SENIORITY_WORDS.matcher(t).find());
    }
}
