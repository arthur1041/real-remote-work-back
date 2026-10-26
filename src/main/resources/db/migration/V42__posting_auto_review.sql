-- ============================================================================
-- Revisão prioritária: o que o modelo achou de uma vaga de empregador.
--
-- Especificação na decisão 4 de `docs/plano-starter.md`.
--
-- **O problema não é a mediana, é a cauda.** Das 32 vagas de empregador já
-- revisadas à mão, a mediana saiu em 1,6 h e a média em 10,1 h -- mas a pior
-- esperou 95,6 h, quatro dias, porque a fila é lida por uma pessoa e uma
-- pessoa dorme. A promessa do Free é "em até 24 h" e ela é cumprida quase
-- sempre; o que o Starter vende é o fim do caso em que não é.
--
-- **O modelo só aprova, nunca recusa.** Esta é a regra inteira e está no
-- documento: pagar nunca garante aprovação, e vaga que falhe na validação ou
-- produza resultado incerto vai para a fila manual exatamente como hoje. Por
-- isso o veredito tem dois valores e não três: APPROVED, que já publicou, e
-- REFERRED, que é "não sei, olhe você". Não existe REJECTED aqui -- recusar é
-- uma decisão que fecha a porta de um cliente pagante, e nenhum modelo toma
-- essa por conta própria.
--
-- **`auto_reviewed_at` é o que impede a releitura.** A varredura procura
-- PENDING com esta coluna nula, então uma vaga referida fica referida: ela não
-- volta a custar chamadas de modelo a cada minuto enquanto espera a pessoa. O
-- empregador que editar e reenviar limpa a coluna, e aí sim é lida de novo --
-- é uma vaga diferente.
-- ============================================================================

alter table employer_postings
    -- Quando o modelo leu. Nulo em tudo que ele ainda não leu, inclusive em
    -- toda vaga de empregador Free, que nunca passa por aqui.
    add column auto_reviewed_at    timestamptz,

    add column auto_review_verdict text check (auto_review_verdict in ('APPROVED', 'REFERRED')),

    -- Por que, nas palavras do modelo, e é o que a pessoa lê na fila antes de
    -- decidir. Uma vaga referida sem motivo legível não teria poupado nada:
    -- a pessoa reabriria a análise inteira do zero.
    add column auto_review_note    text;

-- A consulta da varredura, e só ela: PENDING ainda não lida. Parcial porque
-- aprovada e referida são o estado final para este propósito, e um índice que
-- cresce com o histórico para responder sobre a fila de hoje é um índice que
-- fica mais lento justamente conforme o produto dá certo.
create index employer_postings_awaiting_auto_review
    on employer_postings (created_at)
    where status = 'PENDING' and auto_reviewed_at is null;

comment on column employer_postings.auto_review_verdict is
    'APPROVED = publicada pelo modelo; REFERRED = mandada para a fila humana. Nunca recusa.';
